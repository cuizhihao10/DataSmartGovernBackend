/**
 * @Author : Cui
 * @Date: 2026/06/01 14:22
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.model.AgentHandoffDagBridgeSourceEvidence;

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
 *
 * <p>{@code policyVersions} 与 {@code delegationEvidence} 是本阶段新增的“授权证据快照”。
 * 它们不参与真实执行调度，也不替代 permission-admin 的最终判定；它们的职责是回答生产审计中经常出现的两个问题：
 * 用户确认入箱时看到的是哪一版授权策略？服务账号代表用户执行的委托证据是什么？
 * 后续如果确认记录落 MySQL，这两个字段应以 JSON 数组保存，便于审计台展示、检索和跨服务排障。</p>
 */
public record AgentRunToolDagConfirmationRecord(
        String confirmationId,
        String sessionId,
        String runId,
        String selectionFingerprint,
        List<String> selectedNodeIds,
        List<String> selectedAuditIds,
        List<String> policyVersions,
        List<String> delegationEvidence,
        /*
         * Handoff DAG bridge preview 来源证据。
         *
         * 它用于解释“这次 selected-node confirmation 是否来自 handoff DAG 上的 tool-control 桥接预览”。
         * 字段只保存来源摘要，不保存工具参数、SQL、prompt、targetEndpoint 或完整模板；为空表示历史调用方、
         * 直接 Tool DAG dry-run 调用方或尚未接入 handoff bridge 的入口。
         */
        AgentHandoffDagBridgeSourceEvidence bridgeSourceEvidence,
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
        policyVersions = policyVersions == null ? List.of() : List.copyOf(policyVersions);
        delegationEvidence = delegationEvidence == null ? List.of() : List.copyOf(delegationEvidence);
        /*
         * bridgeSourceEvidence 可以为空：历史 Run 级入口、直接从 Tool DAG dry-run 进入 selected-node 的调用方，
         * 都可能没有 handoff DAG bridge preview 来源。为空不影响既有链路；一旦调用方提供，服务层会在保存前完成
         * fingerprint、tool-control 和 auditId 范围校验。
         */
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
