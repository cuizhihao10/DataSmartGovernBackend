/**
 * @Author : Cui
 * @Date: 2026/06/28 23:10
 * @Description DataSmart Govern Backend - AgentToolActionQualityRemediationSubmitResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 质量治理受控命令提交响应。
 *
 * <p>响应只包含低敏执行摘要，便于 task-management worker 写回 command worker receipt。它不会返回
 * data-quality 的 payloadPreview、payload body、异常 TOP 聚合、SQL、prompt、样本或模型输出。</p>
 *
 * @param accepted agent-runtime 是否接受本次提交请求。
 * @param duplicate 是否命中本 JVM 内 commandId 幂等缓存。
 * @param sideEffectStarted 是否已经越过 data-quality HTTP 副作用边界。
 * @param sideEffectExecuted 是否确认 data-quality/task-management 已提交真实治理任务。
 * @param outcome worker receipt 可使用的低敏结果编码。
 * @param commandId outbox command ID。
 * @param payloadReference 已执行的服务端 payload 引用。
 * @param confirmationId 已校验的审批确认事实 ID。
 * @param taskId data-quality 返回的真实 task-management 任务 ID；未提交时为空。
 * @param taskStatus data-quality 返回的任务状态；未提交时为空。
 * @param message 人类可读摘要。
 * @param issueCodes 阻断或下游未提交原因。
 * @param recommendedActions 后续建议。
 */
public record AgentToolActionQualityRemediationSubmitResponse(
        Boolean accepted,
        Boolean duplicate,
        Boolean sideEffectStarted,
        Boolean sideEffectExecuted,
        String outcome,
        String commandId,
        String payloadReference,
        String confirmationId,
        Long taskId,
        String taskStatus,
        String message,
        List<String> issueCodes,
        List<String> recommendedActions
) {

    public AgentToolActionQualityRemediationSubmitResponse {
        accepted = Boolean.TRUE.equals(accepted);
        duplicate = Boolean.TRUE.equals(duplicate);
        sideEffectStarted = Boolean.TRUE.equals(sideEffectStarted);
        sideEffectExecuted = Boolean.TRUE.equals(sideEffectExecuted);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }
}
