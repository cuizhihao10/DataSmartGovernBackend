/**
 * @Author : Cui
 * @Date: 2026/06/07 13:39
 * @Description DataSmart Govern Backend - AgentToolActionIntakeDecisionSummaryView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 工具动作入口事件中的低敏 readiness 决策摘要。
 *
 * <p>这个 DTO 服务于 `tool_action_intake_recorded` 事件。它只描述“某个工具计划在执行前被如何治理”，
 * 例如是否可执行、是否需要进入异步队列、是否需要人工审批，而不描述工具执行结果，也不返回工具参数值。
 * 字段来源于 Python Runtime 已经裁剪过的 `decisionSummaries` 白名单。</p>
 *
 * @param toolName 工具注册名。该字段用于前端分组、趋势统计和运营排障，不包含工具参数。
 * @param decision 执行前决策，例如 ready_to_execute、waiting_approval、blocked、needs_clarification。
 * @param executable 当前是否具备进入受控执行链路的准备度。注意它不代表工具已经执行。
 * @param queueRequired 是否需要异步队列承接，通常对应 QUEUE_ASYNC 或 worker backlog 管控场景。
 * @param requiresHumanApproval 是否需要人工审批或确认，通常需要 permission-admin 或确认页返回事实。
 * @param parameterIssueCount 参数问题数量。它只用于提示风险规模，不包含参数名称以外的敏感值。
 * @param issueCodes 参数或治理问题码。稳定机器字段，可做筛选、国际化和运营报表。
 * @param reasonCodes 决策原因码。稳定机器字段，可做审计聚合和策略复盘。
 * @param retryHint 低敏重试提示，说明应等待控制面、预算恢复、审批通过还是队列重试。
 */
public record AgentToolActionIntakeDecisionSummaryView(
        String toolName,
        String decision,
        Boolean executable,
        Boolean queueRequired,
        Boolean requiresHumanApproval,
        Integer parameterIssueCount,
        List<String> issueCodes,
        List<String> reasonCodes,
        String retryHint
) {
}
