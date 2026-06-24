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
     * 构造重新入队记录。
     *
     * <p>人工 requeue 代表“外部配置、权限、目标服务或 payload 契约已经修复，可以允许 dispatcher 重新尝试”。
     * 这里不会清空 attemptCount，因为尝试次数是判断该 command 健康度的重要证据；如果一条命令多次被人工重排后仍失败，
     * 运维台应把它升级为更高风险的死信或人工介入事项。</p>
     */
    public AgentAsyncTaskCommandOutboxRecord markRequeued(String reason, Instant now, Instant nextRetryAt) {
        return withStatus(AgentAsyncTaskCommandOutboxStatus.PENDING, attemptCount, now, nextRetryAt, null, reason);
    }

    /**
     * 构造死信记录。
     *
     * <p>进入 DEAD_LETTER 后，dispatcher 不会再自动领取它。死信不是“成功结束”，而是把自动恢复与人工治理边界切开：
     * 系统保留 commandId、幂等键、治理范围和最近原因，管理员后续可以 dry-run 判断是否重排或忽略。</p>
     */
    public AgentAsyncTaskCommandOutboxRecord markDeadLetter(String reason, Instant now) {
        return withStatus(AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER, attemptCount, now, null, publishedAt, reason);
    }

    /**
     * 构造人工忽略记录。
     *
     * <p>IGNORED 表示管理员确认这条 command 不再需要投递或补偿。它不会修改 publishedAt，
     * 也不会伪装成 PUBLISHED，避免把“已忽略”误当成下游已经收到命令。</p>
     */
    public AgentAsyncTaskCommandOutboxRecord markIgnored(String reason, Instant now) {
        return withStatus(AgentAsyncTaskCommandOutboxStatus.IGNORED, attemptCount, now, null, publishedAt, reason);
    }

    /**
     * 追加人工备注。
     *
     * <p>当前 command outbox 还没有独立 operation_audit 表，因此先把最近一次人工备注写入 lastError。
     * 后续如果接入审计表，应把这里的 reason 作为一条独立操作历史，而不是覆盖最近摘要。</p>
     */
    public AgentAsyncTaskCommandOutboxRecord appendOperationNote(String reason, Instant now) {
        return withStatus(status, attemptCount, now, nextRetryAt, publishedAt, reason);
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
