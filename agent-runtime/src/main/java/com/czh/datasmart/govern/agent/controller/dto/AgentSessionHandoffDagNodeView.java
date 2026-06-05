/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagNodeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Master Agent 交接 DAG 的节点视图。
 *
 * <p>这里的“节点”不是线程、进程或真实远程 Agent 实例，而是 Java 控制面对一次多 Agent 会话的
 * 协作阶段解释。这样设计是为了先稳定产品语义：管理台、审计台和后续执行器都能看到同一张图，
 * 再逐步把其中某些节点接入真实的 Agent worker、MCP/A2A adapter、审批流或工具执行队列。</p>
 *
 * <p>安全边界：节点只携带角色、阶段、工具名或 Skill code 等低敏治理事实，不携带 prompt、SQL、
 * 工具参数、样本数据、模型输出、长期记忆正文或用户原始业务目标。</p>
 *
 * @param nodeId 控制面生成的 DAG 节点 ID，单张图内唯一，用于边引用和前端渲染。
 * @param nodeType 节点类型，例如 MASTER、SPECIALIST、GUARDRAIL、MEMORY、TOOL_CONTROL、FEEDBACK。
 * @param stage 节点所处协作阶段，例如 SESSION_INTAKE、SPECIALIST_ANALYSIS、TOOL_GOVERNANCE、SECOND_TURN。
 * @param agentRole 关联的 Agent 角色。非 Agent 节点可为空或使用平台角色名，例如 TOOL_CONTROL_PLANE。
 * @param status 节点状态，继承或解释会话调度状态，常见值为 READY、DEGRADED、APPROVAL_REQUIRED、BLOCKED。
 * @param required 该节点是否是本轮 handoff DAG 的必要步骤。可选节点后续可用于推荐增强能力。
 * @param executable 当前节点是否可进入后续真实执行候选。只读 DAG 不会直接执行，只表达可执行性判断。
 * @param blockedByNodeIds 阻塞当前节点的前置节点 ID 列表。
 * @param evidenceRefs 支撑该节点存在的低敏证据，例如 skill:xxx、tool:xxx、memory:episodic。
 * @param reasons 给学习和排障使用的设计解释，说明为什么需要该节点以及为什么当前状态如此。
 * @param recommendedActions 给运营、审核或后续执行器的下一步建议。
 */
public record AgentSessionHandoffDagNodeView(
        String nodeId,
        String nodeType,
        String stage,
        String agentRole,
        String status,
        Boolean required,
        Boolean executable,
        List<String> blockedByNodeIds,
        List<String> evidenceRefs,
        List<String> reasons,
        List<String> recommendedActions
) {
}
