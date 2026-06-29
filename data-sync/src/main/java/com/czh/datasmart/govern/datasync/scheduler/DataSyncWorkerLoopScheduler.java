/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - DataSyncWorkerLoopScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.DataSyncWorkerLoopProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunResult;
import com.czh.datasmart.govern.datasync.service.DataSyncWorkerLoopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * data-sync 内嵌 worker loop 定时调度器。
 *
 * <p>当前项目正在向“闭环收敛”推进，不能只停留在接口和状态机层面。
 * 本调度器让 data-sync 在配置开启后可以周期性执行 claim -> run-once dispatch -> complete/fail，
 * 从而形成最小可运行的数据同步闭环。</p>
 *
 * <p>为什么默认仍通过配置关闭：</p>
 * <p>1. worker loop 会触发真实源端读取和目标端写入，不应该在开发者无感启动 data-sync 时自动运行；</p>
 * <p>2. 商用环境通常会先确认 datasource-management、目标库、权限策略、服务账号签名和监控告警都已就绪；</p>
 * <p>3. 未来如果拆成独立 worker 服务，这个调度器也可以关闭，由外部 worker 复用同一个 {@link DataSyncWorkerLoopService}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncWorkerLoopScheduler {

    private final DataSyncWorkerLoopProperties properties;
    private final DataSyncWorkerLoopService workerLoopService;
    private final DataSyncWorkerLoopMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 周期性执行 worker loop。
     *
     * <p>同一个 JVM 内使用 {@link AtomicBoolean} 防重入，避免上一轮真实数据读写尚未结束时再次认领任务。
     * 多实例部署时仍依赖 execution 表 claim SQL 的原子状态更新来裁决并发，只有一个实例能把 QUEUED 改为 RUNNING。</p>
     */
    @Scheduled(
            initialDelayString = "${datasmart.data-sync.worker-loop.initial-delay-ms:20000}",
            fixedDelayString = "${datasmart.data-sync.worker-loop.fixed-delay-ms:10000}"
    )
    public void runWorkerLoop() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("data-sync worker loop 上一轮尚未结束，本轮跳过");
            metrics.recordSkippedByReentry();
            return;
        }

        Instant startedAt = Instant.now();
        try {
            SyncWorkerLoopRunResult result = workerLoopService.runOnce(schedulerRequest(), schedulerActorContext());
            metrics.recordRunSuccess(result, Duration.between(startedAt, Instant.now()));
            if (result.claimedCount() > 0 || result.failedCount() > 0) {
                log.info("data-sync worker loop 执行完成: claimed={}, dispatched={}, completed={}, failed={}, queueDrained={}",
                        result.claimedCount(), result.dispatchedCount(), result.completedCount(),
                        result.failedCount(), result.queueDrained());
            } else {
                log.debug("data-sync worker loop 本轮未认领到执行记录");
            }
        } catch (Exception exception) {
            metrics.recordRunFailure(Duration.between(startedAt, Instant.now()));
            log.error("data-sync worker loop 调度失败，系统将在下一轮继续尝试", exception);
        } finally {
            running.set(false);
        }
    }

    /**
     * 为定时调度构造运行请求。
     */
    private SyncWorkerLoopRunRequest schedulerRequest() {
        SyncWorkerLoopRunRequest request = new SyncWorkerLoopRunRequest();
        request.setExecutorId(properties.getExecutorId());
        request.setTenantId(properties.getTenantId());
        request.setMaxExecutions(properties.getMaxExecutionsPerRun());
        request.setLeaseSeconds(properties.getLeaseSeconds());
        return request;
    }

    /**
     * 为定时调度构造服务账号审计上下文。
     */
    private SyncActorContext schedulerActorContext() {
        return new SyncActorContext(
                properties.getTenantId(),
                properties.getSystemActorId(),
                properties.getSystemActorRole(),
                properties.getTraceIdPrefix() + "-scheduler"
        );
    }
}
