/**
 * @Author : Cui
 * @Date: 2026/05/31 17:08
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import java.time.Instant;

/**
 * Agent ASYNC_TASK 命令 outbox 记录。
 *
 * <p>这条记录表达的是“某个异步工具下发命令等待被可靠投递”的事实。
 * 与工具状态事件不同，命令会触发下游 task-management 创建任务，因此必须保留 commandId、idempotencyKey、
 * payloadJson、投递状态、重试次数和错误信息，支撑失败补偿、人工重放和死信治理。</p>
 */
public record AgentAsyncTaskCommandOutboxRecord(
        String outboxId,
        String commandId,
        String idempotencyKey,
        String schemaVersion,
        String commandType,
        String partitionKey,
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
        AgentAsyncTaskCommandOutboxStatus status,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRetryAt,
        Instant publishedAt,
        String lastError,
        int payloadSizeBytes,
        boolean payloadTruncated,
        String payloadJson
) {

    public AgentAsyncTaskCommandOutboxRecord {
        outboxId = requireText(outboxId, "outboxId");
        commandId = requireText(commandId, "commandId");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        commandType = requireText(commandType, "commandType");
        status = status == null ? AgentAsyncTaskCommandOutboxStatus.PENDING : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        lastError = lastError == null ? "" : lastError;
        payloadJson = payloadJson == null ? "" : payloadJson;
    }

    /**
     * 构造待投递记录。
     */
    public static AgentAsyncTaskCommandOutboxRecord pending(String commandId,
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
                                                            String payloadJson,
                                                            int payloadSizeBytes,
                                                            Instant now) {
        return new AgentAsyncTaskCommandOutboxRecord(
                "async-command-outbox:" + commandId,
                commandId,
                idempotencyKey,
                schemaVersion,
                commandType,
                runId,
                commandTopic,
                consumerService,
                sessionId,
                runId,
                auditId,
                toolCode,
                targetService,
                targetEndpoint,
                tenantId,
                projectId,
                workspaceId,
                actorId,
                traceId,
                payloadReference,
                AgentAsyncTaskCommandOutboxStatus.PENDING,
                0,
                now,
                now,
                null,
                null,
                "",
                payloadSizeBytes,
                false,
                payloadJson
        );
    }

    /**
     * 构造被安全策略阻断的记录。
     */
    public AgentAsyncTaskCommandOutboxRecord markBlocked(String error, Instant now) {
        return withStatus(AgentAsyncTaskCommandOutboxStatus.BLOCKED, attemptCount, now, null, publishedAt, error);
    }

    /**
     * 标记被 dispatcher 领取。
     */
    public AgentAsyncTaskCommandOutboxRecord markPublishing(Instant now) {
        return withStatus(AgentAsyncTaskCommandOutboxStatus.PUBLISHING, attemptCount + 1, now, null, publishedAt, lastError);
    }

    /**
     * 标记投递成功。
     */
    public AgentAsyncTaskCommandOutboxRecord markPublished(Instant now) {
        return withStatus(AgentAsyncTaskCommandOutboxStatus.PUBLISHED, attemptCount, now, null, now, "");
    }

    /**
     * 标记投递失败并等待重试。
     */
    public AgentAsyncTaskCommandOutboxRecord markFailed(String error, Instant now, Instant nextRetryAt) {
        return withStatus(AgentAsyncTaskCommandOutboxStatus.FAILED, attemptCount, now, nextRetryAt, publishedAt, error);
    }

    private AgentAsyncTaskCommandOutboxRecord withStatus(AgentAsyncTaskCommandOutboxStatus newStatus,
                                                        int newAttemptCount,
                                                        Instant now,
                                                        Instant newNextRetryAt,
                                                        Instant newPublishedAt,
                                                        String newLastError) {
        return new AgentAsyncTaskCommandOutboxRecord(
                outboxId,
                commandId,
                idempotencyKey,
                schemaVersion,
                commandType,
                partitionKey,
                commandTopic,
                consumerService,
                sessionId,
                runId,
                auditId,
                toolCode,
                targetService,
                targetEndpoint,
                tenantId,
                projectId,
                workspaceId,
                actorId,
                traceId,
                payloadReference,
                newStatus,
                Math.max(0, newAttemptCount),
                createdAt,
                now,
                newNextRetryAt,
                newPublishedAt,
                newLastError,
                payloadSizeBytes,
                payloadTruncated,
                payloadJson
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("异步命令 outbox 缺少必填字段: " + fieldName);
        }
        return value.trim();
    }
}
