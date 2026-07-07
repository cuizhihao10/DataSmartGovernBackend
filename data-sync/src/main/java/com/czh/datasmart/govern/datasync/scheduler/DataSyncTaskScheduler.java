/**
 * @Author : Cui
 * @Date: 2026/07/07 23:13
 * @Description DataSmart Govern Backend - DataSyncTaskScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.DataSyncTaskSchedulerProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchResult;
import com.czh.datasmart.govern.datasync.service.DataSyncTaskScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * data-sync 任务级定时调度器。
 *
 * <p>本调度器负责“定期全量/定期批量的自动触发”，但不负责真实数据搬运：</p>
 * <p>1. 它扫描到期任务并创建 SCHEDULED execution；</p>
 * <p>2. 已有 worker loop 继续认领 execution 并调用 datasource-management；</p>
 * <p>3. 因此可以单独开启 task scheduler 做调度链路验证，也可以再开启 worker loop 做完整执行链路。</p>
 *
 * <p>同一个 JVM 内用 {@link AtomicBoolean} 防止 fixedDelay 任务重入；多实例之间依赖 task.schedule_version
 * 的数据库原子更新裁决，避免重复触发同一个任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncTaskScheduler {

    private final DataSyncTaskSchedulerProperties properties;
    private final DataSyncTaskScheduleService scheduleService;
    private final DataSyncTaskSchedulerMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 周期性扫描并派发到期定时任务。
     */
    @Scheduled(
            initialDelayString = "${datasmart.data-sync.task-scheduler.initial-delay-ms:30000}",
            fixedDelayString = "${datasmart.data-sync.task-scheduler.fixed-delay-ms:15000}"
    )
    public void dispatchDueTasks() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("data-sync task scheduler 上一轮尚未结束，本轮跳过");
            metrics.recordSkippedByReentry();
            return;
        }

        Instant startedAt = Instant.now();
        try {
            SyncTaskScheduleDispatchResult result = scheduleService.dispatchDueTasks(
                    schedulerRequest(),
                    schedulerActorContext()
            );
            metrics.recordRunSuccess(result, Duration.between(startedAt, Instant.now()));
            if (result.createdExecutionCount() > 0 || result.skippedByConcurrencyCount() > 0
                    || result.skippedByMisfirePolicyCount() > 0) {
                log.info("data-sync task scheduler 执行完成: scanned={}, createdExecutions={}, skippedConcurrency={}, skippedMisfire={}",
                        result.scannedTaskCount(), result.createdExecutionCount(),
                        result.skippedByConcurrencyCount(), result.skippedByMisfirePolicyCount());
            } else {
                log.debug("data-sync task scheduler 本轮没有到期任务");
            }
        } catch (Exception exception) {
            metrics.recordRunFailure(Duration.between(startedAt, Instant.now()));
            log.error("data-sync task scheduler 调度失败，系统将在下一轮继续尝试", exception);
        } finally {
            running.set(false);
        }
    }

    private SyncTaskScheduleDispatchRequest schedulerRequest() {
        SyncTaskScheduleDispatchRequest request = new SyncTaskScheduleDispatchRequest();
        request.setTenantId(properties.getTenantId());
        request.setLimit(properties.getBatchSize());
        request.setDryRun(false);
        return request;
    }

    private SyncActorContext schedulerActorContext() {
        return new SyncActorContext(
                properties.getTenantId(),
                properties.getSystemActorId(),
                properties.getSystemActorRole(),
                properties.getTraceIdPrefix() + "-scheduler"
        );
    }
}
