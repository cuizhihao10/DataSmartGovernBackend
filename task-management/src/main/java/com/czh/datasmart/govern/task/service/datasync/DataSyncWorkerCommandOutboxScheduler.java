/**
 * @Author : Cui
 * @Date: 2026/06/21 00:35
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DataSync worker outbox 后台调度器。
 *
 * <p>该组件把手动 outbox 控制面推进为可配置自动循环，但默认仍然关闭。
 * DataSync outbox 与普通 dry-run 不同，它最终会调用 datasource-management 幂等入口并创建/入队同步任务，
 * 属于真实副作用链路。因此必须满足多重开关后才运行：</p>
 * <p>1. {@code enabled=true}：总 worker 能力允许；</p>
 * <p>2. {@code dryRunOnly=false}：系统允许真实副作用，而不是只做预检；</p>
 * <p>3. {@code schedulerEnabled=true}：后台 scheduler 总开关允许；</p>
 * <p>4. {@code dataSyncOutboxSchedulerEnabled=true}：DataSync outbox 子链路单独允许。</p>
 *
 * <p>为什么还要单独的 dataSyncOutboxSchedulerEnabled：</p>
 * <p>Agent worker 未来可能承载数据同步、质量检测、元数据扫描、导出、报表生成等多种工具。
 * 打开通用 scheduler 不应自动打开所有工具副作用。单独开关能让运维按工具链路灰度，
 * 例如先只开放 dry-run，再开放 data-sync，再开放质量检测。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxScheduler {

    private final AgentAsyncToolWorkerProperties properties;
    private final DataSyncWorkerCommandOutboxSchedulerService schedulerService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时触发一轮 DataSync outbox 后台调度。
     *
     * <p>调度顺序由 {@link DataSyncWorkerCommandOutboxSchedulerService} 保证：
     * 先恢复 stale DISPATCHING，再投递 PENDING/到期 DEFERRED。这里专注于定时触发、安全开关、并发互斥和日志。</p>
     */
    @Scheduled(fixedDelayString =
            "${datasmart.task-management.agent-async-worker.data-sync-outbox-scheduler-fixed-delay-ms:5000}")
    public void dispatchScheduledTick() {
        if (!shouldRunScheduler()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("DataSync outbox 后台 scheduler 上一轮尚未结束，本轮跳过，executorId={}",
                    properties.getExecutorId());
            return;
        }
        try {
            DataSyncWorkerOutboxSchedulerTickResult result = schedulerService.dispatchScheduledTick();
            if (result.hasMeaningfulWork()) {
                log.info("DataSync outbox 后台 scheduler 单轮完成，recoveryScanned={}, recoveryRecovered={}, recoveryDeadLetter={}, claimed={}, delivered={}, succeeded={}, deferred={}, failed={}, skipped={}",
                        result.recoveryResult().scannedCount(),
                        result.recoveryResult().recoveredCount(),
                        result.recoveryResult().deadLetterCount(),
                        result.dispatchResult().claimedCount(),
                        result.dispatchResult().deliveredCount(),
                        result.dispatchResult().succeededCount(),
                        result.dispatchResult().deferredCount(),
                        result.dispatchResult().failedCount(),
                        result.dispatchResult().skippedCount());
            }
        } catch (RuntimeException exception) {
            log.error("DataSync outbox 后台 scheduler 单轮执行失败，executorId={}, error={}",
                    properties.getExecutorId(), exception.getMessage(), exception);
        } finally {
            running.set(false);
        }
    }

    private boolean shouldRunScheduler() {
        return properties.isEnabled()
                && !properties.isDryRunOnly()
                && properties.isSchedulerEnabled()
                && properties.isDataSyncOutboxSchedulerEnabled();
    }
}
