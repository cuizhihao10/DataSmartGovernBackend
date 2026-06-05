/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Master Agent 交接 DAG 视图。
 *
 * <p>该视图把 Python Runtime 产生的多 Agent 会话调度事件，转换成 Java 控制面更容易理解的
 * Master -> Specialist -> Guardrail/Approval -> Tool Control Plane -> Feedback -> Second Turn 图。
 * 它回答的是“下一步协作路径应该如何被治理和解释”，而不是“某个 Agent 是否已经真实执行完成”。</p>
 *
 * <p>为什么先做只读 DAG：真实商业化 Agent 平台中，handoff 涉及权限、审批、工具副作用、租户隔离、
 * 事件回放、失败恢复和人工接管。如果一开始就把 DAG 和执行器写死，后续接入 MCP、A2A、LangGraph、
 * OpenClaw/NemoClaw 或自研 worker 时会反复重构。先沉淀控制面图契约，可以让前端、审计、审批和执行器
 * 共用同一份解释。</p>
 *
 * @param identityKey 源 runtime event 投影的幂等键，用于排查重复消费。
 * @param runId Agent run ID。
 * @param sessionId Agent session ID。
 * @param replaySequence Java 控制面回放游标。
 * @param createdAt 源事件创建时间。
 * @param schedulingStatus 源会话调度状态。
 * @param dagExecutionState DAG 可执行状态解释，常见值 READY、DEGRADED、APPROVAL_REQUIRED、BLOCKED。
 * @param executable 当前 DAG 是否允许进入真实执行候选。只要降级、阻塞或需要审批，第一版均保守返回 false。
 * @param handoffRequired 是否存在需要人工/权限/专家接管的 handoff。
 * @param totalNodes 节点数量。
 * @param totalEdges 边数量。
 * @param summaryReasons DAG 级中文解释，面向学习、排障和审计。
 * @param recommendedActions DAG 级下一步建议。
 * @param nodes 节点明细。
 * @param edges 边明细。
 */
public record AgentSessionHandoffDagView(
        String identityKey,
        String runId,
        String sessionId,
        Long replaySequence,
        Instant createdAt,
        String schedulingStatus,
        String dagExecutionState,
        Boolean executable,
        Boolean handoffRequired,
        Integer totalNodes,
        Integer totalEdges,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentSessionHandoffDagNodeView> nodes,
        List<AgentSessionHandoffDagEdgeView> edges
) {
}
