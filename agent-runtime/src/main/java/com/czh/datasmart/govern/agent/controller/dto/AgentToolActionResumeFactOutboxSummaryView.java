/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactOutboxSummaryView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;

import java.time.Instant;

/**
 * 恢复事实包中的 command outbox 低敏摘要。
 *
 * <p>该视图和通用 outbox 诊断视图相比更保守：
 * 1. 不返回 payloadJson，因为它是服务间命令体；
 * 2. 不返回 payloadReference 字符串，避免把对象存储 key、内部引用或业务定位符扩散给 Python/前端；
 * 3. 不返回 targetEndpoint，避免泄露内部服务路由；
 * 4. 不返回 lastError 原文，避免异常中带出 SQL、URL、token 或下游响应片段。</p>
 */
public record AgentToolActionResumeFactOutboxSummaryView(
        String outboxId,
        String commandId,
        String commandType,
        String commandTopic,
        String consumerService,
        String sessionId,
        String runId,
        String auditId,
        String toolCode,
        String targetService,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String actorId,
        String status,
        Integer attemptCount,
        Boolean payloadReferencePresent,
        Boolean payloadTruncated,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRetryAt,
        Instant publishedAt
) {

    /**
     * 从内部 outbox record 转换为低敏摘要。
     *
     * <p>转换时只保留控制面状态和低敏定位字段，专门避开 payloadJson/payloadReference/targetEndpoint/lastError。</p>
     */
    public static AgentToolActionResumeFactOutboxSummaryView from(AgentAsyncTaskCommandOutboxRecord record) {
        return new AgentToolActionResumeFactOutboxSummaryView(
                record.outboxId(),
                record.commandId(),
                record.commandType(),
                record.commandTopic(),
                record.consumerService(),
                record.sessionId(),
                record.runId(),
                record.auditId(),
                record.toolCode(),
                record.targetService(),
                record.tenantId(),
                record.projectId(),
                record.workspaceId(),
                record.actorId(),
                record.status().name(),
                record.attemptCount(),
                record.payloadReference() != null && !record.payloadReference().isBlank(),
                record.payloadTruncated(),
                record.createdAt(),
                record.updatedAt(),
                record.nextRetryAt(),
                record.publishedAt()
        );
    }
}
