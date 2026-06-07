/**
 * @Author : Cui
 * @Date: 2026/06/07 14:04
 * @Description DataSmart Govern Backend - AgentToolActionIntakeDurableActionContractQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 工具动作入口 durable action 契约预览查询响应。
 *
 * <p>响应以“查询窗口”为单位返回统计结果。这样前端和运营台不需要逐条扫描 contract，就能知道当前窗口内
 * 有多少工具动作已经接近可入箱、多少等待审批、多少仍被阻断或拒绝。该响应仍坚持低敏边界，不返回原始
 * arguments、payload、SQL、prompt、模型输出、凭证或内部 endpoint。</p>
 *
 * @param limit 本次查询规范化后的事件窗口上限。
 * @param sourceSnapshotCount 来源工具动作入口事件数量。
 * @param totalContracts 本次推导出的契约数量。一个事件可能包含多个工具决策摘要，因此契约数可能大于事件数。
 * @param readyForDurableContractCount 当前已经具备 readiness 通过语义，但仍可能缺 outbox/receipt 证据的契约数。
 * @param waitingApprovalCount 等待人工审批的契约数。
 * @param waitingClarificationCount 等待用户或上游 Agent 澄清的契约数。
 * @param blockedOrRejectedCount 已阻断或 readiness 前拒绝的契约数。
 * @param outboxWritableNowCount 当前具备立即写 outbox 最低条件的契约数。preview-only 阶段通常为 0。
 * @param contractStateCounts 按 durableActionState 聚合的契约数。
 * @param toolNameCounts 按工具名聚合的契约数。
 * @param missingRequirementCounts 按缺失生产要求聚合的契约数。
 * @param summaryReasons 本次窗口的低敏总结。
 * @param recommendedActions 下一步建议，面向控制面建设和运营排障。
 * @param contracts 契约预览列表。
 */
public record AgentToolActionIntakeDurableActionContractQueryResponse(
        Integer limit,
        Integer sourceSnapshotCount,
        Integer totalContracts,
        Long readyForDurableContractCount,
        Long waitingApprovalCount,
        Long waitingClarificationCount,
        Long blockedOrRejectedCount,
        Long outboxWritableNowCount,
        Map<String, Long> contractStateCounts,
        Map<String, Long> toolNameCounts,
        Map<String, Long> missingRequirementCounts,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentToolActionIntakeDurableActionContractView> contracts
) {
}
