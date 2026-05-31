/**
 * @Author : Cui
 * @Date: 2026/05/31 22:21
 * @Description DataSmart Govern Backend - AgentToolPlanDagNodeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent ToolPlan DAG 节点视图。
 *
 * <p>一个节点对应一次工具审计记录，而不是直接对应一次下游 HTTP 调用。
 * 这样做的好处是：审批、参数补齐、异步任务、失败重试和结果回填都可以围绕同一个 auditId 演进，
 * 不会因为 DAG 引入后出现第二套工具执行事实。</p>
 *
 * @param nodeId DAG 节点 ID。优先来自 governanceHints.planNodeId；缺失时由 Java 按顺序生成。
 * @param auditId 工具审计 ID，审批、执行、结果查询仍以 auditId 为事实主键。
 * @param sequence 原 ToolPlan 顺序，用于兼容旧线性计划和展示。
 * @param toolCode 工具编码。
 * @param state 当前工具审计状态。
 * @param executionMode 工具执行模式。
 * @param executionDecision 现有 execution-policy 对该工具的策略判断。
 * @param parallelGroup 并行组名。相同并行组的节点通常可在依赖满足后并行调度。
 * @param failurePolicy 节点失败策略，例如 BLOCK_RUN、CONTINUE_ON_FAILURE、SKIP_DEPENDENTS。
 * @param resultAlias 结果别名，用于后续模型结果回填或下游节点引用前序输出。
 * @param dependsOnNodeIds 当前节点依赖的前置节点 ID 列表。
 * @param dependentNodeIds 依赖当前节点的后置节点 ID 列表。
 * @param dependencySatisfied 当前节点的依赖是否已经满足。
 * @param readyForExecution 当前节点是否已经具备进入执行候选的图依赖条件和策略条件。
 * @param blockedByNodeIds 阻塞当前节点的前置节点 ID。
 * @param reasons 节点级解释说明。
 * @param recommendedActions 推荐处理动作。
 */
public record AgentToolPlanDagNodeView(
        String nodeId,
        String auditId,
        Integer sequence,
        String toolCode,
        String state,
        String executionMode,
        String executionDecision,
        String parallelGroup,
        String failurePolicy,
        String resultAlias,
        List<String> dependsOnNodeIds,
        List<String> dependentNodeIds,
        Boolean dependencySatisfied,
        Boolean readyForExecution,
        List<String> blockedByNodeIds,
        List<String> reasons,
        List<String> recommendedActions
) {
}
