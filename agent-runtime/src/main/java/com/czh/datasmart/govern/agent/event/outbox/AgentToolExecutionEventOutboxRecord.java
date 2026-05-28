/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.event.AgentToolExecutionStateChangedEvent;

import java.time.Instant;

/**
 * Agent 工具执行事件 outbox 记录。
 *
 * <p>这条记录表达的是“某个工具状态事件等待被可靠投递”的事实，而不是工具审计记录本身。
 * 因此它保留 eventId、partitionKey、runId、auditId、payloadJson、状态、重试次数和错误信息，
 * 让后续 dispatcher 能够按同一条记录完成领取、投递、失败重试、死信和人工补偿。</p>
 *
 * <p>当前 payloadJson 来自 {@link AgentToolExecutionStateChangedEvent}，该事件本身已经做过安全收口：
 * 不携带完整 planArguments、审批备注原文和工具完整输出。outbox 仍保留 payload 大小和截断标记，
 * 是为了给未来扩展字段时提供第二道安全护栏。</p>
 */
public record AgentToolExecutionEventOutboxRecord(
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
        AgentToolExecutionEventOutboxStatus status,
        int attemptCount,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt,
        Instant nextRetryAt,
        Instant publishedAt,
        String lastError,
        int payloadSizeBytes,
        boolean payloadTruncated,
        String payloadJson
) {

    public AgentToolExecutionEventOutboxRecord {
        outboxId = requireText(outboxId, "outboxId");
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        source = requireText(source, "source");
        status = status == null ? AgentToolExecutionEventOutboxStatus.PENDING : status;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        lastError = lastError == null ? "" : lastError;
        payloadJson = payloadJson == null ? "" : payloadJson;
    }

    /**
     * 创建一条待投递 outbox 记录。
     *
     * <p>outboxId 使用 eventId 派生，而不是重新生成随机 ID。这样同一条状态事件重复进入 outbox 时可以天然去重，
     * 后续数据库实现也能用 outboxId/eventId 建唯一索引，避免 Kafka 重试或服务重启造成重复投递。</p>
     */
    public static AgentToolExecutionEventOutboxRecord pending(AgentToolExecutionStateChangedEvent event,
                                                              String payloadJson,
                                                              int payloadSizeBytes,
                                                              Instant now) {
        return new AgentToolExecutionEventOutboxRecord(
                "outbox:" + event.eventId(),
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.source(),
                event.partitionKey(),
                event.tenantId(),
                event.projectId(),
                event.workspaceId(),
                event.actorId(),
                event.sessionId(),
                event.runId(),
                event.auditId(),
                event.toolCode(),
                event.currentState(),
                AgentToolExecutionEventOutboxStatus.PENDING,
                0,
                event.occurredAt(),
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
     * 创建一条被安全策略阻断的 outbox 记录。
     *
     * <p>例如 payload 超过上限时，我们仍然保留事件索引、runId、auditId 和状态，便于运维看到“确实发生过一条事件”，
     * 但不会把超大 payload 当作可投递消息继续推送到下游。</p>
     */
    public static AgentToolExecutionEventOutboxRecord blocked(AgentToolExecutionStateChangedEvent event,
                                                              String safePayloadPreview,
                                                              int payloadSizeBytes,
                                                              String reason,
                                                              Instant now) {
        return new AgentToolExecutionEventOutboxRecord(
                "outbox:" + event.eventId(),
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.source(),
                event.partitionKey(),
                event.tenantId(),
                event.projectId(),
                event.workspaceId(),
                event.actorId(),
                event.sessionId(),
                event.runId(),
                event.auditId(),
                event.toolCode(),
                event.currentState(),
                AgentToolExecutionEventOutboxStatus.BLOCKED,
                0,
                event.occurredAt(),
                now,
                now,
                null,
                null,
                reason,
                payloadSizeBytes,
                true,
                safePayloadPreview
        );
    }

    /**
     * 标记事件已被 dispatcher 领取并准备投递。
     *
     * <p>当前内存版还没有后台 dispatcher，但这个状态转换方法先放在领域对象上，后续持久化实现可以直接复用同一套状态语义。</p>
     */
    public AgentToolExecutionEventOutboxRecord markPublishing(Instant now) {
        return withStatus(AgentToolExecutionEventOutboxStatus.PUBLISHING, attemptCount + 1, now, null, null, lastError);
    }

    /**
     * 标记事件已经成功投递到下游。
     */
    public AgentToolExecutionEventOutboxRecord markPublished(Instant now) {
        return withStatus(AgentToolExecutionEventOutboxStatus.PUBLISHED, attemptCount, now, null, now, "");
    }

    /**
     * 标记事件投递失败，等待下一次重试。
     */
    public AgentToolExecutionEventOutboxRecord markFailed(String error, Instant now, Instant nextRetryAt) {
        return withStatus(AgentToolExecutionEventOutboxStatus.FAILED, attemptCount, now, nextRetryAt, publishedAt, error);
    }

    /**
     * 标记事件被阻断，等待人工修复或后续补偿入口处理。
     *
     * <p>BLOCKED 是 outbox 的“保护性终态”。它不代表业务工具执行失败，而是代表事件投递链路已经判断继续自动重试不安全。
     * 真实商用系统里这类记录通常会进入运维台、告警中心或人工补偿队列，避免同一条坏消息无休止地占用 dispatcher 资源。</p>
     */
    public AgentToolExecutionEventOutboxRecord markBlocked(String error, Instant now) {
        return withStatus(AgentToolExecutionEventOutboxStatus.BLOCKED, attemptCount, now, null, publishedAt, error);
    }

    /**
     * 人工重新入队。
     *
     * <p>该动作通常由运维台或平台管理员触发，用于把 BLOCKED/FAILED 事件重新放回 dispatcher 自动投递队列。
     * 重新入队不会清零 attemptCount，因为历史尝试次数是重要诊断信息；如果某条事件反复被重新入队又再次 BLOCKED，
     * 运维侧就能判断它不是偶发网络问题，而更可能是 payload 契约、权限上下文或下游配置问题。</p>
     */
    public AgentToolExecutionEventOutboxRecord markRequeued(String reason, Instant now) {
        return withStatus(AgentToolExecutionEventOutboxStatus.PENDING, attemptCount, now, null, publishedAt, reason);
    }

    /**
     * 人工忽略并归档。
     *
     * <p>IGNORED 是 outbox 的人工终止状态。它不能等同于 PUBLISHED，因为事件并没有真正送达下游；
     * 但它也不应继续留在 BLOCKED/FAILED 队列里制造告警噪声。真实商业化系统中，这类动作必须经过权限控制和审计留痕。</p>
     */
    public AgentToolExecutionEventOutboxRecord markIgnored(String reason, Instant now) {
        return withStatus(AgentToolExecutionEventOutboxStatus.IGNORED, attemptCount, now, null, publishedAt, reason);
    }

    /**
     * 追加人工处理备注。
     *
     * <p>当前阶段还没有独立 outbox_operation_audit 表，因此先把最近一次处理说明写入 lastError。
     * 这不是最终审计方案，但可以让运维查询页立刻看到“谁在什么时候做了什么判断”。后续补审计表时，
     * 该方法可以保留为记录最近摘要，完整历史则落入独立表。</p>
     */
    public AgentToolExecutionEventOutboxRecord appendOperationNote(String note, Instant now) {
        return withStatus(status, attemptCount, now, nextRetryAt, publishedAt, note);
    }

    private AgentToolExecutionEventOutboxRecord withStatus(AgentToolExecutionEventOutboxStatus newStatus,
                                                          int newAttemptCount,
                                                          Instant now,
                                                          Instant newNextRetryAt,
                                                          Instant newPublishedAt,
                                                          String newLastError) {
        return new AgentToolExecutionEventOutboxRecord(
                outboxId,
                eventId,
                eventType,
                schemaVersion,
                source,
                partitionKey,
                tenantId,
                projectId,
                workspaceId,
                actorId,
                sessionId,
                runId,
                auditId,
                toolCode,
                currentState,
                newStatus,
                Math.max(0, newAttemptCount),
                occurredAt,
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
            throw new IllegalArgumentException("outbox 记录缺少必填字段: " + fieldName);
        }
        return value;
    }
}
