/**
 * @Author : Cui
 * @Date: 2026/06/28 23:10
 * @Description DataSmart Govern Backend - QualityRemediationTaskCommandSubmissionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionQualityRemediationSubmitRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionQualityRemediationSubmitResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionApprovalConfirmationRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionApprovalConfirmationStatus;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionApprovalConfirmationStore;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadStore;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 质量治理受控命令真实提交服务。
 *
 * <p>本服务是质量治理 Agent 闭环从“可审批”走向“可执行”的第一条真实副作用路径。task-management worker
 * 只需要传入 commandId；agent-runtime 会自己回查 outbox 记录、确认事实和 payload store，然后在服务端内部
 * 恢复低敏治理请求并调用 data-quality `dryRun=false`。</p>
 *
 * <p>为什么真实提交放在 agent-runtime 而不是 task-management：payload body 当前由 Agent Host 管控，task-management
 * 只保存低敏 command envelope。如果让 task-management 直接持有正文，就会破坏“模型/前端/调度器只见引用，Host 才能读正文”
 * 的安全边界。后续 payload store 生产化后，本服务可以继续作为 Host 侧执行网关。</p>
 */
@Service
@RequiredArgsConstructor
public class QualityRemediationTaskCommandSubmissionService {

    private static final String TARGET_SERVICE = "data-quality";
    private static final String TARGET_ENDPOINT = "/quality-rules/remediation-tasks";
    private static final String TOOL_CODE = "quality.remediation.task.draft";
    private static final String COMMAND_TYPE = "AGENT_TOOL_ACTION_CONTROLLED_COMMAND";
    private static final String SUCCESS_OUTCOME = "EXECUTION_SUCCEEDED";
    private static final String NOT_SUBMITTED_OUTCOME = "EXECUTION_SKIPPED";

    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final AgentToolActionPayloadStore payloadStore;
    private final AgentToolActionApprovalConfirmationStore confirmationStore;
    private final QualityRemediationTaskSubmissionRequestBuilder requestBuilder;
    private final AgentRuntimeProperties runtimeProperties;
    private final AgentToolServiceAuthorizationProperties serviceAuthorizationProperties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    /**
     * 本地幂等缓存。
     *
     * <p>它只能保证当前 JVM 内重复调用不会重复创建治理任务，不能替代生产级 durable idempotency。
     * 下一阶段如果要完全商用闭环，应把 commandId -> downstream taskId 写入 MySQL execution fact，
     * 并让 data-quality/task-management 接收稳定幂等键。</p>
     */
    private final Map<String, AgentToolActionQualityRemediationSubmitResponse> completedByCommandId =
            new ConcurrentHashMap<>();

    /**
     * 提交已审批的质量治理受控命令。
     *
     * @param commandId outbox commandId，来自 task-management 任务 params。
     * @param request worker 低敏调用上下文。
     * @param traceId 跨服务 traceId。
     * @return 低敏执行摘要，可直接用于 worker receipt。
     */
    public AgentToolActionQualityRemediationSubmitResponse submit(
            String commandId,
            AgentToolActionQualityRemediationSubmitRequest request,
            String traceId) {
        String safeCommandId = requireText(commandId, "commandId");
        AgentToolActionQualityRemediationSubmitResponse cached = completedByCommandId.get(safeCommandId);
        if (cached != null) {
            return duplicate(cached);
        }
        AgentAsyncTaskCommandOutboxRecord outboxRecord = outboxStore.findByCommandId(safeCommandId)
                .orElseThrow(() -> badRequest("未找到质量治理受控命令 outbox 记录，commandId=" + safeCommandId));
        Map<String, Object> commandPayload = parsePayload(outboxRecord.payloadJson());
        validateOutboxRecord(outboxRecord, commandPayload);
        AgentToolActionPayloadRecord payloadRecord = payloadStore.findByReference(outboxRecord.payloadReference())
                .orElseThrow(() -> badRequest("未找到 command 绑定的 agent-payload 记录，commandId=" + safeCommandId));
        validatePayloadRecord(outboxRecord, commandPayload, payloadRecord);
        AgentToolActionApprovalConfirmationRecord confirmationRecord = confirmationStore
                .findByConfirmationId(text(commandPayload.get("approvalConfirmationId")))
                .orElseThrow(() -> badRequest("未找到 command 绑定的工具动作审批确认事实，commandId=" + safeCommandId));
        validateConfirmationRecord(outboxRecord, commandPayload, payloadRecord, confirmationRecord);

        QualityRemediationTaskDraftRequest submitRequest =
                requestBuilder.build(payloadRecord, commandPayload);
        AgentToolActionQualityRemediationSubmitResponse response =
                submitToDataQuality(outboxRecord, commandPayload, confirmationRecord, submitRequest, request, traceId);
        if (Boolean.TRUE.equals(response.sideEffectExecuted())) {
            completedByCommandId.putIfAbsent(safeCommandId, response);
        }
        return response;
    }

