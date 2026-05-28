/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumerDiagnosticsView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * Agent runtime event consumer 诊断视图。
 *
 * <p>该 DTO 面向平台管理员、运维和后续 observability 模块。
 * 它不返回任何用户 prompt、工具参数或 attributes 详情，只返回消费链路配置、计数和最近拒绝原因，
 * 目的是帮助判断“Java 控制面有没有在消费 Python Runtime 事件，以及消费质量如何”。</p>
 */
public record AgentRuntimeEventConsumerDiagnosticsView(
        boolean kafkaConsumerEnabled,
        String topic,
        String groupId,
        int maxEventsPerRun,
        int maxTotalEvents,
        int currentProjectionSize,
        long acceptedCount,
        long duplicateCount,
        long rejectedCount,
        long malformedJsonCount,
        long unsupportedSchemaCount,
        long missingEventTypeCount,
        long missingCorrelationIdCount,
        long emptyPayloadCount,
        double averageProcessingMillis,
        Instant lastAcceptedAt,
        Instant lastDuplicateAt,
        Instant lastRejectedAt,
        String lastRejectedReason
) {
}
