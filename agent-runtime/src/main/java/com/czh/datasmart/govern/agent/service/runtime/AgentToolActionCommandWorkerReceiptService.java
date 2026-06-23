/**
 * @Author : Cui
 * @Date: 2026/06/23 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 受控命令 worker 回执接收服务。
 *
 * <p>本服务是 command outbox 小闭环中的“事实落点”：当 worker 完成服务端复核、跳过执行、
 * 执行成功、执行失败或进入补偿态时，它把低敏摘要写回 agent-runtime。agent-runtime 再把该摘要
 * 写入 runtime event projection，并同步物化到 worker receipt index，供恢复事实包、timeline、
 * 审计查询和后续 A2A/MCP 工具执行治理复用。</p>
 *
 * <p>这里刻意不实现真实 shell runner，也不读取 payloadReference。worker 回执只相信 worker 已经完成的
 * “低敏事实声明”，并对声明做二次合同校验。这样项目可以先完成 proposal -> safety-precheck -> outbox
 * -> worker precheck -> receipt 的最小闭环，同时避免在沙箱、环境变量过滤、stdout/stderr 裁剪、
 * 产物二次鉴权还没有完全落地前开放不可控副作用。</p>
 */
@Service
@Slf4j
public class AgentToolActionCommandWorkerReceiptService {

    public static final String SCHEMA_VERSION = "datasmart.agent-runtime.tool-action-command-worker-receipt.v1";
    public static final String SOURCE = "TASK_MANAGEMENT_WORKER";
    public static final String EVENT_TYPE = "agent.tool_execution.command_worker_receipt_recorded";
    public static final String PAYLOAD_POLICY =
            "SUMMARY_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY";

    private static final String ALLOW_CONTROLLED_EXECUTION = "ALLOW_CONTROLLED_EXECUTION";
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_ACTION_COUNT = 6;
    private static final int MAX_ACTION_LENGTH = 240;
    private static final int MAX_ISSUE_CODE_COUNT = 12;

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentToolActionWorkerReceiptIndexService receiptIndexService;

    @Autowired
    public AgentToolActionCommandWorkerReceiptService(AgentRuntimeEventProjectionStore projectionStore,
                                                      AgentToolActionWorkerReceiptIndexService receiptIndexService) {
        this.projectionStore = projectionStore;
        this.receiptIndexService = receiptIndexService;
    }

    /**
     * 兼容单元测试的轻量构造器。
     *
     * <p>生产环境通过 Spring 注入共享的 receiptIndexService；单元测试只关注 projection 写入时，
     * 可以使用内存索引，避免每个测试都手工创建完整依赖图。</p>
     */
    public AgentToolActionCommandWorkerReceiptService(AgentRuntimeEventProjectionStore projectionStore) {
        this(
                projectionStore,
                new AgentToolActionWorkerReceiptIndexService(
                        new InMemoryAgentToolActionWorkerReceiptIndexStore(10000)
                )
        );
    }

    /**
     * 接收并投影一条 command worker 回执。
     *
     * @param sessionId 路由中的 Agent 会话 ID，用于 timeline 聚合。
     * @param runId 路由中的 Agent Run ID，用于 timeline 聚合和幂等键兜底。
     * @param traceId 调用方透传的 traceId，用于跨服务排障。
     * @param request worker 写回的低敏回执请求。
     * @return 接收结果；重复幂等键被视为 accepted + duplicate，而不是错误。
     */
    public AgentToolActionCommandWorkerReceiptResponse receive(String sessionId,
                                                               String runId,
                                                               String traceId,
                                                               AgentToolActionCommandWorkerReceiptRequest request) {
        validatePath(sessionId, "sessionId");
        validatePath(runId, "runId");
        validateRequest(request);
        String normalizedOutcome = normalizeUpper(requireText(request.outcome(), "outcome"));
        AgentRuntimeEventProjectionRecord record = toProjectionRecord(
                sessionId.trim(),
                runId.trim(),
                trimToNull(traceId),
                request,
                normalizedOutcome
        );
        boolean appended = projectionStore.append(record);
        materializeReceiptIndex(record);
        return new AgentToolActionCommandWorkerReceiptResponse(
                true,
                !appended,
                record.identityKey(),
                EVENT_TYPE,
                normalizedOutcome,
                Boolean.TRUE.equals(request.sideEffectExecuted()),
                appended ? "受控命令 worker 回执已写入 runtime event timeline"
                        : "受控命令 worker 回执已存在，本次按幂等重复处理"
        );
    }