    private void validateOutboxRecord(AgentAsyncTaskCommandOutboxRecord record, Map<String, Object> payload) {
        if (record.status() != AgentAsyncTaskCommandOutboxStatus.PUBLISHED) {
            throw badRequest("质量治理真实提交只接受已投递到 task-management 的 outbox command，当前状态="
                    + record.status());
        }
        if (!COMMAND_TYPE.equals(record.commandType()) || !COMMAND_TYPE.equals(text(payload.get("commandType")))) {
            throw badRequest("质量治理真实提交只支持 AGENT_TOOL_ACTION_CONTROLLED_COMMAND");
        }
        if (!TOOL_CODE.equals(record.toolCode()) || !TOOL_CODE.equals(text(payload.get("toolCode")))) {
            throw badRequest("当前 command 不是 quality.remediation.task.draft，不能提交质量治理任务");
        }
        if (!Boolean.TRUE.equals(booleanValue(payload.get("payloadBodyAvailable")))) {
            throw badRequest("command payload 声明 payloadBodyAvailable=false，不能进入真实提交");
        }
        if (!safeEquals(record.payloadReference(), text(payload.get("payloadReference")))) {
            throw badRequest("outbox payloadReference 与 command payload 不一致");
        }
    }

    private void validatePayloadRecord(AgentAsyncTaskCommandOutboxRecord record,
                                       Map<String, Object> commandPayload,
                                       AgentToolActionPayloadRecord payloadRecord) {
        if (payloadRecord.expired(Instant.now())) {
            throw badRequest("agent-payload 记录已过期，必须重新 dry-run 和审批确认");
        }
        if (!Boolean.TRUE.equals(payloadRecord.payloadBodyAvailable()) || payloadRecord.payloadBody().isEmpty()) {
            throw badRequest("agent-payload body 尚未物化，不能提交 data-quality dryRun=false");
        }
        if (!safeEquals(payloadRecord.payloadReference(), record.payloadReference())
                || !safeEquals(payloadRecord.runId(), record.runId())
                || !safeEquals(payloadRecord.tenantId(), stringValue(record.tenantId()))
                || !safeEquals(payloadRecord.projectId(), stringValue(record.projectId()))
                || !safeEquals(payloadRecord.actorId(), record.actorId())
                || !safeEquals(payloadRecord.toolName(), record.toolCode())
                || !safeEquals(payloadRecord.graphId(), text(commandPayload.get("graphId")))
                || !safeEquals(payloadRecord.contractId(), text(commandPayload.get("contractId")))) {
            throw badRequest("agent-payload 记录与 outbox command 的作用域绑定不一致");
        }
    }

    private void validateConfirmationRecord(AgentAsyncTaskCommandOutboxRecord outboxRecord,
                                            Map<String, Object> commandPayload,
                                            AgentToolActionPayloadRecord payloadRecord,
                                            AgentToolActionApprovalConfirmationRecord confirmationRecord) {
        if (!Boolean.TRUE.equals(confirmationRecord.confirmed())
                || confirmationRecord.status() != AgentToolActionApprovalConfirmationStatus.CONFIRMED) {
            throw badRequest("工具动作审批确认事实不是 CONFIRMED，不能提交真实质量治理任务");
        }
        if (confirmationRecord.expired(Instant.now())) {
            throw badRequest("工具动作审批确认事实已过期，必须重新确认后再提交");
        }
        if (!safeEquals(confirmationRecord.confirmationId(), text(commandPayload.get("approvalConfirmationId")))
                || !safeEquals(confirmationRecord.payloadReference(), payloadRecord.payloadReference())
                || !safeEquals(confirmationRecord.runId(), outboxRecord.runId())
                || !safeEquals(confirmationRecord.tenantId(), stringValue(outboxRecord.tenantId()))
                || !safeEquals(confirmationRecord.projectId(), stringValue(outboxRecord.projectId()))
                || !safeEquals(confirmationRecord.actorId(), outboxRecord.actorId())
                || !safeEquals(confirmationRecord.toolName(), outboxRecord.toolCode())
                || !safeEquals(confirmationRecord.graphId(), text(commandPayload.get("graphId")))
                || !safeEquals(confirmationRecord.contractId(), text(commandPayload.get("contractId")))) {
            throw badRequest("工具动作审批确认事实与 outbox command 或 payload 记录不一致");
        }
        String policyVersion = text(commandPayload.get("policyVersion"));
        if (policyVersion != null && !safeEquals(confirmationRecord.policyVersion(), policyVersion)) {
            throw badRequest("工具动作审批确认事实 policyVersion 与 command payload 不一致");
        }
    }

