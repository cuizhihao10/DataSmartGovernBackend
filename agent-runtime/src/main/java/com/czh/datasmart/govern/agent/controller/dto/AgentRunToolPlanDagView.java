/**
 * @Author : Cui
 * @Date: 2026/05/31 22:22
 * @Description DataSmart Govern Backend - AgentRunToolPlanDagView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Run 级 ToolPlan DAG 预检视图。
 *
 * <p>该视图是只读编排图，不会审批、不会执行工具、不会投递 Kafka。
 * 它的产品目标是先让 Java 控制面能够解释“这些工具之间有什么依赖、哪些可以并行、失败后如何处理、结果按什么顺序回填”，
 * 为后续真正的多工具调度器、异步 worker、二轮模型推理和人工补偿台提供稳定契约。</p>
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param dependencyMode 依赖解析模式：EXPLICIT 表示使用 Python/治理提示传入的依赖；LEGACY_SEQUENCE 表示兼容旧线性顺序。
 * @param totalNodes 节点数量。
 * @param totalEdges 依赖边数量。
 * @param readyNodeCount 当前依赖和策略均满足的节点数量。
 * @param blockedNodeCount 当前被前置依赖、审批、参数或策略阻断的节点数量。
 * @param hasCycle 是否检测到依赖环。
 * @param cycleNodeIds 参与依赖环的节点 ID 列表。
 * @param topologicalNodeIds 拓扑排序结果；有环时只返回可安全排序的部分节点。
 * @param readyNodeIds 当前可进入执行候选的节点 ID。
 * @param blockedNodeIds 当前被阻断的节点 ID。
 * @param summaryReasons Run 级解释说明。
 * @param recommendedActions 推荐下一步动作。
 * @param nodes 节点明细。
 * @param edges 依赖边明细。
 */
public record AgentRunToolPlanDagView(
        String sessionId,
        String runId,
        String dependencyMode,
        Integer totalNodes,
        Integer totalEdges,
        Integer readyNodeCount,
        Integer blockedNodeCount,
        Boolean hasCycle,
        List<String> cycleNodeIds,
        List<String> topologicalNodeIds,
        List<String> readyNodeIds,
        List<String> blockedNodeIds,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentToolPlanDagNodeView> nodes,
        List<AgentToolPlanDagEdgeView> edges
) {
}
