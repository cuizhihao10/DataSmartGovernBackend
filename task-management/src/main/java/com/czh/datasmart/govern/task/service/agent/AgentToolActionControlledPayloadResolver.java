/**
 * @Author : Cui
 * @Date: 2026/06/11 22:00
 * @Description DataSmart Govern Backend - AgentToolActionControlledPayloadResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandContractSupport;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandContractSupport.CommandKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 受控工具动作任务 payload 解析器。
 *
 * <p>它是新 `AGENT_TOOL_ACTION_CONTROLLED` dry-run 链路的第一道门。与历史
 * {@link AgentAsyncToolPayloadResolver} 不同，本解析器不会调用 agent-runtime 的 plan-arguments 读取接口，
 * 也不会尝试恢复真实工具参数。它只解析 task.params 中的低敏命令信封，并验证该任务确实来自
 * `AGENT_TOOL_ACTION_CONTROLLED_COMMAND`。</p>
 *
 * <p>为什么要单独做一个 resolver：如果复用历史 resolver，就必须把 `agent-payload:` 伪装成
 * `agent-tool-audit://.../plan-arguments`，这会破坏协议边界。单独 resolver 可以明确告诉后续执行器：
 * “你拿到的是控制面任务，不是可执行参数快照”。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionControlledPayloadResolver {

    private static final String EXPECTED_COMMAND_KIND = CommandKind.TOOL_ACTION_CONTROLLED.name();
    private static final String EXPECTED_REFERENCE_TYPE = "AGENT_PAYLOAD";
    private static final int MAX_EVIDENCE_ITEMS = 30;
    private static final int MAX_EVIDENCE_LENGTH = 512;

    private final ObjectMapper objectMapper;

    /**
     * 解析并校验受控工具动作任务。
     *
     * @param task 已由专用 dry-run dispatcher 认领的任务。
     * @return 低敏 payload 快照，供后续 pre-check/dry-run 决策使用。
     */
    public AgentToolActionControlledTaskPayload resolve(Task task) {
        validateTask(task);
        Map<String, Object> params = parseParams(task.getParams());
        CommandKind commandKind = AgentAsyncTaskCommandContractSupport.requireSupportedCommandType(
                requiredText(params, "commandType")
        );
        if (!CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)) {
            throw new IllegalArgumentException("受控工具动作 dry-run 只能处理 AGENT_TOOL_ACTION_CONTROLLED_COMMAND");
        }
        AgentAsyncTaskCommandContractSupport.validateTargetService(commandKind, requiredText(params, "targetService"));
        AgentAsyncTaskCommandContractSupport.normalizeTargetEndpoint(commandKind, optionalText(params.get("targetEndpoint")));
        String runId = requiredText(params, "runId");
        String payloadReference = requiredText(params, "payloadReference");
        AgentAsyncTaskCommandContractSupport.validatePayloadReference(
                commandKind,
                payloadReference,
                requiredText(params, "sessionId"),
                runId,
                requiredText(params, "auditId")
        );
        validateCommandShape(params);
        List<String> argumentNames = stringList(params.get("argumentNames"), "argumentNames");
        List<String> sensitiveArgumentNames = stringList(params.get("sensitiveArgumentNames"), "sensitiveArgumentNames");
        if (!new LinkedHashSet<>(argumentNames).containsAll(sensitiveArgumentNames)) {
            throw new IllegalArgumentException("sensitiveArgumentNames 必须是 argumentNames 的子集");
        }
        List<String> delegationEvidence = evidenceList(params.get("delegationEvidence"), "delegationEvidence");
        return new AgentToolActionControlledTaskPayload(
                task.getId(),
                task.getStatus(),
                task.getType(),
                task.getTenantId(),
                task.getProjectId(),
                optionalText(params.get("actorId")),
                requiredText(params, "commandId"),
                requiredText(params, "commandType"),
                requiredText(params, "commandKind"),
                requiredText(params, "auditId"),
                requiredText(params, "sessionId"),
                runId,
                requiredText(params, "toolCode"),
                requiredText(params, "targetService"),
                optionalText(params.get("targetEndpoint")),
                optionalLong(params.get("workspaceId"), "workspaceId"),
                payloadReference,
                requiredText(params, "payloadReferenceType"),
                parsePayloadKey(payloadReference),
                booleanValue(params.get("workerDispatchEnabled")),
                argumentNames,
                sensitiveArgumentNames,
                optionalText(params.get("confirmationId")),
                evidenceList(params.get("policyVersions"), "policyVersions"),
                delegationEvidence,
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
        if (!AgentAsyncTaskCommandContractSupport.TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED.equals(task.getType())) {
            throw new IllegalArgumentException("受控工具动作 dry-run 只能处理 AGENT_TOOL_ACTION_CONTROLLED，taskType="
                    + task.getType());
        }
        if (task.getParams() == null || task.getParams().isBlank()) {
            throw new IllegalArgumentException("受控工具动作任务缺少 params 低敏命令信封");
        }
    }

    private Map<String, Object> parseParams(String params) {
        try {
            return objectMapper.readValue(params, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("受控工具动作任务 params 不是合法 JSON: " + exception.getMessage(), exception);
        }
    }

    /**
     * 校验 task.params 中与“新受控动作”强相关的字段。
     *
     * <p>这些字段理论上由 `AgentAsyncTaskCommandConsumerService` 写入，但 dry-run dispatcher 仍要重新校验。
     * 原因是任务可能来自历史数据、人工 SQL 修复、测试脚本或未来导入流程；执行器边界不能盲信任务表。</p>
     */
    private void validateCommandShape(Map<String, Object> params) {
        if (!EXPECTED_COMMAND_KIND.equals(requiredText(params, "commandKind"))) {
            throw new IllegalArgumentException("commandKind 必须是 TOOL_ACTION_CONTROLLED");
        }
        if (!EXPECTED_REFERENCE_TYPE.equals(requiredText(params, "payloadReferenceType"))) {
            throw new IllegalArgumentException("payloadReferenceType 必须是 AGENT_PAYLOAD");
        }
        if (booleanValue(params.get("workerDispatchEnabled"))) {
            throw new IllegalArgumentException("AGENT_TOOL_ACTION_CONTROLLED 不能被旧 workerDispatchEnabled 执行");
        }
    }

    private String parsePayloadKey(String payloadReference) {
        String body = payloadReference.substring(AgentAsyncTaskCommandContractSupport.PAYLOAD_REFERENCE_AGENT_PAYLOAD_PREFIX.length());
        String[] parts = body.split("/", -1);
        return parts.length >= 2 ? parts[1] : "";
    }

    private List<String> stringList(Object value, String fieldName) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(fieldName + " 必须是字符串数组");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String text = optionalText(item);
            if (text != null && seen.add(text)) {
                normalized.add(text);
            }
        }
        return normalized;
    }

    private List<String> evidenceList(Object value, String fieldName) {
        List<String> values = stringList(value, fieldName);
        if (values.size() > MAX_EVIDENCE_ITEMS) {
            throw new IllegalArgumentException(fieldName + " 数量不能超过 " + MAX_EVIDENCE_ITEMS);
        }
        for (String item : values) {
            if (item.length() > MAX_EVIDENCE_LENGTH) {
                throw new IllegalArgumentException(fieldName + " 单项长度不能超过 " + MAX_EVIDENCE_LENGTH);
            }
            if (looksLikeSensitivePayload(item)) {
                throw new IllegalArgumentException(fieldName + " 只能保存低敏证据，不能包含 SQL、prompt、token 或密码片段");
            }
        }
        return values;
    }

    private boolean looksLikeSensitivePayload(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("prompt:");
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Long optionalLong(Object value, String fieldName) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
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

    private String requiredText(Map<String, Object> params, String fieldName) {
        String text = optionalText(params.get(fieldName));
        if (text == null) {
            throw new IllegalArgumentException("受控工具动作任务 params 缺少 " + fieldName);
        }
        return text;
    }

    private String optionalText(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}
