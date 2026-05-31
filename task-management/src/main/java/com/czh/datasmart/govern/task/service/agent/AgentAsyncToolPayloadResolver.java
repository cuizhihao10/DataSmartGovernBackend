/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentAsyncToolPayloadResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.entity.Task;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 异步工具 payloadReference 解析与预检服务。
 *
 * <p>该服务是 4.51 的核心：它把 task.params 中保存的安全摘要与 agent-runtime 返回的真实参数快照做一致性校验。
 * 这一步必须发生在真正 worker 执行工具之前，否则 Kafka command、task 表或执行器本地缓存一旦被污染，
 * worker 可能会拿错误参数调用下游服务。</p>
 *
 * <p>当前校验范围：</p>
 * <p>1. 任务类型必须是 AGENT_ASYNC_TOOL；</p>
 * <p>2. payloadReference 必须是 agent-tool-audit:// 协议并指向 plan-arguments；</p>
 * <p>3. task.params 中的 sessionId/runId/auditId/tool/tenant/project/workspace 必须与 Agent Runtime 快照一致；</p>
 * <p>4. argumentNames 与 sensitiveArgumentNames 必须一致，防止参数快照悄悄变化；</p>
 * <p>5. 解析后的参数载荷不能超过配置上限，避免大对象拖垮 worker 或日志系统。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncToolPayloadResolver {

    public static final String TASK_TYPE = "AGENT_ASYNC_TOOL";

    private final AgentRuntimePayloadReferenceClient payloadReferenceClient;
    private final AgentAsyncToolWorkerProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 解析任务中的 Agent 异步工具载荷。
     *
     * @param task task-management 中已创建的异步工具任务。
     * @param traceId 当前链路追踪 ID。
     * @return 执行前预检结果。当前仅表示可用于下一阶段工具适配器，不会产生业务副作用。
     */
    public AgentAsyncToolResolvedPayload resolve(Task task, String traceId) {
        validateTask(task);
        Map<String, Object> taskParams = parseTaskParams(task.getParams());
        AgentToolAuditPayloadReference reference = AgentToolAuditPayloadReference.parse(requiredText(taskParams, "payloadReference"));
        validateReferenceMatchesTaskParams(reference, taskParams);

        AgentRuntimePlanArgumentsPayloadResponse payload = payloadReferenceClient.fetchPlanArguments(reference, traceId);
        validatePayloadMatchesTask(task, taskParams, reference, payload);

        int payloadBytes = payloadBytes(payload.planArguments());
        if (payloadBytes > Math.max(1, properties.getMaxResolvedPayloadBytes())) {
            throw new IllegalStateException("Agent 异步工具参数载荷超过 worker 安全上限，payloadBytes="
                    + payloadBytes + ", maxResolvedPayloadBytes=" + properties.getMaxResolvedPayloadBytes());
        }

        return new AgentAsyncToolResolvedPayload(
                task.getId(),
                task.getStatus(),
                task.getType(),
                requiredText(taskParams, "commandId"),
                reference.toCanonicalString(),
                reference.payloadKind(),
                payload.sessionId(),
                payload.runId(),
                payload.auditId(),
                payload.toolCode(),
                payload.targetService(),
                payload.targetEndpoint(),
                payload.tenantId(),
                payload.projectId(),
                payload.workspaceId(),
                payload.actorId(),
                payload.traceId(),
                payload.executionMode(),
                payload.state(),
                payload.argumentNames(),
                payload.sensitiveArgumentNames(),
                payloadBytes,
                properties.isDryRunOnly(),
                payload.planArguments(),
                payload.governanceHints(),
                payload.parameterValidation(),
                validationMessages(payloadBytes),
                recommendedActions(),
                LocalDateTime.now()
        );
    }

    private void validateTask(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("任务不能为空");
        }
        if (task.getId() == null) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        if (!TASK_TYPE.equals(task.getType())) {
            throw new IllegalArgumentException("只有 AGENT_ASYNC_TOOL 任务才能解析 Agent 工具载荷，taskType=" + task.getType());
        }
        if (task.getParams() == null || task.getParams().isBlank()) {
            throw new IllegalArgumentException("Agent 异步工具任务缺少 params 安全摘要");
        }
    }

    private Map<String, Object> parseTaskParams(String params) {
        try {
            return objectMapper.readValue(params, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 异步工具任务 params 不是合法 JSON: " + exception.getMessage(), exception);
        }
    }

    private void validateReferenceMatchesTaskParams(AgentToolAuditPayloadReference reference,
                                                    Map<String, Object> taskParams) {
        requireEquals("sessionId", reference.sessionId(), requiredText(taskParams, "sessionId"));
        requireEquals("runId", reference.runId(), requiredText(taskParams, "runId"));
        requireEquals("auditId", reference.auditId(), requiredText(taskParams, "auditId"));
    }

    private void validatePayloadMatchesTask(Task task,
                                            Map<String, Object> taskParams,
                                            AgentToolAuditPayloadReference reference,
                                            AgentRuntimePlanArgumentsPayloadResponse payload) {
        if (payload == null) {
            throw new IllegalStateException("agent-runtime 返回的参数载荷为空");
        }
        requireEquals("payloadReference", reference.toCanonicalString(), payload.payloadReference());
        requireEquals("payloadKind", reference.payloadKind(), payload.payloadKind());
        requireEquals("sessionId", requiredText(taskParams, "sessionId"), payload.sessionId());
        requireEquals("runId", requiredText(taskParams, "runId"), payload.runId());
        requireEquals("auditId", requiredText(taskParams, "auditId"), payload.auditId());
        requireEquals("toolCode", requiredText(taskParams, "toolCode"), payload.toolCode());
        requireEquals("targetService", requiredText(taskParams, "targetService"), payload.targetService());
        requireEquals("targetEndpoint", requiredText(taskParams, "targetEndpoint"), payload.targetEndpoint());
        requireEquals("tenantId", task.getTenantId(), payload.tenantId());
        requireEquals("projectId", task.getProjectId(), payload.projectId());
        requireEquals("workspaceId", longValue(taskParams.get("workspaceId"), "workspaceId"), payload.workspaceId());
        requireListEquals("argumentNames", stringList(taskParams.get("argumentNames"), "argumentNames"), payload.argumentNames());
        requireListEquals("sensitiveArgumentNames",
                stringList(taskParams.get("sensitiveArgumentNames"), "sensitiveArgumentNames"),
                payload.sensitiveArgumentNames());
    }

    private int payloadBytes(Map<String, Object> planArguments) {
        try {
            return objectMapper.writeValueAsString(planArguments == null ? Map.of() : planArguments)
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 异步工具参数载荷无法序列化，不能进入执行阶段", exception);
        }
    }

    private List<String> validationMessages(int payloadBytes) {
        List<String> messages = new ArrayList<>();
        messages.add("payloadReference 已按 agent-tool-audit:// 协议解析，并与 task.params 摘要完成一致性校验。");
        messages.add("参数载荷大小为 " + payloadBytes + " 字节，未超过 worker 配置上限。");
        messages.add("当前阶段只完成执行前预检，真实工具调用仍需等待工具适配器白名单、权限策略和状态回写闭环。");
        return messages;
    }

    private List<String> recommendedActions() {
        if (properties.isDryRunOnly()) {
            return List.of(
                    "保持 dry-run 预检模式，继续补充工具适配器白名单和任务状态回写。",
                    "不要让 worker 直接调用 targetEndpoint；应由明确的工具适配器按 toolCode 解释参数并执行。",
                    "后续接入 permission-admin 服务间策略，校验 actor、tenant、project、workspace 与目标资源的访问边界。"
            );
        }
        return List.of(
                "将该预检结果交给受控工具适配器执行，而不是拼接任意 HTTP URL。",
                "执行后应回写 task-management 任务状态，并发布事件同步 agent-runtime 工具审计。"
        );
    }

    private String requiredText(Map<String, Object> params, String fieldName) {
        Object value = params.get(fieldName);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Agent 异步工具任务 params 缺少 " + fieldName);
        }
        return String.valueOf(value).trim();
    }

    private Long longValue(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("Agent 异步工具任务 params 缺少 " + fieldName);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " 不是合法数字: " + value, exception);
        }
    }

    private List<String> stringList(Object value, String fieldName) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(fieldName + " 必须是字符串数组");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                normalized.add(String.valueOf(item).trim());
            }
        }
        return new ArrayList<>(normalized);
    }

    private void requireEquals(String fieldName, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("Agent 异步工具载荷字段不一致，field=" + fieldName
                    + ", expected=" + expected + ", actual=" + actual);
        }
    }

    private void requireListEquals(String fieldName, List<String> expected, List<String> actual) {
        if (!Objects.equals(expected == null ? List.of() : expected, actual == null ? List.of() : actual)) {
            throw new IllegalStateException("Agent 异步工具载荷列表字段不一致，field=" + fieldName
                    + ", expected=" + expected + ", actual=" + actual);
        }
    }
}
