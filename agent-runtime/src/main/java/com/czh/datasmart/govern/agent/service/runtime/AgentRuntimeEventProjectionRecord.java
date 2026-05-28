/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * Java 控制面保存的 Agent runtime event 投影记录。
 *
 * <p>它不是 Kafka 原始消息的简单复制，而是 Java agent-runtime 为“运行详情页、状态同步、审计索引”
 * 准备的控制面视图。当前第一版保存在内存里，后续可以平滑迁移到 MySQL、ClickHouse 或审计中心。</p>
 *
 * <p>`identityKey` 是本阶段非常关键的字段：Kafka consumer 在 rebalance、ack 丢失、实例重启后可能重复收到同一条消息。
 * 如果没有幂等键，控制面详情页会出现重复事件，甚至后续状态机被重复推进。
 * Python payload 当前没有独立 eventId，因此 Java 侧用 source/run/session/request/sequence/eventType/createdAt 组合生成近似稳定键。</p>
 */
public record AgentRuntimeEventProjectionRecord(
        String identityKey,
        String schemaVersion,
        String source,
        String eventType,
        String stage,
        String message,
        String severity,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        Long sequence,
        Instant createdAt,
        Instant publishedAt,
        Instant consumedAt,
        Map<String, Object> attributes
) {
}
