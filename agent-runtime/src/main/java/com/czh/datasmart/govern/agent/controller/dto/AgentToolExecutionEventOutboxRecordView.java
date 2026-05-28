/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxRecordView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxRecord;

import java.time.Instant;

/**
 * 工具执行事件 outbox 查询视图。
 *
 * <p>该视图刻意不返回 payloadJson。原因是 outbox payload 虽然来自脱敏事件，但它仍然属于服务间投递数据，
 * 不应默认暴露给普通控制台查询。真实审计中心如果需要查看完整 payload，应通过更高权限的审计接口读取。</p>
 */
public record AgentToolExecutionEventOutboxRecordView(
        String outboxId,
        String eventId,
        String eventType,
        String schemaVersion,
        String source,
        String partitionKey,
        String tenantId,
        String projectId,
        String workspaceId,
        String actorId,
        String sessionId,
        String runId,
        String auditId,
        String toolCode,
        String currentState,
        String status,
        int attemptCount,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRetryAt,
        Instant publishedAt,
        String lastError,
        int payloadSizeBytes,
        boolean payloadTruncated
) {

    public static AgentToolExecutionEventOutboxRecordView from(AgentToolExecutionEventOutboxRecord record) {
        return new AgentToolExecutionEventOutboxRecordView(
                record.outboxId(),
                record.eventId(),
                record.eventType(),
                record.schemaVersion(),
                record.source(),
                record.partitionKey(),
                record.tenantId(),
                record.projectId(),
                record.workspaceId(),
                record.actorId(),
                record.sessionId(),
                record.runId(),
                record.auditId(),
                record.toolCode(),
                record.currentState(),
                record.status().name(),
                record.attemptCount(),
                record.occurredAt(),
                record.createdAt(),
                record.updatedAt(),
                record.nextRetryAt(),
                record.publishedAt(),
                record.lastError(),
                record.payloadSizeBytes(),
                record.payloadTruncated()
        );
    }
}
