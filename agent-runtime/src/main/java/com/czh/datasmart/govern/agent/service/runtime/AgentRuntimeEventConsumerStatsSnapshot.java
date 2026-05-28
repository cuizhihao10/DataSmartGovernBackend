/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumerStatsSnapshot.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;

/**
 * Agent runtime event consumer 统计快照。
 *
 * <p>这是当前阶段的轻量诊断模型。后续接入 Micrometer/Prometheus 时，可以把这些字段一一映射为 counter、
 * gauge 和 timer；在那之前，管理 API 仍然可以通过这个快照看见消费链路是否活跃、是否存在大量重复或拒绝。</p>
 */
public record AgentRuntimeEventConsumerStatsSnapshot(
        long acceptedCount,
        long duplicateCount,
        long rejectedCount,
        long malformedJsonCount,
        long unsupportedSchemaCount,
        long missingEventTypeCount,
        long missingCorrelationIdCount,
        long emptyPayloadCount,
        long totalProcessingNanos,
        double averageProcessingMillis,
        Instant lastAcceptedAt,
        Instant lastDuplicateAt,
        Instant lastRejectedAt,
        String lastRejectedReason
) {
}
