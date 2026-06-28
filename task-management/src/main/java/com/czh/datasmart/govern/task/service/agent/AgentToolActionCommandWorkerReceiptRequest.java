/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.List;

/**
 * task-management 写回 agent-runtime command worker receipt 的请求副本。
 *
 * <p>该契约表达“task-management worker 已经完成 command 级前置复核或受控执行，并把低敏结果交给
 * agent-runtime timeline”。它可以声明 sideEffectExecuted=true，但必须携带 command worker lease 的
 * fencingToken/version/expiresAt，让 agent-runtime 校验当前 worker 仍然有资格写回。</p>
 *
 * <p>字段不得包含命令行、stdout/stderr、payload body、工具参数、SQL、prompt、样本、模型输出、真实路径或凭据。
 * 执行产物如果需要展示，只能通过受控 artifactReference 或 taskId 这类低敏引用进入。</p>
 */
public record AgentToolActionCommandWorkerReceiptRequest(
        String commandId,
        Long taskId,
        Long taskRunId,
        String executorId,
        Long tenantId,
        Long projectId,
        Long actorId,
        String taskStatus,
        String outcome,
        Boolean preCheckPassed,
        Boolean sideEffectStarted,
        Boolean sideEffectExecuted,
        Boolean workerLeaseRequired,
        String fencingToken,
        Long workerLeaseVersion,
        Long workerLeaseExpiresAtMs,
        String commandSafetyDecision,
        String commandSafetyPolicyVersion,
        List<String> commandSafetyIssueCodes,
        Integer normalizedTimeoutSeconds,
        Integer normalizedOutputByteLimitBytes,
        String artifactReferenceType,
        String artifactReference,
        Boolean artifactAvailable,
        String errorCode,
        String auditId,
        String toolCode,
        String targetService,
        String workerReceiptMode,
        String message,
        List<String> recommendedActions,
        String idempotencyKey
) {
}
