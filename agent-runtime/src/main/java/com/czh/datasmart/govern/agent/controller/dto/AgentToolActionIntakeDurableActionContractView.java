/**
 * @Author : Cui
 * @Date: 2026/06/07 14:04
 * @Description DataSmart Govern Backend - AgentToolActionIntakeDurableActionContractView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 工具动作入口事件推导出的 durable action 契约预览。
 *
 * <p>这个 DTO 不是 outbox 记录，也不是工具执行命令。它的业务角色是连接两段链路：
 * 前一段是 Python `tool_action_intake_recorded` 低敏事件，后一段是 Java async command outbox、
 * task-management worker、worker receipt 和结果脱敏。由于 intake 事件刻意不保存原始 arguments、
 * prompt、SQL 或 payload，本视图只能说明“要进入可恢复执行还缺哪些证据”，不能直接携带执行参数。</p>
 *
 * @param contractId 控制面生成的稳定契约 ID。它由事件幂等键、replaySequence、工具名和决策摘要哈希生成。
 * @param sourceEventIdentityKey 来源 runtime event 的幂等键，用于回查事件事实。
 * @param sourceReplaySequence Java 控制面 replay 游标，用于断点续读和审计定位。
 * @param tenantId 租户 ID，用于多租户隔离。
 * @param projectId 项目 ID，用于 PROJECT 数据范围和审计分区。
 * @param actorId 触发工具动作意图的主体 ID。
 * @param requestId 请求追踪 ID。
 * @param runId Agent run ID。
 * @param sessionId Agent session ID。
 * @param eventCreatedAt 来源事件创建时间。
 * @param protocolFamily 协议族，例如 MCP 或未来 A2A。
 * @param intakeSource 入口来源，例如 MCP_TOOLS_CALL。
 * @param toolName 工具注册名。不包含工具参数。
 * @param readinessDecision readiness 决策摘要，例如 ready_to_execute、waiting_approval。
 * @param durableActionState durable action 预备状态，说明当前能否推进到 outbox 契约。
 * @param executable readiness 是否认为该工具可执行。注意这不代表已经写 outbox。
 * @param queueRequired 是否需要异步队列承接。
 * @param requiresHumanApproval 是否需要人工审批或确认事实。
 * @param parameterIssueCount 参数问题数量，不包含参数值。
 * @param issueCodes 低敏问题码。
 * @param reasonCodes 低敏原因码。
 * @param outboxWritableNow 当前是否具备“立即写 outbox”的最低条件。对 preview-only intake 通常为 false。
 * @param outboxCommandType 如果未来补齐证据，应写入的命令类型建议；NONE 表示当前不应入箱。
 * @param idempotencyKey 建议的低敏幂等键，用于未来 outbox command 去重。
 * @param workerReceiptRequired 是否要求 worker receipt 证明副作用发生。
 * @param requiredEvidence 从入口事实推进到 outbox 前必须具备的证据清单。
 * @param missingRequirements 当前仍缺失的生产级要求。
 * @param payloadPolicy payload 安全策略说明，强调只能使用 payloadReference，不能使用原始参数。
 * @param guardrailNotes 给控制面、前端确认页或运营人员看的低敏防护说明。
 */
public record AgentToolActionIntakeDurableActionContractView(
        String contractId,
        String sourceEventIdentityKey,
        Long sourceReplaySequence,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        Instant eventCreatedAt,
        String protocolFamily,
        String intakeSource,
        String toolName,
        String readinessDecision,
        String durableActionState,
        Boolean executable,
        Boolean queueRequired,
        Boolean requiresHumanApproval,
        Integer parameterIssueCount,
        List<String> issueCodes,
        List<String> reasonCodes,
        Boolean outboxWritableNow,
        String outboxCommandType,
        String idempotencyKey,
        Boolean workerReceiptRequired,
        List<String> requiredEvidence,
        List<String> missingRequirements,
        String payloadPolicy,
        List<String> guardrailNotes
) {
}
