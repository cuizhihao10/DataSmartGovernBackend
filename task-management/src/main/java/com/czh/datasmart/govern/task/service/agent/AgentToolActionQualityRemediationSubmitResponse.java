/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentToolActionQualityRemediationSubmitResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.List;

/**
 * agent-runtime 质量治理受控提交响应副本。
 *
 * <p>响应只包含低敏执行摘要：是否接受、是否重复、是否越过副作用边界、下游任务 ID 和低敏建议。
 * 它不能包含 data-quality payloadPreview、payload body、异常聚合正文、SQL、prompt、样本或模型输出。</p>
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
