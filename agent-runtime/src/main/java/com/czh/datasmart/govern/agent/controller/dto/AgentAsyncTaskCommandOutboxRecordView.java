/**
 * @Author : Cui
 * @Date: 2026/05/31 17:17
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxRecordView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;

import java.time.Instant;

/**
 * Agent 异步命令 outbox 记录安全视图。
 *
 * <p>该视图不返回 payloadJson，因为 payload 虽然已经做过安全收口，但仍属于服务间命令体。
 * 诊断接口只展示 commandId、状态、目标、重试次数、payloadReference 和错误摘要，避免前端或日志扩散命令明细。</p>
 */
public record AgentAsyncTaskCommandOutboxRecordView(
        String outboxId,
        String commandId,
        String idempotencyKey,
        String schemaVersion,
        String commandType,
        String commandTopic,
        String consumerService,
        String sessionId,
        String runId,
        String auditId,
        String toolCode,
        String targetService,
        String targetEndpoint,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String actorId,
        String traceId,
        String payloadReference,
        String status,
        Integer attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRetryAt,
        Instant publishedAt,
        String lastError,
        Integer payloadSizeBytes,
        Boolean payloadTruncated
) {

    public static AgentAsyncTaskCommandOutboxRecordView from(AgentAsyncTaskCommandOutboxRecord record) {
        return new AgentAsyncTaskCommandOutboxRecordView(
                record.outboxId(),
                record.commandId(),
                record.idempotencyKey(),
                record.schemaVersion(),
                record.commandType(),
                record.commandTopic(),
                record.consumerService(),
                record.sessionId(),
                record.runId(),
                record.auditId(),
                record.toolCode(),
                record.targetService(),
                record.targetEndpoint(),
                record.tenantId(),
                record.projectId(),
                record.workspaceId(),
                record.actorId(),
                record.traceId(),
                record.payloadReference(),
                record.status().name(),
                record.attemptCount(),
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