    private AgentToolActionQualityRemediationSubmitResponse submitToDataQuality(
            AgentAsyncTaskCommandOutboxRecord outboxRecord,
            Map<String, Object> commandPayload,
            AgentToolActionApprovalConfirmationRecord confirmationRecord,
            QualityRemediationTaskDraftRequest submitRequest,
            AgentToolActionQualityRemediationSubmitRequest request,
            String traceId) {
        try {
            Map<String, Object> response = restClientBuilder
                    .baseUrl(resolveBaseUrl())
                    .build()
                    .post()
                    .uri(TARGET_ENDPOINT)
                    .headers(headers -> applyHeaders(headers, outboxRecord, request, traceId))
                    .body(submitRequest)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return toResponse(outboxRecord, commandPayload, confirmationRecord, response);
        } catch (RestClientException exception) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "调用 data-quality 提交质量治理任务失败，commandId=" + outboxRecord.commandId()
                            + ": " + exception.getMessage()
            );
        }
    }

    private AgentToolActionQualityRemediationSubmitResponse toResponse(
            AgentAsyncTaskCommandOutboxRecord record,
            Map<String, Object> commandPayload,
            AgentToolActionApprovalConfirmationRecord confirmationRecord,
            Map<String, Object> response) {
        if (response == null) {
            throw badRequest("data-quality 返回空响应，无法确认质量治理任务是否提交");
        }
        int code = intValue(response.get("code"), 0);
        if (code != 0) {
            return new AgentToolActionQualityRemediationSubmitResponse(
                    true,
                    false,
                    true,
                    false,
                    NOT_SUBMITTED_OUTCOME,
                    record.commandId(),
                    record.payloadReference(),
                    confirmationRecord.confirmationId(),
                    null,
                    null,
                    safeMessage(response.get("message"), "data-quality 拒绝提交质量治理任务"),
                    List.of("DATA_QUALITY_REMEDIATION_SUBMIT_REJECTED"),
                    List.of("检查 data-quality 权限、项目范围、异常范围和 task-management 集成状态。")
            );
        }
        Map<String, Object> data = map(response.get("data"));
        boolean submitted = Boolean.TRUE.equals(booleanValue(data.get("submitted")));
        Long taskId = longValue(data.get("taskId"));
        String taskStatus = text(data.get("taskStatus"));
        return new AgentToolActionQualityRemediationSubmitResponse(
                true,
                false,
                true,
                submitted,
                submitted ? SUCCESS_OUTCOME : NOT_SUBMITTED_OUTCOME,
                record.commandId(),
                record.payloadReference(),
                confirmationRecord.confirmationId(),
                taskId,
                taskStatus,
                submitted
                        ? "质量异常治理任务已通过 Agent Host 受控提交到 data-quality/task-management。"
                        : safeMessage(data.get("message"), "data-quality 未创建真实治理任务。"),
                submitted ? List.of() : List.of("DATA_QUALITY_REMEDIATION_NOT_SUBMITTED"),
                submitted
                        ? List.of("task-management worker 应写回 EXECUTION_SUCCEEDED command receipt，并记录远端 taskId。")
                        : List.of("请检查 data-quality 返回的 warnings、异常数量、项目范围和 task-management 集成配置。")
        );
    }

    private AgentToolActionQualityRemediationSubmitResponse duplicate(
            AgentToolActionQualityRemediationSubmitResponse cached) {
        return new AgentToolActionQualityRemediationSubmitResponse(
                cached.accepted(),
                true,
                cached.sideEffectStarted(),
                cached.sideEffectExecuted(),
                cached.outcome(),
                cached.commandId(),
                cached.payloadReference(),
                cached.confirmationId(),
                cached.taskId(),
                cached.taskStatus(),
                "命中 agent-runtime 本地幂等缓存，未重复调用 data-quality。",
                cached.issueCodes(),
                cached.recommendedActions()
        );
    }

    private void applyHeaders(HttpHeaders headers,
                              AgentAsyncTaskCommandOutboxRecord record,
                              AgentToolActionQualityRemediationSubmitRequest request,
                              String traceId) {
        headers.set(PlatformContextHeaders.TENANT_ID, stringValue(record.tenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID,
                String.valueOf(serviceAuthorizationProperties.getServiceAccountActorId()));
        headers.set(PlatformContextHeaders.ACTOR_ROLE, serviceAuthorizationProperties.getServiceAccountRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.TRACE_ID, firstText(traceId, record.traceId(),
                request == null ? null : request.idempotencyKey()));
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, stringValue(record.projectId()));
    }

    private String resolveBaseUrl() {
        String baseUrl = runtimeProperties.getToolServiceBaseUrls().get(TARGET_SERVICE);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未配置 data-quality 工具服务地址，不能提交质量治理任务"
            );
        }
        return baseUrl;
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception exception) {
            throw badRequest("command payloadJson 不是合法 JSON，不能提交真实质量治理任务");
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private PlatformBusinessException badRequest(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw badRequest(fieldName + " 不能为空");
        }
        return value.trim();
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean safeEquals(String left, String right) {
        String normalizedLeft = text(left);
        String normalizedRight = text(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String safeMessage(Object value, String fallback) {
        String text = text(value);
        if (text == null) {
            return fallback;
        }
        return text.length() <= 300 ? text : text.substring(0, 300);
    }
}
