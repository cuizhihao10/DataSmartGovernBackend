/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptOutboxMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.controller.dto.TaskManagementReceiptOutboxDispatchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * task-management receipt outbox 指标。
 *
 * <p>receipt outbox 是跨服务最终一致链路，生产环境必须知道：</p>
 * <p>1. 最近一轮是否有大量重试；</p>
 * <p>2. 是否出现 DEAD_LETTER；</p>
 * <p>3. 后台调度器是否长期没有成功运行；</p>
 * <p>4. 是否因上一轮未结束而跳过。</p>
 *
 * <p>指标标签刻意保持低基数，只使用 result/outcome/reason 这类枚举，不使用 receiptId、taskId、executionId、tenantId 或 traceId。</p>
 */
@Component
public class DataSyncTaskManagementReceiptOutboxMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicLong lastScanned = new AtomicLong();
    private final AtomicLong lastDelivered = new AtomicLong();
    private final AtomicLong lastRetryScheduled = new AtomicLong();
    private final AtomicLong lastDeadLettered = new AtomicLong();
    private final AtomicLong lastSuccessEpochSecond = new AtomicLong();
    private final AtomicLong lastFailureEpochSecond = new AtomicLong();

    public DataSyncTaskManagementReceiptOutboxMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * 记录一轮 outbox dispatch 成功返回。
     */
    public void recordDispatchSuccess(TaskManagementReceiptOutboxDispatchResult result) {
        lastScanned.set(result == null ? 0L : result.scannedCount());
        lastDelivered.set(result == null ? 0L : result.deliveredCount());
        lastRetryScheduled.set(result == null ? 0L : result.retryScheduledCount());
        lastDeadLettered.set(result == null ? 0L : result.deadLetteredCount());
        lastSuccessEpochSecond.set(Instant.now().getEpochSecond());

        Counter.builder("datasmart_data_sync_task_receipt_outbox_tick_total")
                .description("data-sync task-management receipt outbox 调度轮次")
                .tag("result", resolveResult(result))
                .register(meterRegistry)
                .increment();
        incrementOutcome("DELIVERED", result == null ? 0 : result.deliveredCount());
        incrementOutcome("RETRY_SCHEDULED", result == null ? 0 : result.retryScheduledCount());
        incrementOutcome("DEAD_LETTERED", result == null ? 0 : result.deadLetteredCount());
        incrementOutcome("SKIPPED", result == null ? 0 : result.skippedCount());
    }

    public void recordSchedulerSkipped() {
        Counter.builder("datasmart_data_sync_task_receipt_outbox_skip_total")
                .description("data-sync receipt outbox 调度跳过次数")
                .tag("reason", "REENTRY")
                .register(meterRegistry)
                .increment();
    }

    public void recordDispatchFailure() {
        lastFailureEpochSecond.set(Instant.now().getEpochSecond());
        Counter.builder("datasmart_data_sync_task_receipt_outbox_failure_total")
                .description("data-sync receipt outbox 调度外层异常次数")
                .tag("phase", "SCHEDULER")
                .register(meterRegistry)
                .increment();
    }

    private void registerGauges() {
        Gauge.builder("datasmart_data_sync_task_receipt_outbox_last_scanned", lastScanned, AtomicLong::get)
                .description("data-sync receipt outbox 最近一轮扫描数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_receipt_outbox_last_delivered", lastDelivered, AtomicLong::get)
                .description("data-sync receipt outbox 最近一轮成功投递数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_receipt_outbox_last_retry_scheduled", lastRetryScheduled, AtomicLong::get)
                .description("data-sync receipt outbox 最近一轮安排重试数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_receipt_outbox_last_dead_lettered", lastDeadLettered, AtomicLong::get)
                .description("data-sync receipt outbox 最近一轮死信数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_receipt_outbox_last_success_epoch_seconds",
                        lastSuccessEpochSecond, AtomicLong::get)
                .description("data-sync receipt outbox 最近一次成功调度 Unix 秒时间戳")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_receipt_outbox_last_failure_epoch_seconds",
                        lastFailureEpochSecond, AtomicLong::get)
                .description("data-sync receipt outbox 最近一次调度失败 Unix 秒时间戳")
                .register(meterRegistry);
    }

    private void incrementOutcome(String outcome, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("datasmart_data_sync_task_receipt_outbox_delivery_total")
                .description("data-sync receipt outbox 投递结果数量")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment(count);
    }

    private String resolveResult(TaskManagementReceiptOutboxDispatchResult result) {
        if (result == null || result.scannedCount() == 0) {
            return "EMPTY";
        }
        if (result.deadLetteredCount() > 0) {
            return "DEAD_LETTERED";
        }
        if (result.retryScheduledCount() > 0 && result.deliveredCount() > 0) {
            return "MIXED";
        }
        if (result.retryScheduledCount() > 0) {
            return "RETRY_WAIT";
        }
        if (result.deliveredCount() > 0) {
            return "DELIVERED";
        }
        return "SCANNED_ONLY";
    }
}
