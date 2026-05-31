/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentAsyncToolResolvedPayload.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 异步工具载荷预检结果。
 *
 * <p>该对象是 task-management worker 执行前的“安全观察窗”。
 * 它表示任务、command 摘要、payloadReference 和 agent-runtime 审计快照已经完成一致性校验，
 * 但不代表真实工具已经执行。</p>
 */
public record AgentAsyncToolResolvedPayload(
        Long taskId,
        String taskStatus,
        String taskType,
        String commandId,
        String payloadReference,
        String payloadKind,
        String sessionId,
        String runId,
        String auditId,
        String toolCode,
        String targetService,
        String targetEndpoint,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String actorId,
        String traceId,
        String executionMode,
        String auditState,
        List<String> argumentNames,
        List<String> sensitiveArgumentNames,
        Integer payloadBytes,
        Boolean dryRunOnly,
        Map<String, Object> planArguments,
        Map<String, Object> governanceHints,
        Map<String, Object> parameterValidation,
        List<String> validationMessages,
        List<String> recommendedActions,
        LocalDateTime resolvedAt) {

    public AgentAsyncToolResolvedPayload {
        argumentNames = argumentNames == null ? List.of() : List.copyOf(argumentNames);
        sensitiveArgumentNames = sensitiveArgumentNames == null ? List.of() : List.copyOf(sensitiveArgumentNames);
        planArguments = planArguments == null ? Map.of() : new LinkedHashMap<>(planArguments);
        governanceHints = governanceHints == null ? Map.of() : new LinkedHashMap<>(governanceHints);
        parameterValidation = parameterValidation == null ? Map.of() : new LinkedHashMap<>(parameterValidation);
        validationMessages = validationMessages == null ? List.of() : List.copyOf(validationMessages);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }
}
