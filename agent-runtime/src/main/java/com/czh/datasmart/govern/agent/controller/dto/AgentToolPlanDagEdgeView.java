/**
 * @Author : Cui
 * @Date: 2026/05/31 22:20
 * @Description DataSmart Govern Backend - AgentToolPlanDagEdgeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent ToolPlan DAG 依赖边视图。
 *
 * <p>该 DTO 用于表达“某个工具节点必须在另一个工具节点之后执行”的编排约束。
 * 它不是执行记录，也不会触发工具调用；它只是把 Python Runtime 的工具计划从线性列表逐步升级成
 * 可审计、可解释、可回放的有向无环图。</p>
 *
 * @param fromNodeId 前置节点 ID。只有当前置节点达到可接受状态时，后置节点才可能进入执行候选。
 * @param toNodeId 后置节点 ID。
 * @param edgeType 依赖边类型，例如 EXPLICIT_DEPENDENCY 或 LEGACY_SEQUENCE。
 * @param reason 依赖边来源说明，便于前端、审计员和学习者理解为什么形成这条边。
 */
public record AgentToolPlanDagEdgeView(
        String fromNodeId,
        String toNodeId,
        String edgeType,
        String reason
) {
}
