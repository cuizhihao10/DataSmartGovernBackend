/**
 * @Author : Cui
 * @Date: 2026/06/27 01:24
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackDispatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackSuggestionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateLatestReceiptView;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateReconciliationResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent command 最终态回调投递服务。
 *
 * <p>5.108 阶段已经实现了“只读最终态对账”：根据 worker receipt 判断 command 是否应当成功、失败、
 * 退避或继续等待。本服务把这个建议向前推进半步：在调用方显式请求时，把建议映射成 task-management
 * 已有执行器回调协议，而不是跨库改 task 表。</p>
 *
 * <p>为什么不是后台自动扫描：</p>
 * <p>1. 自动扫描需要独立幂等表、调度租约、失败重试、死信和告警；当前若直接做定时器，容易形成隐蔽副作用；</p>
 * <p>2. 先提供受控 POST 入口，可以让运维台、测试脚本或未来 worker 在明确 commandId 后触发；</p>
 * <p>3. 每次投递前都重新对账，保证不会拿旧响应重复推进任务；</p>
 * <p>4. 真实状态写入仍走 task-management 的 runId/executorId/idempotencyKey 校验。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 只发送低敏 result/errorMessage/reason/checkpoint，不发送命令、stdout/stderr、payload body 或 artifact 正文；</p>
 * <p>2. 响应不返回 task-management baseUrl 或内部 endpoint；</p>
 * <p>3. 对 RUNNING 可见性刷新默认跳过，避免最终态补偿接口制造大量进度噪声；</p>
 * <p>4. 缺少 taskId、taskRunId、executorId 或幂等键时 fail-closed，不猜测、不补造。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentCommandTaskFinalStateCallbackDispatchService {

    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_FINAL_STATE_CALLBACK_DISPATCH_NO_COMMAND_NO_STDIO_NO_PAYLOAD_BODY";

    private static final String TARGET_SERVICE = "task-management";

    private final AgentRuntimeProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final AgentCommandTaskFinalStateReconciliationService reconciliationService;

    /**
     * 对 command 重新对账，并按结果向 task-management 投递一次受控回调。
     *
     * @param request 调用方提交的 command 与投递策略。默认 dryRun=true，避免误推进任务。
     * @param accessContext gateway/permission-admin 透传的访问上下文，用于复用对账服务的数据范围收口。
     * @param traceId 当前链路 ID，会透传给 task-management，便于跨服务排障。
     * @return 低敏投递结果，包含本次重新对账响应和投递层状态。
     */
    public AgentCommandTaskFinalStateCallbackDispatchResponse dispatch(
            AgentCommandTaskFinalStateCallbackDispatchRequest request,
            AgentRuntimeEventQueryAccessContext accessContext,
            String traceId) {
        AgentCommandTaskFinalStateReconciliationResponse reconciliation = reconciliationService.reconcile(
                request.getCommandId(),
                request.getToolCode(),
                request.getTenantId(),
                request.getProjectId(),
                request.getActorId(),
                request.getRunId(),
                request.getSessionId(),
                request.getLimit(),
                accessContext
        );
        CallbackDispatchPlan plan = buildPlan(request, reconciliation);
        if (!plan.dispatchable()) {
            return response(request, reconciliation, plan, false, false, false, null);
        }
        if (isDryRun(request)) {
            return response(request, reconciliation, plan.asDryRun(), false, false, false, null);
        }
        try {
            TaskManagementEnvelope downstream = invokeTaskManagement(plan, reconciliation, traceId);
            boolean accepted = downstream != null && Integer.valueOf(0).equals(downstream.getCode());
            CallbackDispatchPlan deliveredPlan = accepted
                    ? plan.withStatus("DISPATCHED")
                    : plan.withStatus("FAILED_DOWNSTREAM_REJECTED")
                    .withIssue("TASK_MANAGEMENT_REJECTED_FINAL_STATE_CALLBACK")
                    .withAction("检查 task-management 当前 runId/executorId/任务状态是否仍允许该回调。");
            return response(request, reconciliation, deliveredPlan, true, accepted, accepted,
                    accepted ? safeMessage(downstream.getMessage(), "task-management 已接受最终态回调。")
                            : safeMessage(downstream == null ? null : downstream.getMessage(),
                            "task-management 拒绝最终态回调，响应说明已隐藏或为空。"));
        } catch (RestClientException exception) {
            CallbackDispatchPlan failedPlan = plan.withStatus("FAILED_DOWNSTREAM_UNAVAILABLE")
                    .withIssue("TASK_MANAGEMENT_FINAL_STATE_CALLBACK_UNAVAILABLE")
                    .withAction("稍后按相同 commandId 和幂等键重试，或检查 task-management 服务健康与网络策略。");
            return response(request, reconciliation, failedPlan, true, false, false,
                    "task-management 回调调用失败，底层地址、响应片段和网络细节已隐藏。");
        }
    }

    private CallbackDispatchPlan buildPlan(AgentCommandTaskFinalStateCallbackDispatchRequest request,
                                           AgentCommandTaskFinalStateReconciliationResponse reconciliation) {
        AgentCommandTaskFinalStateLatestReceiptView receipt = reconciliation.latestReceipt();
        AgentCommandTaskFinalStateCallbackSuggestionView suggestion = reconciliation.callbackSuggestion();
        List<String> issues = new ArrayList<>(reconciliation.issueCodes());
        List<String> actions = new ArrayList<>(reconciliation.recommendedActions());
        if (!Boolean.TRUE.equals(reconciliation.callbackRecommended()) || suggestion == null) {
            issues.add("FINAL_STATE_RECONCILIATION_DOES_NOT_RECOMMEND_CALLBACK");
            actions.add("继续等待 worker receipt，或由运维人工确认最终态后再触发回调。");
            return CallbackDispatchPlan.skipped("SKIPPED_RECONCILIATION_NOT_READY", issues, actions);
        }
        if (receipt == null) {
            issues.add("FINAL_STATE_LATEST_RECEIPT_MISSING");
            actions.add("先确认 command worker receipt 是否已写入 agent-runtime 索引。");
            return CallbackDispatchPlan.skipped("SKIPPED_RECEIPT_MISSING", issues, actions);
        }
        if (receipt.taskId() == null || receipt.taskRunId() == null || !hasText(receipt.executorId())) {
            issues.add("TASK_CALLBACK_LINK_INCOMPLETE");
            actions.add("补齐 taskId、taskRunId、executorId 后再投递，不能根据 commandId 猜测任务租约。");
            return CallbackDispatchPlan.skipped("SKIPPED_CALLBACK_LINK_INCOMPLETE", issues, actions);
        }
        if (!hasText(suggestion.idempotencyKeyHint())) {
            issues.add("FINAL_STATE_CALLBACK_IDEMPOTENCY_KEY_MISSING");
            actions.add("修复对账建议生成逻辑，确保每次回调都有稳定幂等键。");
            return CallbackDispatchPlan.skipped("SKIPPED_IDEMPOTENCY_KEY_MISSING", issues, actions);
        }
        String status = normalizeStatus(suggestion.callbackStatus());
        if (status == null) {
            return CallbackDispatchPlan.skipped(
                    "SKIPPED_CALLBACK_STATUS_MISSING",
                    append(issues, "FINAL_STATE_CALLBACK_STATUS_MISSING"),
                    append(actions, "等待对账服务补齐 callbackStatus，或由运维人工确认任务应进入的状态。")
            );
        }
        if ("RUNNING".equals(status) && !Boolean.TRUE.equals(request.getIncludeNonTerminalProgressCallback())) {
            issues.add("RUNNING_VISIBILITY_CALLBACK_DISABLED");
            actions.add("如确实需要刷新执行中可见性，请显式开启 includeNonTerminalProgressCallback。");
            return CallbackDispatchPlan.skipped("SKIPPED_RUNNING_VISIBILITY_DISABLED", issues, actions);
        }
        return switch (status) {
            case "SUCCEEDED" -> new CallbackDispatchPlan(
                    true,
                    "READY_TO_DISPATCH",
                    receipt.taskId(),
                    receipt.taskRunId(),
                    receipt.executorId(),
                    status,
                    "TASK_COMPLETE",
                    suggestion.idempotencyKeyHint(),
                    completeBody(receipt, suggestion),
                    List.copyOf(issues),
                    List.copyOf(actions)
            );
            case "FAILED" -> new CallbackDispatchPlan(
                    true,
                    "READY_TO_DISPATCH",
                    receipt.taskId(),
                    receipt.taskRunId(),
                    receipt.executorId(),
                    status,
                    "TASK_FAIL",
                    suggestion.idempotencyKeyHint(),
                    failBody(receipt, suggestion),
                    List.copyOf(issues),
                    List.copyOf(actions)
            );
            case "DEFERRED" -> new CallbackDispatchPlan(
                    true,
                    "READY_TO_DISPATCH",
                    receipt.taskId(),
                    receipt.taskRunId(),
                    receipt.executorId(),
                    status,
                    "TASK_DEFER",
                    suggestion.idempotencyKeyHint(),
                    deferBody(receipt, suggestion, request.getDeferDelaySeconds()),
                    List.copyOf(issues),
                    List.copyOf(actions)
            );
            case "RUNNING" -> new CallbackDispatchPlan(
                    true,
                    "READY_TO_DISPATCH",
                    receipt.taskId(),
                    receipt.taskRunId(),
                    receipt.executorId(),
                    status,
                    "TASK_PROGRESS",
                    suggestion.idempotencyKeyHint(),
                    progressBody(receipt, suggestion),
                    List.copyOf(issues),
                    List.copyOf(actions)
            );
            default -> CallbackDispatchPlan.skipped(
                    "SKIPPED_UNSUPPORTED_CALLBACK_STATUS",
                    append(issues, "UNSUPPORTED_FINAL_STATE_CALLBACK_STATUS_" + safeCode(status)),
                    append(actions, "补充 callbackStatus 到 task-management 执行器协议的映射后再自动投递。")
            );
        };
    }

    private TaskManagementEnvelope invokeTaskManagement(CallbackDispatchPlan plan,
                                                        AgentCommandTaskFinalStateReconciliationResponse reconciliation,
                                                        String traceId) {
        RestClient client = restClientBuilder.baseUrl(resolveTaskManagementBaseUrl()).build();
        return switch (plan.targetOperation()) {
            case "TASK_COMPLETE" -> client.post()
                    .uri("/tasks/{taskId}/complete", plan.taskId())
                    .headers(headers -> applyHeaders(headers, reconciliation, traceId))
                    .body(plan.body())
                    .retrieve()
                    .body(TaskManagementEnvelope.class);
            case "TASK_FAIL" -> client.post()
                    .uri("/tasks/{taskId}/fail", plan.taskId())
                    .headers(headers -> applyHeaders(headers, reconciliation, traceId))
                    .body(plan.body())
                    .retrieve()
                    .body(TaskManagementEnvelope.class);
            case "TASK_DEFER" -> client.post()
                    .uri("/tasks/{taskId}/defer", plan.taskId())
                    .headers(headers -> applyHeaders(headers, reconciliation, traceId))
                    .body(plan.body())
                    .retrieve()
                    .body(TaskManagementEnvelope.class);
            case "TASK_PROGRESS" -> client.post()
                    .uri("/tasks/{taskId}/progress", plan.taskId())
                    .headers(headers -> applyHeaders(headers, reconciliation, traceId))
                    .body(plan.body())
                    .retrieve()
                    .body(TaskManagementEnvelope.class);
            default -> throw new IllegalStateException("不支持的最终态回调操作: " + plan.targetOperation());
        };
    }

    private void applyHeaders(HttpHeaders headers,
                              AgentCommandTaskFinalStateReconciliationResponse reconciliation,
                              String traceId) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (hasText(traceId)) {
            headers.set(PlatformContextHeaders.TRACE_ID, traceId.trim());
        }
        if (hasText(reconciliation.tenantId())) {
            headers.set(PlatformContextHeaders.TENANT_ID, reconciliation.tenantId());
        }
        String actorId = numericText(reconciliation.actorId());
        if (actorId != null) {
            headers.set(PlatformContextHeaders.ACTOR_ID, actorId);
        }
        if (hasText(reconciliation.projectId())) {
            headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
            headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, reconciliation.projectId());
        }
    }

    private String resolveTaskManagementBaseUrl() {
        String baseUrl = properties.getToolServiceBaseUrls().get(TARGET_SERVICE);
        if (!hasText(baseUrl)) {
            throw new RestClientException("task-management baseUrl 未配置");
        }
        return baseUrl;
    }

    private CompleteBody completeBody(AgentCommandTaskFinalStateLatestReceiptView receipt,
                                      AgentCommandTaskFinalStateCallbackSuggestionView suggestion) {
        return new CompleteBody(
                receipt.taskRunId(),
                receipt.executorId(),
                suggestion.idempotencyKeyHint(),
                safeMessage(suggestion.outputSummary(), "受控命令执行成功已由 worker receipt 确认。")
        );
    }

    private FailBody failBody(AgentCommandTaskFinalStateLatestReceiptView receipt,
                              AgentCommandTaskFinalStateCallbackSuggestionView suggestion) {
        return new FailBody(
                receipt.taskRunId(),
                receipt.executorId(),
                suggestion.idempotencyKeyHint(),
                safeMessage(suggestion.callbackMessage(), "受控命令最终态对账建议任务失败。")
        );
    }

    private DeferBody deferBody(AgentCommandTaskFinalStateLatestReceiptView receipt,
                                AgentCommandTaskFinalStateCallbackSuggestionView suggestion,
                                Integer delaySeconds) {
        int appliedDelay = delaySeconds == null ? 60 : Math.min(Math.max(delaySeconds, 1), 3600);
        return new DeferBody(
                receipt.taskRunId(),
                receipt.executorId(),
                suggestion.idempotencyKeyHint(),
                safeMessage(suggestion.callbackMessage(), "受控命令 worker 容量受限，任务延迟回队列。"),
                appliedDelay
        );
    }

    private ProgressBody progressBody(AgentCommandTaskFinalStateLatestReceiptView receipt,
                                      AgentCommandTaskFinalStateCallbackSuggestionView suggestion) {
        return new ProgressBody(
                receipt.taskRunId(),
                receipt.executorId(),
                suggestion.idempotencyKeyHint(),
                50,
                safeMessage(suggestion.callbackMessage(), "受控命令预检通过，任务仍在执行中。")
        );
    }

    private AgentCommandTaskFinalStateCallbackDispatchResponse response(
            AgentCommandTaskFinalStateCallbackDispatchRequest request,
            AgentCommandTaskFinalStateReconciliationResponse reconciliation,
            CallbackDispatchPlan plan,
            boolean dispatchAttempted,
            boolean dispatched,
            boolean downstreamAccepted,
            String downstreamMessage) {
        return new AgentCommandTaskFinalStateCallbackDispatchResponse(
                PAYLOAD_POLICY,
                reconciliation.commandId(),
                isDryRun(request),
                dispatchAttempted,
                dispatched,
                plan.deliveryStatus(),
                plan.taskId(),
                plan.taskRunId(),
                plan.executorId(),
                plan.callbackStatus(),
                plan.targetOperation(),
                plan.idempotencyKey(),
                downstreamAccepted,
                downstreamMessage,
                reconciliation,
                plan.issueCodes(),
                plan.recommendedActions()
        );
    }

    private String normalizeStatus(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isDryRun(AgentCommandTaskFinalStateCallbackDispatchRequest request) {
        return !Boolean.FALSE.equals(request.getDryRun());
    }

    private String safeMessage(String value, String fallback) {
        if (!hasText(value) || looksSensitive(value)) {
            return fallback;
        }
        String text = value.trim();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private String safeCode(String value) {
        if (!hasText(value) || looksSensitive(value)) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
    }

    private String numericText(String value) {
        if (!hasText(value)) {
            return null;
        }
        String text = value.trim();
        try {
            Long.parseLong(text);
            return text;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean looksSensitive(String value) {
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
                || lower.contains("stdout")
                || lower.contains("stderr")
                || lower.contains("commandline")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }

    private List<String> append(List<String> values, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.add(value);
        return List.copyOf(copy);
    }

    /**
     * task-management `ApiResponse` 的本地解析结构。
     *
     * <p>agent-runtime 不直接依赖 task-management DTO，保持两个微服务通过 HTTP JSON 契约耦合。
     * 当前只需要 code/message 判断是否接受回调，data 可能是 Task 对象，但本服务不会读取任务正文。</p>
     */
    @Data
    private static class TaskManagementEnvelope {
        private Integer code;
        private String message;
        private Object data;
    }

    private record CompleteBody(Long runId, String executorId, String idempotencyKey, String result) {}

    private record FailBody(Long runId, String executorId, String idempotencyKey, String errorMessage) {}

    private record DeferBody(Long runId, String executorId, String idempotencyKey, String reason, Integer delaySeconds) {}

    private record ProgressBody(Long runId, String executorId, String idempotencyKey, Integer progress, String checkpoint) {}
}
