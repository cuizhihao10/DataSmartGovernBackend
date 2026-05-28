/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Agent runtime event 投影视图。
 *
 * <p>该 DTO 面向控制台、排障接口和未来“Agent 运行事件中心”页面。
 * 它返回的是 Java 控制面已经消费到的事件投影，不等同于 Kafka 原始消息，也不等同于 Python Runtime 的 WebSocket replay。
 * 这样做能让前端和运维人员以稳定字段理解事件，而不需要直接解析 Kafka payload。</p>
 */
public record AgentRuntimeEventProjectionView(
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