    private void materializeReceiptIndex(AgentRuntimeEventProjectionRecord record) {
        /*
         * append(...) 会在存储层为记录补齐 replaySequence。record 本身不可变，所以索引物化时优先读取
         * 已保存的版本，保证 receipt index 与 HTTP replay / WebSocket replay 使用同一条 Java 控制面游标。
         */
        AgentRuntimeEventProjectionRecord storedRecord = projectionStore.findByIdentityKey(record.identityKey())
                .orElse(record);
        try {
            receiptIndexService.materialize(storedRecord);
        } catch (RuntimeException exception) {
            /*
             * projection 已经代表“回执被接收”的事实，索引物化失败不应反向删除事实。
             * 当前阶段先记录低敏告警；后续 MySQL durable index 上线后，可以引入 fail-closed 配置。
             */
            log.warn("command worker receipt 低敏索引物化失败，identityKey={}, runId={}, sessionId={}, error={}",
                    storedRecord.identityKey(), storedRecord.runId(), storedRecord.sessionId(), exception.getMessage());
        }
    }

    private AgentRuntimeEventProjectionRecord toProjectionRecord(String sessionId,
                                                                 String runId,
                                                                 String traceId,
                                                                 AgentToolActionCommandWorkerReceiptRequest request,
                                                                 String outcome) {
        Instant now = Instant.now();
        String message = safeMessage(request.message(), outcome);
        return new AgentRuntimeEventProjectionRecord(
                identityKey(runId, request, outcome),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                stage(outcome),
                message,
                severity(outcome, request.preCheckPassed(), request.sideEffectExecuted()),
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

    private Map<String, Object> attributes(AgentToolActionCommandWorkerReceiptRequest request,
                                           String outcome,
                                           String message) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("commandId", safeShortText(request.commandId(), "unknown-command", 180));
        attributes.put("taskId", request.taskId());
        attributes.put("taskRunId", request.taskRunId());
        attributes.put("executorId", safeShortText(request.executorId(), "unknown-executor", 160));
        attributes.put("taskStatus", safeShortText(request.taskStatus(), "UNKNOWN", 80));
        attributes.put("outcome", outcome);
        attributes.put("preCheckPassed", Boolean.TRUE.equals(request.preCheckPassed()));
        attributes.put("sideEffectStarted", Boolean.TRUE.equals(request.sideEffectStarted()));
        attributes.put("sideEffectExecuted", Boolean.TRUE.equals(request.sideEffectExecuted()));
        attributes.put("commandSafetyDecision", safeShortText(request.commandSafetyDecision(), "UNKNOWN", 120));
        attributes.put("commandSafetyPolicyVersion", safeShortText(request.commandSafetyPolicyVersion(), null, 160));
        attributes.put("commandSafetyIssueCodes", safeList(request.commandSafetyIssueCodes(), MAX_ISSUE_CODE_COUNT, 120));
        attributes.put("commandSafetyIssueCodeCount", safeList(request.commandSafetyIssueCodes(),
                MAX_ISSUE_CODE_COUNT, 120).size());
        attributes.put("normalizedTimeoutSeconds", nonNegative(request.normalizedTimeoutSeconds()));
        attributes.put("normalizedOutputByteLimitBytes", nonNegative(request.normalizedOutputByteLimitBytes()));
        attributes.put("artifactReferenceType", safeShortText(request.artifactReferenceType(), null, 80));
        attributes.put("artifactReference", safeArtifactReference(request.artifactReference()));
        attributes.put("artifactAvailable", Boolean.TRUE.equals(request.artifactAvailable()));
        attributes.put("errorCode", safeShortText(request.errorCode(), errorCode(outcome), 160));
        attributes.put("auditId", safeShortText(request.auditId(), null, 200));
        attributes.put("toolCode", safeShortText(request.toolCode(), null, 160));
        attributes.put("targetService", safeShortText(request.targetService(), null, 120));
        attributes.put("workerReceiptMode", safeShortText(request.workerReceiptMode(), mode(outcome), 120));
        attributes.put("message", message);
        attributes.put("recommendedActions", safeActions(request.recommendedActions()));
        attributes.put("eventPayloadPolicy", PAYLOAD_POLICY);
        return Collections.unmodifiableMap(attributes);
    }

    private void validateRequest(AgentToolActionCommandWorkerReceiptRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "受控命令 worker 回执请求体不能为空");
        }
        requireText(request.commandId(), "commandId");
        String outcome = normalizeUpper(requireText(request.outcome(), "outcome"));
        List<String> issueCodes = safeList(request.commandSafetyIssueCodes(), MAX_ISSUE_CODE_COUNT, 120);
        boolean preCheckPassed = Boolean.TRUE.equals(request.preCheckPassed());
        boolean sideEffectStarted = Boolean.TRUE.equals(request.sideEffectStarted());
        boolean sideEffectExecuted = Boolean.TRUE.equals(request.sideEffectExecuted());
        String safetyDecision = normalizeUpper(trimToNull(request.commandSafetyDecision()));

