/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptOutboxScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.TaskManagementReceiptOutboxDispatchResult;
import com.czh.datasmart.govern.datasync.service.support.DataSyncTaskManagementReceiptOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * task-management receipt outbox 后台补偿调度器。
 *
 * <p>它解决的是“task-management 短暂不可用导致 receipt 投递失败”的最终一致问题：
 * publisher 已经把低敏 receipt 写入 data-sync 本地 outbox，本调度器周期性扫描 due 记录并重试投递。</p>
 *
 * <p>并发说明：</p>
 * <p>1. 同 JVM 使用 AtomicBoolean 防止上一轮尚未结束又进入下一轮；</p>
 * <p>2. 多实例部署时，Mapper 的 markDelivering 条件更新负责数据库级抢占，只有一个实例能处理同一条 receipt；</p>
 * <p>3. 如果实例在 DELIVERING 中崩溃，staleDeliveringSeconds 到期后记录会重新进入可处理范围。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncTaskManagementReceiptOutboxScheduler {

    private final DataSyncTaskManagementReceiptProperties properties;
    private final DataSyncTaskManagementReceiptOutboxService outboxService;
    private final DataSyncTaskManagementReceiptOutboxMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            initialDelayString = "${datasmart.data-sync.task-management-receipt.outbox.initial-delay-ms:45000}",
            fixedDelayString = "${datasmart.data-sync.task-management-receipt.outbox.fixed-delay-ms:30000}"
    )
    public void dispatchDueReceipts() {
        if (!properties.isEnabled()
                || properties.getOutbox() == null
                || !properties.getOutbox().isEnabled()
                || !properties.getOutbox().isSchedulerEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("task-management receipt outbox 上一轮补偿尚未结束，本轮跳过");
            metrics.recordSchedulerSkipped();
            return;
        }
        try {
            TaskManagementReceiptOutboxDispatchResult result =
                    outboxService.dispatchDue(properties.getOutbox().getBatchSize(), schedulerActorContext());
            if (result.deliveredCount() > 0 || result.retryScheduledCount() > 0 || result.deadLetteredCount() > 0) {
                log.info("task-management receipt outbox 补偿完成: scanned={}, delivered={}, retryScheduled={}, deadLettered={}, skipped={}",
                        result.scannedCount(), result.deliveredCount(), result.retryScheduledCount(),
                        result.deadLetteredCount(), result.skippedCount());
            } else {
                log.debug("task-management receipt outbox 本轮无待补偿记录: scanned={}", result.scannedCount());
            }
        } catch (Exception exception) {
            metrics.recordDispatchFailure();
            log.error("task-management receipt outbox 补偿调度失败，系统将在下一轮继续尝试", exception);
        } finally {
            running.set(false);
        }
    }

    private SyncActorContext schedulerActorContext() {
        return new SyncActorContext(
                null,
                properties.getOutbox().getSystemActorId(),
                properties.getOutbox().getSystemActorRole(),
                properties.getOutbox().getTraceIdPrefix() + "-scheduler"
        );
    }
}
