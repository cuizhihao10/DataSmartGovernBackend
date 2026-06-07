/**
 * @Author : Cui
 * @Date: 2026/06/07 13:39
 * @Description DataSmart Govern Backend - AgentToolActionIntakeProjectionQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 工具动作意图入口投影查询响应。
 *
 * <p>该响应既返回单条事件快照，也返回本次查询窗口内的低敏聚合。这样管理台可以直接看到当前窗口内：
 * 有多少外部工具意图被接收、多少在 readiness 之前被拒绝、多少进入审批/澄清/阻断，而不需要前端遍历自由
 * attributes map。当前聚合只基于本次返回窗口；未来如果为 `tool_action_intake_recorded` 落 dedicated index，
 * 可以复用同一个 DTO 承载数据库聚合结果。</p>
 *
 * @param limit 本次查询规范化后的返回上限，防止管理台一次性拉取过多事件。
 * @param totalMatched 本次查询实际命中的快照数量。
 * @param indexSource 投影来源。当前仍来自 runtime-event 内存投影，后续可切换到专用索引。
 * @param acceptedWindowCount 当前窗口内至少有一个工具计划被 intake 接收的事件数。
 * @param rejectedBeforeReadinessWindowCount 当前窗口内至少有一个工具动作在 readiness 前被拒绝的事件数。
 * @param readinessExecutableWindowCount 当前窗口内至少有一个工具通过执行前准备度检查的事件数。
 * @param readinessApprovalRequiredWindowCount 当前窗口内至少有一个工具需要人工审批的事件数。
 * @param readinessClarificationRequiredWindowCount 当前窗口内至少有一个工具需要补充澄清的事件数。
 * @param readinessBlockedWindowCount 当前窗口内至少有一个工具被执行前策略阻断的事件数。
 * @param boundaryCounts intake 边界计数，例如 TOOL_PLAN_READINESS_GRAPH 或 REJECTED_BEFORE_READINESS。
 * @param graphBranchCounts readiness graph 分支计数，用于趋势卡片和筛选项。
 * @param toolNameCounts 工具名低敏计数。这里只统计工具注册名，不包含参数。
 * @param issueCodeCounts 治理问题码计数，用于排障和策略调优。
 * @param nextActionCounts 下一步动作计数，例如 EXECUTE_READY_TOOLS、CREATE_OR_WAIT_APPROVAL。
 * @param snapshots 本次查询返回的强类型低敏事件快照。
 */
public record AgentToolActionIntakeProjectionQueryResponse(
        Integer limit,
        Integer totalMatched,
        String indexSource,
        Long acceptedWindowCount,
        Long rejectedBeforeReadinessWindowCount,
        Long readinessExecutableWindowCount,
        Long readinessApprovalRequiredWindowCount,
        Long readinessClarificationRequiredWindowCount,
        Long readinessBlockedWindowCount,
        Map<String, Long> boundaryCounts,
        Map<String, Long> graphBranchCounts,
        Map<String, Long> toolNameCounts,
        Map<String, Long> issueCodeCounts,
        Map<String, Long> nextActionCounts,
        List<AgentToolActionIntakeProjectionView> snapshots
) {
}
