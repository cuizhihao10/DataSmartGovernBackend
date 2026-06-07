/**
 * @Author : Cui
 * @Date: 2026/06/07 13:39
 * @Description DataSmart Govern Backend - AgentToolActionIntakeProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工具动作意图入口 runtime event 的 Java 控制面投影视图。
 *
 * <p>Python Runtime 5.48 会把 MCP `tools/call` preview 的结果写成 `tool_action_intake_recorded`
 * 事件。Java agent-runtime 的职责不是重新解析 MCP JSON-RPC，也不是读取工具参数，而是把该事件里的低敏
 * 控制面事实转换成稳定 DTO，供管理台、审计台、WebSocket replay 和后续 Java timeline 使用。</p>
 *
 * <p>安全边界非常重要：本视图只暴露协议、preview 边界、accepted/rejected 数量、issue/reason code、
 * readiness 计数、graph 分支和 durable action 边界。它不会返回 `arguments`、SQL、prompt、样本数据、
 * 模型输出、凭证、内部 endpoint 或 artifact 正文，避免 Java 投影成为第二份工具上下文缓存。</p>
 *
 * @param identityKey 事件幂等键，用于控制面去重和问题排查。
 * @param recordSchemaVersion Java runtime event 投影记录自身的 schema 版本。
 * @param recordSource 事件来源服务，例如 python-ai-runtime。
 * @param eventType 固定为 tool_action_intake_recorded。
 * @param severity 事件严重级别，通常是 audit、info 或 warning。
 * @param tenantId 租户 ID，用于多租户隔离。
 * @param projectId 项目 ID，用于 PROJECT 数据范围收口。
 * @param actorId 触发本次动作意图的用户、服务账号或 Agent 主体。
 * @param requestId HTTP/API 请求追踪 ID。
 * @param runId Agent run 维度标识。
 * @param sessionId Agent session 维度标识。
 * @param sequence 生产方局部序号。
 * @param replaySequence Java 控制面写入投影时分配的稳定 replay 游标。
 * @param createdAt 事件创建时间。
 * @param consumedAt Java 控制面消费或写入投影的时间。
 * @param eventPayloadVersion Python 事件 payload 版本。
 * @param snapshotType 业务快照类型，默认 TOOL_ACTION_INTAKE。
 * @param payloadPolicy payload 脱敏策略说明。
 * @param previewSchemaVersion MCP intake preview 响应 schema 版本。
 * @param protocolFamily 协议族，例如 MCP、A2A 或 FUTURE_AGENT_PROTOCOL。
 * @param previewOnly 是否只是预检，不代表真实执行。
 * @param toolExecutionEnabled 当前入口是否允许直接执行工具，本阶段通常为 false。
 * @param jsonRpcDetected 是否检测到 JSON-RPC 包装。
 * @param methodAccepted JSON-RPC method 是否被协议适配层接受。
 * @param callDetected 是否识别到了工具调用意图。
 * @param sensitiveFieldIgnoredCount Python 侧明确忽略的敏感字段数量。
 * @param intakeSource 入口来源，例如 MCP_TOOLS_CALL。
 * @param totalCount 本次入口识别到的工具动作意图总数。
 * @param acceptedToolPlanCount 被转换为 ToolPlan 并进入 readiness 的数量。
 * @param rejectedBeforeReadinessCount 在 readiness 前被拒绝的数量。
 * @param boundaryCounts intake 边界计数。
 * @param issueCodes 治理问题码列表。
 * @param blockingIssueCount 阻断型问题数量。
 * @param toolNames 工具注册名列表，不包含工具参数。
 * @param toolNamesTruncatedCount 工具名列表被裁剪的数量。
 * @param readinessTotalCount 进入 readiness 的工具计划数量。
 * @param readinessExecutableCount 可进入受控执行的数量。
 * @param readinessApprovalRequiredCount 需要人工审批的数量。
 * @param readinessClarificationRequiredCount 需要用户或上游 Agent 澄清的数量。
 * @param readinessDraftOnlyCount 只生成草稿、不允许执行的数量。
 * @param readinessQueuedAsyncCount 需要异步队列承接的数量。
 * @param readinessThrottledCount 因预算、并发或 backlog 被限流的数量。
 * @param readinessBlockedCount 被安全、权限或策略阻断的数量。
 * @param readinessHasBlockingDecision 是否存在阻断型 readiness 决策。
 * @param readinessNextActions 低敏下一步动作建议。
 * @param readinessReasonCodes readiness 原因码。
 * @param graphExecutionBoundary readiness graph 的执行边界说明。
 * @param graphNodeCount 图节点数量。
 * @param graphEdgeCount 图边数量。
 * @param graphBranches 命中的图分支。
 * @param graphBranchCounts 图分支计数。
 * @param graphToolExecuted 图评估阶段是否执行了工具，生产边界上应为 false。
 * @param graphOutboxWritten 图评估阶段是否写入 outbox，预检阶段应为 false。
 * @param graphApprovalCreated 图评估阶段是否创建审批，预检阶段应为 false。
 * @param graphWorkerReceiptRequiredForSideEffects 是否要求 worker receipt 才能证明副作用发生。
 * @param productionReadyForExecution 当前链路是否具备真实生产执行条件。
 * @param missingProductionRequirements 尚缺失的生产级要求，例如 outbox、审批或 worker receipt。
 * @param decisionSummaries 低敏工具决策摘要，不包含 arguments/payload/SQL/prompt。
 */
public record AgentToolActionIntakeProjectionView(
        String identityKey,
        String recordSchemaVersion,
        String recordSource,
        String eventType,
        String severity,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        Long sequence,
        Long replaySequence,
        Instant createdAt,
        Instant consumedAt,
        String eventPayloadVersion,
        String snapshotType,
        String payloadPolicy,
        String previewSchemaVersion,
        String protocolFamily,
        Boolean previewOnly,
        Boolean toolExecutionEnabled,
        Boolean jsonRpcDetected,
        Boolean methodAccepted,
        Boolean callDetected,
        Integer sensitiveFieldIgnoredCount,
        String intakeSource,
        Integer totalCount,
        Integer acceptedToolPlanCount,
        Integer rejectedBeforeReadinessCount,
        Map<String, Integer> boundaryCounts,
        List<String> issueCodes,
        Integer blockingIssueCount,
        List<String> toolNames,
        Integer toolNamesTruncatedCount,
        Integer readinessTotalCount,
        Integer readinessExecutableCount,
        Integer readinessApprovalRequiredCount,
        Integer readinessClarificationRequiredCount,
        Integer readinessDraftOnlyCount,
        Integer readinessQueuedAsyncCount,
        Integer readinessThrottledCount,
        Integer readinessBlockedCount,
        Boolean readinessHasBlockingDecision,
        List<String> readinessNextActions,
        List<String> readinessReasonCodes,
        String graphExecutionBoundary,
        Integer graphNodeCount,
        Integer graphEdgeCount,
        List<String> graphBranches,
        Map<String, Integer> graphBranchCounts,
        Boolean graphToolExecuted,
        Boolean graphOutboxWritten,
        Boolean graphApprovalCreated,
        Boolean graphWorkerReceiptRequiredForSideEffects,
        Boolean productionReadyForExecution,
        List<String> missingProductionRequirements,
        List<AgentToolActionIntakeDecisionSummaryView> decisionSummaries
) {
}
