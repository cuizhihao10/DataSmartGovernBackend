/**
 * @Author : Cui
 * @Date: 2026/06/07 14:27
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 工具动作执行图预览查询响应。
 *
 * <p>响应以查询窗口为单位返回聚合指标和图列表。窗口聚合很重要：真实商业化管理台不仅要看单个工具动作，还要快速回答
 * “最近有多少工具动作卡在审批”“有多少缺 payloadReference”“有多少已经具备 outbox 入箱条件”等运营问题。</p>
 *
 * @param limit 本次查询规范化后的事件窗口上限。
 * @param sourceContractCount 来源 durable action contract 数量。
 * @param totalGraphs 推导出的执行图数量，通常与 contract 数量一致。
 * @param readyForOutboxGraphCount 已满足最低 outbox 写入条件的图数量。
 * @param waitingApprovalGraphCount 等待人工审批的图数量。
 * @param waitingClarificationGraphCount 等待用户或上游 Agent 澄清的图数量。
 * @param waitingBudgetGraphCount 等待预算或 worker 队列恢复的图数量。
 * @param blockedGraphCount 已阻断或入口拒绝的图数量。
 * @param graphStateCounts 按 graphState 聚合的图数量。
 * @param nodeTypeCounts 按节点类型聚合的节点数量。
 * @param missingRequirementCounts 按缺失要求聚合的图数量。
 * @param summaryReasons 本次窗口的中文总结。
 * @param recommendedActions 面向下一阶段产品实现和运维治理的建议。
 * @param graphs 执行图预览列表。
 */
public record AgentToolActionExecutionGraphQueryResponse(
        Integer limit,
        Integer sourceContractCount,
        Integer totalGraphs,
        Long readyForOutboxGraphCount,
        Long waitingApprovalGraphCount,
        Long waitingClarificationGraphCount,
        Long waitingBudgetGraphCount,
        Long blockedGraphCount,
        Map<String, Long> graphStateCounts,
        Map<String, Long> nodeTypeCounts,
        Map<String, Long> missingRequirementCounts,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentToolActionExecutionGraphView> graphs
) {
}
