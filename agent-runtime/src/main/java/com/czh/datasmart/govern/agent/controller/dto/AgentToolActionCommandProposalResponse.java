/**
 * @Author : Cui
 * @Date: 2026/06/07 14:48
 * @Description DataSmart Govern Backend - AgentToolActionCommandProposalResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 工具动作 command proposal 响应。
 *
 * <p>该响应是“正式 outbox 写入前的低敏预备结果”。如果 outboxWriteAllowedByPreflight=true，表示当前证据已经满足
 * 进入正式 writer 的最低条件；但 writer 仍必须重新校验权限、payloadReference、幂等键、审批事实和 worker 容量。
 * 如果为 false，调用方只能根据 missingEvidence/rejectedEvidence 修复缺口，不能进入 outbox。</p>
 *
 * @param proposalId 控制面生成的稳定 proposal ID，用于确认页、审计台和后续 writer 关联。
 * @param proposalState proposal 状态，例如 READY_FOR_OUTBOX_CONFIRMATION、WAITING_REQUIRED_EVIDENCE、BLOCKED_BY_GRAPH_STATE。
 * @param outboxWriteAllowedByPreflight 是否允许进入正式 outbox writer 的最低条件。注意本接口本身不会写 outbox。
 * @param graphId 来源执行图 ID。
 * @param contractId 来源 contract ID。
 * @param sourceEventIdentityKey 来源 runtime event 幂等键。
 * @param sourceReplaySequence 来源 replay 游标。
 * @param tenantId 租户 ID。
 * @param projectId 项目 ID。
 * @param actorId 主体 ID。
 * @param requestId 请求 ID。
 * @param runId Agent run ID。
 * @param sessionId Agent session ID。
 * @param proposedAt proposal 生成时间。
 * @param toolName 工具名，不包含参数。
 * @param commandType 建议的 outbox command 类型。NONE 表示不应进入 outbox。
 * @param commandSchemaVersion 建议或请求的命令 schema 版本。
 * @param idempotencyKey 从 contract/graph 推导出的幂等键。
 * @param payloadReference 受控载荷引用。
 * @param payloadPolicy payload 安全策略。
 * @param workerReceiptRequired 是否要求 worker receipt。
 * @param workerReceiptMode worker 回执模式。
 * @param graphState 来源执行图状态。
 * @param terminalState 来源执行图终态/等待态。
 * @param acceptedEvidence 已接受的低敏证据。
 * @param missingEvidence 当前仍缺失的证据。
 * @param rejectedEvidence 被拒绝或需要重新生成的证据。
 * @param guardrailNotes 防护说明，面向学习、前端确认页和运维排障。
 * @param summaryReasons proposal 结果解释。
 * @param recommendedActions 下一步建议。
 */
public record AgentToolActionCommandProposalResponse(
        String proposalId,
        String proposalState,
        Boolean outboxWriteAllowedByPreflight,
        String graphId,
        String contractId,
        String sourceEventIdentityKey,
        Long sourceReplaySequence,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        Instant proposedAt,
        String toolName,
        String commandType,
        String commandSchemaVersion,
        String idempotencyKey,
        String payloadReference,
        String payloadPolicy,
        Boolean workerReceiptRequired,
        String workerReceiptMode,
        String graphState,
        String terminalState,
        List<String> acceptedEvidence,
        List<String> missingEvidence,
        List<String> rejectedEvidence,
        List<String> guardrailNotes,
        List<String> summaryReasons,
        List<String> recommendedActions
) {
}
