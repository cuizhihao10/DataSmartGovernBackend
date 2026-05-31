/**
 * @Author : Cui
 * @Date: 2026/06/01 14:22
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import java.time.Instant;
import java.util.List;

/**
 * DAG selected-node 确认事实记录。
 *
 * <p>这条记录的业务语义是：“某个调用方在某个时刻确认了某个 Agent Run 的某版 dry-run 预案，
 * 并且服务端据此把一批已校验的异步工具审计项写入 command outbox”。它与 command outbox 的职责不同：
 * outbox 负责可靠投递和重试；confirmation 负责解释用户确认、预案指纹和下游 command 之间的证据链。</p>
 *
 * <p>记录里只保存节点 ID、auditId、outboxId、commandId 和治理上下文，不保存原始工具参数、SQL、prompt、
 * 文件内容或样本数据。这样做能满足审计追踪，同时避免确认表成为敏感数据扩散通道。</p>
 */
public record AgentRunToolDagConfirmationRecord(
        String confirmationId,
        String sessionId,
        String runId,
        String selectionFingerprint,
        List<String> selectedNodeIds,
        List<String> selectedAuditIds,
        List<String> outboxIds,
        List<String> commandIds,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String actorId,
        String traceId,
        Boolean confirmed,
        AgentRunToolDagConfirmationStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public AgentRunToolDagConfirmationRecord {
        confirmationId = requireText(confirmationId, "confirmationId");
        sessionId = requireText(sessionId, "sessionId");
        runId = requireText(runId, "runId");
        selectionFingerprint = requireText(selectionFingerprint, "selectionFingerprint");
        selectedNodeIds = selectedNodeIds == null ? List.of() : List.copyOf(selectedNodeIds);
        selectedAuditIds = selectedAuditIds == null ? List.of() : List.copyOf(selectedAuditIds);
        outboxIds = outboxIds == null ? List.of() : List.copyOf(outboxIds);
        commandIds = commandIds == null ? List.of() : List.copyOf(commandIds);
        confirmed = confirmed == null ? Boolean.TRUE : confirmed;
        status = status == null ? AgentRunToolDagConfirmationStatus.CONFIRMED : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("DAG selected-node 确认记录缺少必填字段: " + fieldName);
        }
        return value.trim();
    }
}