        rejectSensitiveText(request.message(), "message");
        safeActions(request.recommendedActions());
        validateArtifactReference(request.artifactReference(), request.artifactAvailable());

        if (sideEffectExecuted && !sideEffectStarted) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "受控命令 worker 回执声明 sideEffectExecuted=true 时，必须同时声明 sideEffectStarted=true");
        }
        if (sideEffectExecuted && !preCheckPassed) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "受控命令 worker 回执声明副作用已发生时，worker 侧预检必须通过");
        }
        if (sideEffectExecuted && !ALLOW_CONTROLLED_EXECUTION.equals(safetyDecision)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "受控命令 worker 回执声明副作用已发生时，commandSafetyDecision 必须为 ALLOW_CONTROLLED_EXECUTION");
        }
        if (sideEffectExecuted && !issueCodes.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "受控命令 worker 回执声明副作用已发生时，不允许携带仍未关闭的 commandSafetyIssueCodes");
        }
        if ("EXECUTION_SUCCEEDED".equals(outcome) && (!preCheckPassed || !sideEffectStarted || !sideEffectExecuted)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "EXECUTION_SUCCEEDED 必须同时满足 preCheckPassed、sideEffectStarted、sideEffectExecuted");
        }
        if ("FAILED_PRECHECK".equals(outcome) && (preCheckPassed || sideEffectStarted || sideEffectExecuted)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "FAILED_PRECHECK 必须表示执行前被阻断，不能声明预检通过或副作用已开始");
        }
        if ("WORKER_PRECHECK_PASSED".equals(outcome) && (!preCheckPassed || sideEffectStarted || sideEffectExecuted)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "WORKER_PRECHECK_PASSED 只能表示执行前复核通过，不能声明真实副作用已开始");
        }
    }

    private void validateArtifactReference(String artifactReference, Boolean artifactAvailable) {
        String reference = trimToNull(artifactReference);
        if (reference == null) {
            if (Boolean.TRUE.equals(artifactAvailable)) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "artifactAvailable=true 时必须提供低敏 artifactReference");
            }
            return;
        }
        if (safeArtifactReference(reference) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifactReference 只能使用受控低敏 scheme，不能包含 URL、真实路径、路径逃逸或对象正文");
        }
    }

    private String identityKey(String runId, AgentToolActionCommandWorkerReceiptRequest request, String outcome) {
        String explicitKey = trimToNull(request.idempotencyKey());
        if (explicitKey != null && !looksSensitive(explicitKey)) {
            return "command-worker-receipt:" + safeShortText(explicitKey, "unknown-idempotency-key", 220);
        }
        return "command-worker-receipt:" + runId + ":"
                + safeShortText(request.commandId(), "unknown-command", 120) + ":"
                + outcome + ":"
                + (request.taskRunId() == null ? "no-task-run" : request.taskRunId());
    }

    private String stage(String outcome) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "command_worker_precheck_failed";
            case "WORKER_PRECHECK_PASSED" -> "command_worker_precheck_passed";
            case "EXECUTION_SUCCEEDED" -> "command_worker_execution_succeeded";
            case "EXECUTION_FAILED" -> "command_worker_execution_failed";
            case "EXECUTION_SKIPPED" -> "command_worker_execution_skipped";
            case "CAPACITY_LIMITED" -> "command_worker_capacity_limited";
            case "COMPENSATION_REQUIRED" -> "command_worker_compensation_required";
            default -> "command_worker_receipt_recorded";
        };
    }

    private String severity(String outcome, Boolean preCheckPassed, Boolean sideEffectExecuted) {
        if ("EXECUTION_FAILED".equals(outcome) || "COMPENSATION_REQUIRED".equals(outcome)) {
            return "error";
        }
        if ("FAILED_PRECHECK".equals(outcome)) {
            return "warning";
        }
        if (Boolean.TRUE.equals(sideEffectExecuted) || Boolean.TRUE.equals(preCheckPassed)) {
            return "audit";
        }
        return "info";
    }

    private String errorCode(String outcome) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "AGENT_COMMAND_WORKER_PRECHECK_REJECTED";
            case "WORKER_PRECHECK_PASSED" -> "AGENT_COMMAND_WORKER_PRECHECK_PASSED";
            case "EXECUTION_SUCCEEDED" -> "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED";
            case "EXECUTION_FAILED" -> "AGENT_COMMAND_WORKER_EXECUTION_FAILED";
            case "EXECUTION_SKIPPED" -> "AGENT_COMMAND_WORKER_EXECUTION_SKIPPED";
            case "CAPACITY_LIMITED" -> "AGENT_COMMAND_WORKER_CAPACITY_LIMITED";
            case "COMPENSATION_REQUIRED" -> "AGENT_COMMAND_WORKER_COMPENSATION_REQUIRED";
            default -> "AGENT_COMMAND_WORKER_RECEIPT_RECORDED";
        };
    }

    private String mode(String outcome) {
        return switch (outcome) {
            case "WORKER_PRECHECK_PASSED", "FAILED_PRECHECK" -> "PRECHECK_ONLY";
            case "EXECUTION_SUCCEEDED", "EXECUTION_FAILED" -> "EXECUTION_RESULT";
            case "COMPENSATION_REQUIRED" -> "COMPENSATION_REQUIRED";
            default -> "RECEIPT_SUMMARY";
        };
    }

    private String safeMessage(String message, String outcome) {
        String fallback = switch (outcome) {
            case "FAILED_PRECHECK" -> "受控命令 worker 在执行前复核阶段阻断了命令，未产生真实副作用。";
            case "WORKER_PRECHECK_PASSED" -> "受控命令 worker 侧复核已通过，尚未进入真实副作用执行。";
            case "EXECUTION_SUCCEEDED" -> "受控命令 worker 已完成受控执行，并写回低敏执行成功回执。";
            case "EXECUTION_FAILED" -> "受控命令 worker 执行失败，已写回低敏失败摘要，正文输出不进入 timeline。";
            case "EXECUTION_SKIPPED" -> "受控命令 worker 跳过执行，等待后续调度或人工处理。";
            case "CAPACITY_LIMITED" -> "受控命令 worker 受容量保护限制，暂未进入真实执行。";
            case "COMPENSATION_REQUIRED" -> "受控命令 worker 声明需要补偿处理，请进入任务运维流程。";
            default -> "受控命令 worker 回执已记录。";
        };
        return safeShortText(message, fallback, MAX_MESSAGE_LENGTH);
    }

    private List<String> safeActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(action -> action != null && !action.isBlank())
                .peek(action -> rejectSensitiveText(action, "recommendedActions"))
                .limit(MAX_ACTION_COUNT)
                .map(action -> safeShortText(action, null, MAX_ACTION_LENGTH))
                .toList();
    }

    private List<String> safeList(List<String> values, int maxCount, int maxLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !looksSensitive(value))
                .limit(maxCount)
                .map(value -> safeShortText(value, null, maxLength))
                .toList();
    }

    private void rejectSensitiveText(String value, String fieldName) {
        if (looksSensitive(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "受控命令 worker 回执字段 " + fieldName + " 疑似包含命令、路径、stdout/stderr、SQL、prompt、token 或内部 endpoint，已拒绝写入 timeline");
        }
    }

    private String safeArtifactReference(String value) {
        String reference = trimToNull(value);
        if (reference == null) {
            return null;
        }
        String lower = reference.toLowerCase(Locale.ROOT);
        boolean allowedPrefix = lower.startsWith("agent-artifact:")
                || lower.startsWith("artifact:")
                || lower.startsWith("minio-object:")
                || lower.startsWith("agent-output:")
                || lower.startsWith("command-output:")
                || lower.startsWith("task-artifact:");
        if (!allowedPrefix || looksSensitive(reference)
                || lower.contains("://")
                || lower.contains("..")
                || reference.contains("\\")
                || reference.contains("{")
                || reference.contains("}")
                || reference.contains("\n")
                || reference.contains("\r")) {
            return null;
        }
        return reference.length() <= 220 ? reference : reference.substring(0, 220);
    }

    private String requireText(String value, String fieldName) {
        String text = trimToNull(value);
        if (text == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return text;
    }

    private void validatePath(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
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

    private String normalizeUpper(String value) {
        String text = trimToNull(value);
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
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
                || lower.contains("apikey")
                || lower.contains("commandline")
                || lower.contains("command line")
                || lower.contains("stdout")
                || lower.contains("stderr")
                || lower.contains("workingdirectory")
                || lower.contains("workspace")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }
}
