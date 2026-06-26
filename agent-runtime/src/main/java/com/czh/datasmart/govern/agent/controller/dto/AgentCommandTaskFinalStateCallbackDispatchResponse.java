/**
 * @Author : Cui
 * @Date: 2026/06/27 01:24
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackDispatchResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent command 最终态回调投递响应。
 *
 * <p>该响应同时返回“对账结果”和“投递结果”，方便调用方判断为什么没有投递：
 * 可能是 dry-run、对账不推荐回调、缺少 taskId/taskRunId/executorId、RUNNING 非终态被策略跳过，
 * 也可能是 task-management 接口返回失败。</p>
 *
 * <p>安全边界：响应只返回 taskId/taskRunId/executorId/callbackStatus/幂等键等低敏控制面信息。
 * 不返回 task-management baseUrl、内部 endpoint、命令正文、stdout/stderr、artifact 正文、SQL、prompt、
 * 工具参数值、样本数据、模型输出、凭证或 token。</p>
 *
 * @param payloadPolicy 本响应的低敏载荷策略。
 * @param commandId 本次处理的 commandId。
 * @param dryRun true 表示只演练不向 task-management 发起状态写入。
 * @param dispatchAttempted true 表示已经实际调用过 task-management。
 * @param dispatched true 表示 task-management 成功接受本次回调。
 * @param deliveryStatus 投递状态机摘要，例如 DRY_RUN、DISPATCHED、SKIPPED、FAILED。
 * @param taskId task-management 任务 ID。
 * @param taskRunId task-management 运行 ID。
 * @param executorId 当前 receipt 声明的执行器 ID。
 * @param callbackStatus 本次映射的回调状态。
 * @param targetOperation 低敏目标操作名，不返回 URL 或内部路径。
 * @param idempotencyKey 本次回调使用的幂等键。
 * @param downstreamAccepted task-management 是否接受响应。
 * @param downstreamMessage 下游低敏响应说明，经过截断和敏感词过滤。
 * @param reconciliation 本次投递前重新计算出的对账响应。
 * @param issueCodes 投递层发现的问题码。
 * @param recommendedActions 面向运维或后续 worker 的建议动作。
 */
public record AgentCommandTaskFinalStateCallbackDispatchResponse(
        String payloadPolicy,
        String commandId,
        Boolean dryRun,
        Boolean dispatchAttempted,
        Boolean dispatched,
        String deliveryStatus,
        Long taskId,
        Long taskRunId,
        String executorId,
        String callbackStatus,
        String targetOperation,
        String idempotencyKey,
        Boolean downstreamAccepted,
        String downstreamMessage,
        AgentCommandTaskFinalStateReconciliationResponse reconciliation,
        List<String> issueCodes,
        List<String> recommendedActions
) {
}
