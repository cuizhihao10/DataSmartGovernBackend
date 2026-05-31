/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentRuntimePlanArgumentsPayloadResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * task-management 侧接收的 Agent Runtime 参数载荷响应。
 *
 * <p>该 record 与 agent-runtime 的 `AgentToolPlanArgumentsPayloadView` 保持字段契约一致，
 * 但不直接跨模块依赖 agent-runtime Java 类型。微服务之间应通过 HTTP/JSON 契约耦合，
 * 而不是让 task-management 在编译期依赖另一个业务模块的 DTO 包。</p>
 */
public record AgentRuntimePlanArgumentsPayloadResponse(
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
        String state,
        List<String> argumentNames,
        List<String> sensitiveArgumentNames,
        Map<String, Object> planArguments,
        Map<String, Object> governanceHints,
        Map<String, Object> parameterValidation,
        LocalDateTime resolvedAt) {

    public AgentRuntimePlanArgumentsPayloadResponse {
        argumentNames = argumentNames == null ? List.of() : List.copyOf(argumentNames);
        sensitiveArgumentNames = sensitiveArgumentNames == null ? List.of() : List.copyOf(sensitiveArgumentNames);
        planArguments = planArguments == null ? Map.of() : new LinkedHashMap<>(planArguments);
        governanceHints = governanceHints == null ? Map.of() : new LinkedHashMap<>(governanceHints);
        parameterValidation = parameterValidation == null ? Map.of() : new LinkedHashMap<>(parameterValidation);
    }
}
