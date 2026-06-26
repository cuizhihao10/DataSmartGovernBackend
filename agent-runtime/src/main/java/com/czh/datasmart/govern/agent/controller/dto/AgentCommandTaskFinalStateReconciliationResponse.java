/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateReconciliationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 命令任务最终态对账响应。
 *
 * <p>该响应面向智能网关、运维控制台和后续自动补偿 worker，回答一个非常关键的问题：
 * “某个 Agent command 已经收到哪些低敏 worker receipt，这些 receipt 是否足以把 task-management / Agent 审计
 * 推进到成功、失败、退避或等待状态？”</p>
 *
 * <p>设计上它只做解释和建议，不直接写 task-management，也不直接调用 Agent 审计回调。
 * 这样可以让当前阶段先完成可观测、可测试、可审计的闭环判断；等自动回调策略、服务账号签名、幂等表和
 * 人工补偿策略稳定后，再把建议接入真正的写入链路。</p>
 *
 * @param queryMode 查询模式说明，强调 commandId 必填和低敏 receipt 索引来源。
 * @param payloadPolicy 响应低敏策略。
 * @param commandId 本次对账的命令 ID。
 * @param toolCode 可选工具编码过滤条件。
 * @param tenantId 收口后的租户条件。
 * @param projectId 收口后的项目条件。
 * @param actorId 收口后的 actor 条件。
 * @param runId 收口后的 Agent Run 条件。
 * @param sessionId 收口后的 Agent Session 条件。
 * @param authorizedProjectIds PROJECT 数据范围下的授权项目集合；空集合表示不可见。
 * @param appliedLimit 本次扫描 receipt 索引的最大数量。
 * @param receiptCount 命中的 receipt 数量。
 * @param latestReceiptPresent 是否存在可用于对账的最新 receipt。
 * @param latestReceipt 最新 receipt 低敏证据视图。
 * @param reconciliationStatus 对账结论，例如 SUCCEEDED、FAILED、WAITING_WORKER、BLOCKED_BEFORE_EXECUTION。
 * @param reconciledTaskStatus 建议 task/Agent 审计进入的状态，例如 RUNNING、DEFERRED、SUCCEEDED、FAILED。
 * @param terminal true 表示建议状态为终态。
 * @param callbackRecommended true 表示当前事实足以触发状态回调或可见性刷新。
 * @param requiresManualCompensation true 表示副作用失败/补偿风险需要运维或业务人员确认。
 * @param retryable true 表示当前不是终态，后续可以等待队列、容量或 worker 重试。
 * @param callbackSuggestion 低敏回调建议。
 * @param evidenceCodes 支撑结论的低敏证据码。
 * @param issueCodes 当前仍需关注的问题码。
 * @param recommendedActions 建议下一步动作。
 * @param missingCapabilities 当前距离生产级自动闭环仍缺的能力。
 */
public record AgentCommandTaskFinalStateReconciliationResponse(
        String queryMode,
        String payloadPolicy,
        String commandId,
        String toolCode,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        List<String> authorizedProjectIds,
        Integer appliedLimit,
        Integer receiptCount,
        Boolean latestReceiptPresent,
        AgentCommandTaskFinalStateLatestReceiptView latestReceipt,
        String reconciliationStatus,
        String reconciledTaskStatus,
        Boolean terminal,
        Boolean callbackRecommended,
        Boolean requiresManualCompensation,
        Boolean retryable,
        AgentCommandTaskFinalStateCallbackSuggestionView callbackSuggestion,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions,
        List<String> missingCapabilities
) {
}
