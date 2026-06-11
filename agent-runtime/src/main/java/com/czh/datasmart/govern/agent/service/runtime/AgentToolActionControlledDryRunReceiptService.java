/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunReceiptService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionControlledDryRunReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionControlledDryRunReceiptResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 受控工具动作 dry-run receipt 接收服务。
 *
 * <p>该服务位于 agent-runtime，是 task-management dry-run 结果进入统一 Agent timeline 的入口。
 * 它不更新历史工具审计状态，也不声明工具执行成功；它只把“执行前治理闸门发生了什么”写成低敏
 * runtime event projection。这样前端、审计台和智能网关可以看到受控动作为什么被 defer 或 fail，
 * 但仍然看不到工具参数、SQL、prompt、payload body 或下游结果正文。</p>
 *
 * <p>为什么不复用 `AgentAsyncToolTaskStatusCallbackService`：
 * 历史 callback 绑定 `AgentToolExecutionAuditRecord`，用于 `AGENT_ASYNC_TOOL` 真实 worker 的运行状态；
 * 新 `AGENT_TOOL_ACTION_CONTROLLED` 仍处在控制面 dry-run 阶段，很多任务并没有历史 audit 记录。
 * 如果强行复用旧状态机，会把“执行前治理 receipt”误建模为“工具执行状态”，导致产品语义混乱。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionControlledDryRunReceiptService {

    public static final String SCHEMA_VERSION = "datasmart.agent-runtime.tool-action-controlled-dry-run-receipt.v1";
    public static final String SOURCE = "TASK_MANAGEMENT";
    public static final String EVENT_TYPE = "agent.tool_execution.controlled_dry_run_receipt_recorded";
    public static final String PAYLOAD_POLICY = "SUMMARY_ONLY_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY";

    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_ACTION_COUNT = 6;
    private static final int MAX_ACTION_LENGTH = 240;

    private final AgentRuntimeEventProjectionStore projectionStore;

    /**
     * 接收并投影一条 dry-run receipt。
     *
     * @param sessionId 路由路径中的 Agent 会话 ID，作为 timeline 聚合维度。
     * @param runId 路由路径中的 Agent Run ID，作为 timeline 聚合维度。
     * @param traceId task-management 透传的 traceId，用于跨服务排查。
     * @param request task-management 发送的低敏 receipt。
     * @return 投影写入结果；重复幂等键会被视为 accepted + duplicate。
     */
    public AgentToolActionControlledDryRunReceiptResponse receive(String sessionId,
                                                                  String runId,
                                                                  String traceId,
                                                                  AgentToolActionControlledDryRunReceiptRequest request) {
        validatePath(sessionId, "sessionId");
        validatePath(runId, "runId");
        validateRequest(request);
        AgentRuntimeEventProjectionRecord record = toProjectionRecord(sessionId.trim(), runId.trim(),
                trimToNull(traceId), request);
        boolean appended = projectionStore.append(record);
        return new AgentToolActionControlledDryRunReceiptResponse(
                true,
                !appended,
                record.identityKey(),
                EVENT_TYPE,
                appended ? "受控工具动作 dry-run receipt 已写入 runtime event timeline"
                        : "受控工具动作 dry-run receipt 已存在，本次按幂等重复处理"
        );
    }

    private AgentRuntimeEventProjectionRecord toProjectionRecord(String sessionId,
                                                                 String runId,
                                                                 String traceId,
                                                                 AgentToolActionControlledDryRunReceiptRequest request) {
        Instant now = Instant.now();
        String outcome = requireText(request.outcome(), "outcome");
        String stage = stage(outcome);
        String message = safeMessage(request.message(), outcome);
        return new AgentRuntimeEventProjectionRecord(
                identityKey(runId, request),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                stage,
                message,
                severity(outcome, request.preCheckPassed()),
                stringValue(request.tenantId()),
                stringValue(request.projectId()),
                stringValue(request.actorId()),
                traceId,
                runId,
                sessionId,
                null,
                now,
                now,
                now,
                attributes(request, outcome, message)
        );
    }

    private Map<String, Object> attributes(AgentToolActionControlledDryRunReceiptRequest request,
                                           String outcome,
                                           String message) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("commandId", safeShortText(request.commandId(), "unknown-command", 160));
        attributes.put("taskId", request.taskId());
        attributes.put("taskRunId", request.taskRunId());
        attributes.put("executorId", safeShortText(request.executorId(), "unknown-executor", 160));
        attributes.put("taskStatus", safeShortText(request.taskStatus(), "UNKNOWN", 64));
        attributes.put("outcome", outcome);
        attributes.put("preCheckPassed", Boolean.TRUE.equals(request.preCheckPassed()));
        attributes.put("sideEffectExecuted", false);
        attributes.put("message", message);
        attributes.put("errorCode", safeShortText(request.errorCode(), errorCode(outcome), 120));
        attributes.put("auditId", safeShortText(request.auditId(), null, 200));
        attributes.put("toolCode", safeShortText(request.toolCode(), null, 160));
        attributes.put("targetService", safeShortText(request.targetService(), null, 120));
        attributes.put("payloadReferenceType", safeShortText(request.payloadReferenceType(), null, 80));
        attributes.put("payloadKey", safeShortText(request.payloadKey(), null, 160));
        attributes.put("payloadStoreEvidence", Boolean.TRUE.equals(request.payloadStoreEvidence()));
        attributes.put("payloadBodyAvailable", Boolean.TRUE.equals(request.payloadBodyAvailable()));
        attributes.put("workerDispatchEnabled", Boolean.TRUE.equals(request.workerDispatchEnabled()));
        attributes.put("policyVersionCount", nonNegative(request.policyVersionCount()));
        attributes.put("delegationEvidenceCount", nonNegative(request.delegationEvidenceCount()));
        attributes.put("recommendedActions", safeActions(request.recommendedActions()));
        attributes.put("eventPayloadPolicy", PAYLOAD_POLICY);
        /*
         * 注意：这里返回 unmodifiableMap，而不是 Map.copyOf。
         * Map.copyOf 不允许 null value，但当前低敏 receipt 中 auditId、toolCode、payloadKey 等字段在异常任务里
         * 可能为空。保留 null 能表达“该证据缺失”，对排查坏任务比静默删除字段更有价值。
         */
        return Collections.unmodifiableMap(attributes);
    }

    private void validateRequest(AgentToolActionControlledDryRunReceiptRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "受控工具动作 dry-run receipt 请求体不能为空");
        }
        requireText(request.commandId(), "commandId");
        requireText(request.outcome(), "outcome");
        if (Boolean.TRUE.equals(request.sideEffectExecuted())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "dry-run receipt 不能声明 sideEffectExecuted=true");
        }
        if (looksSensitive(request.message())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "dry-run receipt message 疑似包含 SQL、prompt、token 或密码片段，已拒绝写入 timeline");
        }
    }

    private void validatePath(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
    }

    private String identityKey(String runId, AgentToolActionControlledDryRunReceiptRequest request) {
        String explicitKey = trimToNull(request.idempotencyKey());
        if (explicitKey != null && !looksSensitive(explicitKey)) {
            return "controlled-tool-action-receipt:" + explicitKey;
        }
        return "controlled-tool-action-receipt:" + runId + ":"
                + safeShortText(request.commandId(), "unknown-command", 120) + ":"
                + safeShortText(request.outcome(), "UNKNOWN", 80) + ":"
                + (request.taskRunId() == null ? "no-task-run" : request.taskRunId());
    }

    private String stage(String outcome) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "controlled_tool_action_precheck_failed";
            case "DEFERRED_WAITING_PAYLOAD_BODY" -> "controlled_tool_action_waiting_payload_body";
            case "DEFERRED_READY_FOR_EXECUTOR" -> "controlled_tool_action_waiting_executor";
            case "CAPACITY_LIMITED" -> "controlled_tool_action_capacity_limited";
            default -> "controlled_tool_action_dry_run_recorded";
        };
    }

    private String severity(String outcome, Boolean preCheckPassed) {
        if ("FAILED_PRECHECK".equals(outcome)) {
            return "error";
        }
        if (Boolean.TRUE.equals(preCheckPassed)) {
            return "audit";
        }
        return "info";
    }

    private String errorCode(String outcome) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED";
            case "DEFERRED_WAITING_PAYLOAD_BODY" -> "AGENT_TOOL_ACTION_CONTROLLED_WAITING_PAYLOAD_BODY";
            case "DEFERRED_READY_FOR_EXECUTOR" -> "AGENT_TOOL_ACTION_CONTROLLED_WAITING_EXECUTOR";
            case "CAPACITY_LIMITED" -> "AGENT_TOOL_ACTION_CONTROLLED_CAPACITY_LIMITED";
            default -> "AGENT_TOOL_ACTION_CONTROLLED_DRY_RUN_RECORDED";
        };
    }

    private String safeMessage(String message, String outcome) {
        String fallback = switch (outcome) {
            case "FAILED_PRECHECK" -> "受控工具动作 dry-run 前置复核失败，真实工具副作用已被阻断。";
            case "DEFERRED_WAITING_PAYLOAD_BODY" -> "受控工具动作 dry-run 已通过低敏证据复核，等待 payload body 物化。";
            case "DEFERRED_READY_FOR_EXECUTOR" -> "受控工具动作 dry-run 已通过，等待专用 executor 开放真实执行。";
            default -> "受控工具动作 dry-run receipt 已记录。";
        };
        return safeShortText(message, fallback, MAX_MESSAGE_LENGTH);
    }

    private List<String> safeActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(action -> action != null && !action.isBlank())
                .filter(action -> !looksSensitive(action))
                .limit(MAX_ACTION_COUNT)
                .map(action -> safeShortText(action, null, MAX_ACTION_LENGTH))
                .toList();
    }

    private String requireText(String value, String fieldName) {
        String text = trimToNull(value);
        if (text == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return text;
    }

    private String safeShortText(String value, String fallback, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            return fallback;
        }
        if (looksSensitive(text)) {
            return fallback == null ? "REDACTED_SENSITIVE_TEXT" : fallback;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stringValue(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private boolean looksSensitive(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("prompt:")
                || lower.contains("api_key")
                || lower.contains("apikey");
    }
}
