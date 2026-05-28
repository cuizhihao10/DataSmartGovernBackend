/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumerStats.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent runtime event consumer 轻量统计器。
 *
 * <p>商业化系统里 Kafka consumer 不能只有日志。日志适合追查单次异常，但平台运营更需要趋势：
 * 消费了多少事件、重复多少、拒绝多少、平均处理耗时多少、最近一次拒绝原因是什么。
 * 当前先用内存 Atomic 计数器落地，后续可以平滑对接 Micrometer、Prometheus 和告警规则。</p>
 */
@Component
public class AgentRuntimeEventConsumerStats {

    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong malformedJsonCount = new AtomicLong();
    private final AtomicLong unsupportedSchemaCount = new AtomicLong();
    private final AtomicLong missingEventTypeCount = new AtomicLong();
    private final AtomicLong missingCorrelationIdCount = new AtomicLong();
    private final AtomicLong emptyPayloadCount = new AtomicLong();
    private final AtomicLong totalProcessingNanos = new AtomicLong();
    private final AtomicReference<Instant> lastAcceptedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastDuplicateAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastRejectedAt = new AtomicReference<>();
    private final AtomicReference<String> lastRejectedReason = new AtomicReference<>();

    /**
     * 记录一次消费结果。
     *
     * <p>该方法只做内存计数，不抛业务异常，避免“指标系统本身出问题”影响 Kafka 消费主链路。
     * 真正接 Micrometer 时也应保持这一原则：指标失败不能导致业务消息失败。</p>
     */
    public void record(AgentRuntimeEventConsumeResult result, long processingNanos) {
        totalProcessingNanos.addAndGet(Math.max(0, processingNanos));
        Instant now = Instant.now();
        if (result.accepted()) {
            acceptedCount.incrementAndGet();
            lastAcceptedAt.set(now);
            return;
        }
        if (result.duplicate()) {
            duplicateCount.incrementAndGet();
            lastDuplicateAt.set(now);
            return;
        }
        rejectedCount.incrementAndGet();
        lastRejectedAt.set(now);
        lastRejectedReason.set(result.reason());
        incrementRejectedReason(result.reason());
    }

    public AgentRuntimeEventConsumerStatsSnapshot snapshot() {
        long processed = acceptedCount.get() + duplicateCount.get() + rejectedCount.get();
        long nanos = totalProcessingNanos.get();
        double averageMillis = processed == 0 ? 0.0 : (nanos / 1_000_000.0) / processed;
        return new AgentRuntimeEventConsumerStatsSnapshot(
                acceptedCount.get(),
                duplicateCount.get(),
                rejectedCount.get(),
                malformedJsonCount.get(),
                unsupportedSchemaCount.get(),
                missingEventTypeCount.get(),
                missingCorrelationIdCount.get(),
                emptyPayloadCount.get(),
                nanos,
                averageMillis,
                lastAcceptedAt.get(),
                lastDuplicateAt.get(),
                lastRejectedAt.get(),
                lastRejectedReason.get()
        );
    }

    private void incrementRejectedReason(String reason) {
        if ("MALFORMED_JSON".equals(reason)) {
            malformedJsonCount.incrementAndGet();
        } else if ("UNSUPPORTED_SCHEMA_VERSION".equals(reason)) {
            unsupportedSchemaCount.incrementAndGet();
        } else if ("MISSING_EVENT_TYPE".equals(reason)) {
            missingEventTypeCount.incrementAndGet();
        } else if ("MISSING_CORRELATION_ID".equals(reason)) {
            missingCorrelationIdCount.incrementAndGet();
        } else if ("EMPTY_PAYLOAD".equals(reason)) {
            emptyPayloadCount.incrementAndGet();
        }
    }
}
