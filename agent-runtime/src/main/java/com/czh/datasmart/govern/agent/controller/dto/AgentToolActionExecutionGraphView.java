/**
 * @Author : Cui
 * @Date: 2026/06/07 14:27
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单个工具动作的执行图预览。
 *
 * <p>本视图把 5.50 的 durable action contract 进一步组织成图结构：入口节点、准备度节点、人工审批或澄清节点、
 * 契约节点、outbox 节点和 worker receipt 节点。这样前端和后续执行器不需要从自由文本建议中猜测下一步，而是可以
 * 按节点状态和边条件理解真实副作用前的完整治理路径。</p>
 *
 * <p>该图仍然是只读预览。它不会写 outbox、不会创建审批、不会读取 payloadReference，也不会调用 worker。
 * 真实执行必须由单独的确认 API 或 outbox command builder 完成，并再次校验权限、幂等、载荷引用和容量策略。</p>
 *
 * @param graphId 控制面生成的稳定图 ID，通常由 contractId 派生。
 * @param contractId 来源 durable action contract ID。
 * @param sourceEventIdentityKey 来源 runtime event 幂等键。
 * @param sourceReplaySequence 来源 runtime event replay 游标。
 * @param tenantId 租户 ID，用于多租户隔离。
 * @param projectId 项目 ID，用于项目级数据范围收口。
 * @param actorId 触发工具动作意图的主体 ID。
 * @param requestId 请求追踪 ID。
 * @param runId Agent run ID。
 * @param sessionId Agent session ID。
 * @param eventCreatedAt 来源事件创建时间。
 * @param protocolFamily 协议族，例如 MCP、A2A 或未来扩展协议。
 * @param intakeSource 工具动作入口来源，例如 MCP_TOOLS_CALL。
 * @param toolName 工具注册名，不包含工具参数。
 * @param graphState 整张图的当前治理状态。
 * @param terminalState 如果当前不能继续，说明图停在哪类终态或等待态。
 * @param outboxWritableNow 当前是否满足“可以由专用确认 API 写入 outbox”的最低条件。
 * @param nodeCount 节点数量。
 * @param edgeCount 边数量。
 * @param requiredEvidence 从入口事实推进到真实执行前必须具备的证据列表。
 * @param missingRequirements 当前仍缺失的生产级要求。
 * @param payloadPolicy payload 安全策略，强调只能使用受控引用，不能恢复原始参数。
 * @param summaryReasons 图级解释，帮助学习和排障。
 * @param recommendedActions 下一步建议，通常面向确认页、审批台、outbox builder 或协议适配层。
 * @param nodes 图节点列表。
 * @param edges 图有向边列表。
 */
public record AgentToolActionExecutionGraphView(
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
        Instant eventCreatedAt,
        String protocolFamily,
        String intakeSource,
        String toolName,
        String graphState,
        String terminalState,
        Boolean outboxWritableNow,
        Integer nodeCount,
        Integer edgeCount,
        List<String> requiredEvidence,
        List<String> missingRequirements,
        String payloadPolicy,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentToolActionExecutionGraphNodeView> nodes,
        List<AgentToolActionExecutionGraphEdgeView> edges
) {
}
