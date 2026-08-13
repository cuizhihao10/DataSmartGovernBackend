/**
 * @Author : Cui
 * @Date: 2026/08/11 20:25
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryDataSyncClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.service.tool.AgentToolDownstreamHttpSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent-runtime 调用 data-sync Autopilot case API 和失败对象重试 API 的受控客户端。
 *
 * <p>case decision/transition 使用内部服务令牌；真正的 retry 同时透传原用户与 Agent delegation，
 * 让 data-sync 按普通业务接口再次执行 RBAC、租户和资源归属校验。客户端不提供任意 URL、任意方法
 * 或任意 body 的通用出口，避免 Autopilot 变成内网 HTTP 代理。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAutopilotRecoveryDataSyncClient {

    private static final String DATA_SYNC = "data-sync";
    private static final String DECISION_PATH = "/internal/data-sync/autopilot/recovery/decisions";
    private static final String TRANSITION_PATH =
            "/internal/data-sync/autopilot/recovery/cases/{caseId}/transitions";
    private static final String TRIGGER_RESULT_PATH =
            "/internal/data-sync/autopilot/recovery/triggers/{eventId}/results";
    private static final String TRIGGER_DEAD_LETTER_PATH =
            "/internal/data-sync/autopilot/recovery/triggers/{eventId}/dead-letter";
    private static final String QUARANTINE_APPLY_PATH =
            "/internal/data-sync/autopilot/recovery/cases/{caseId}/quarantine/apply";
    private static final String RETRY_PATH =
            "/sync-tasks/{taskId}/executions/{executionId}/objects/retry";

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;

    /**
     * Writes the final handling outcome for one readable Kafka trigger back to data-sync.
     *
     * <p>The input is the low-sensitive result that the consumer is ready to acknowledge, and the output is
     * {@code void}: data-sync is the durable owner of the trigger-result receipt. This method deliberately sends
     * only the internal service token. A verifier rejection can happen before the event has a trustworthy session,
     * run, or delegation, so attaching user-delegation headers here would either be impossible or would let an
     * unverified event influence an authorization boundary. The fixed URL and the four fixed body fields also keep
     * this callback from becoming a generic internal HTTP proxy.</p>
     *
     * <p>A normal return means data-sync accepted a successful platform envelope and the consumer may commit the
     * Kafka offset. HTTP failures, missing envelopes, non-success envelope codes, and malformed local result data
     * are all infrastructure or contract failures. They are intentionally surfaced as runtime exceptions so the
     * listener's bounded retry and DLT policy can preserve the unrecorded terminal result for later handling.</p>
     *
     * @param result low-sensitive terminal result for a parsed Autopilot recovery trigger
     * @throws IllegalStateException when the local result or data-sync response violates the callback contract
     */
    public void recordTriggerResult(AgentAutopilotRecoveryExecutionResult result) {
        if (result == null) {
            throw invalidDataSyncContract("AUTOPILOT_TRIGGER_RESULT_INVALID");
        }
        String eventId = requiredResultText(result.eventId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", requiredResultText(result.status()));
        body.put("reasonCode", requiredResultText(result.reasonCode()));
        body.put("caseId", result.caseId());
        body.put("currentExecutionId", result.currentExecutionId());
        body.put("retrievalDecision", result.retrievalDecision());
        body.put("retrievalStrategy", result.retrievalStrategy());
        body.put("retrievalEvidenceCount", result.retrievalEvidenceCount());
        body.put("retrievalEvidenceDigest", result.retrievalEvidenceDigest());

        RestClient client = httpSupport.serviceClient(restClientBuilder, DATA_SYNC);
        Map<String, Object> response = client.post()
                .uri(TRIGGER_RESULT_PATH, eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(httpSupport::applyInternalServiceToken)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (!successfulPlatformEnvelope(response)) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_TRIGGER_RESULT_ENVELOPE_INVALID");
        }
    }

    /**
     * Asks data-sync to durably converge a trigger whose bounded Kafka delivery reached the dead-letter topic.
     *
     * <p>The request contains only the event ID in a fixed URL and the original current execution ID in the body.
     * It intentionally sends no represented-user or Agent delegation headers: a DLT handler is reporting transport
     * exhaustion, while data-sync reloads the authoritative outbox, decision receipt, case scope, and optimistic
     * version. The internal service token authenticates the caller without turning broker payload fields into
     * transition authority.</p>
     *
     * <p>A successful platform envelope proves that the DLT fact is now queryable in data-sync. Every malformed
     * local identity, HTTP failure, or non-success envelope remains an exception so Spring Kafka's
     * {@code DltStrategy.FAIL_ON_ERROR} does not commit an unconverged dead-letter record.</p>
     *
     * @param eventId verified event identity parsed from the original trigger payload
     * @param currentExecutionId positive execution identity carried by that trigger
     */
    public void recordTriggerDeadLettered(String eventId, Long currentExecutionId) {
        String requiredEventId = requiredResultText(eventId);
        if (currentExecutionId == null || currentExecutionId <= 0) {
            throw invalidDataSyncContract("AUTOPILOT_TRIGGER_DEAD_LETTER_IDENTITY_INVALID");
        }
        Map<String, Object> response = httpSupport.serviceClient(restClientBuilder, DATA_SYNC)
                .post()
                .uri(TRIGGER_DEAD_LETTER_PATH, requiredEventId)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(httpSupport::applyInternalServiceToken)
                .body(Map.of("currentExecutionId", currentExecutionId))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (!successfulPlatformEnvelope(response)) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_DEAD_LETTER_ENVELOPE_INVALID");
        }
    }

    /**
     * 将 Java 证据校验后的候选交给 data-sync 做第二次本地策略评估，并持久化 recovery case 决策。
     *
     * <p>输入是可信触发器、Python 的低敏候选和 Java 证据校验结果；输出是 data-sync 返回的带版本 case 视图。
     * 方法会向固定内部路径发起一次 POST，并写入远端 case，因此具有网络和持久化副作用。它同时传递用户
     * delegation 和内部服务令牌：前者让 data-sync 重新执行租户、用户和资源 RBAC，后者只证明这是受控的
     * 服务间调用，二者缺一不可。</p>
     *
     * <p>{@code receiptId} 固定为 {@code eventId:decision}，使 data-sync 可以把同一 Kafka 事件的重复决策
     * 识别为同一业务事实。返回的 {@code state} 是第二道策略的权威结论，调用方不能只凭 Java 的
     * {@code AUTO_APPROVED} 绕过它。下游 HTTP 错误直接传播；响应没有可用 {@code data} 时抛出稳定的
     * 技术合同异常，而不是猜测成功。</p>
     *
     * @param trigger 已验证身份、授权范围和恢复时限的触发器
     * @param response 已由 Python 返回但尚未在 data-sync 落案的低敏候选
     * @param evidenceVerified Java 是否已复算并验证候选证据
     * @return data-sync 持久化后的 case 视图及其乐观锁版本
     * @throws IllegalStateException when the data-sync response contract lacks valid case data
     */
    public AgentAutopilotRecoveryCaseView recordDecision(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response,
            boolean evidenceVerified) {
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", event.tenantId());
        body.put("projectId", event.projectId());
        body.put("syncTaskId", event.syncTaskId());
        body.put("rootExecutionId", event.rootExecutionId());
        body.put("currentExecutionId", event.currentExecutionId());
        body.put("cycle", event.cycle());
        body.put("deadlineAt", trigger.deadlineAt().toString());
        body.put("errorFingerprint", event.errorFingerprint());
        body.put("repeatedErrorCount", event.repeatedErrorCount());
        body.put("action", response.action());
        body.put("riskLevel", response.riskLevel());
        body.put("repairFingerprint", response.repairFingerprint());
        body.put("receiptId", event.eventId() + ":decision");
        body.put("confidenceScore", (int) Math.round(response.confidence() * 100.0d));
        body.put("evidenceAvailable", evidenceVerified && response.evidenceAvailable());
        // 这些事实只是范围受限的传输投影。data-sync 在认定 RETRY_EXECUTION 已获自动授权前，
        // 仍必须重新读取自身的执行和错误账本。
        body.put("autopilotRecoveryFacts", response.autopilotRecoveryFacts());
        Map<String, Object> data = postEnvelope(
                trigger, DECISION_PATH, body, event.eventId(), new Object[0]);
        return caseView(data);
    }

    /**
     * 使用 data-sync 返回的乐观锁版本，推进 recovery case 的一条生命周期边。
     *
     * <p>输入包含可信触发器、上一步 case 视图、目标 receipt 类型和固定后缀；输出是远端接受迁移后的新视图。
     * 该调用会写入远端状态，并以用户 delegation 加内部令牌再次接受 data-sync 的权限和资源归属检查。
     * {@code expectedVersion} 防止过期调用覆盖新状态，{@code eventId:receiptSuffix} 则把同一事件、同一边的
     * 重复提交交给 data-sync 的持久 receipt 去重。</p>
     *
     * <p>这里不吞掉冲突：缺少 case 标识或版本、服务端拒绝过期状态、HTTP 失败和无效响应都会让调用方获得异常。
     * 执行编排层据此决定是否降级为人工关注，而不是把未记录的副作用误报为成功。</p>
     *
     * @param trigger 已验证的恢复触发器
     * @param recoveryCase 上一步 data-sync case，必须含 {@code caseId} 和 {@code version}
     * @param receiptType 要记录的状态迁移类型，例如 {@code RECOVERY_STARTED}
     * @param receiptSuffix 参与幂等 receiptId 的固定低敏后缀，例如 {@code started} 或 {@code failed}
     * @param attentionReason 需要人工关注时的稳定原因码；不需要时可为空
     * @return 推进后包含新版本的 case 视图
     * @throws IllegalStateException when the case lacks optimistic-lock data or the response contract is invalid
     */
    public AgentAutopilotRecoveryCaseView recordTransition(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryCaseView recoveryCase,
            String receiptType,
            String receiptSuffix,
            String attentionReason) {
        if (recoveryCase == null || recoveryCase.caseId() == null || recoveryCase.version() == null) {
            throw invalidDataSyncContract("AUTOPILOT_RECOVERY_CASE_VERSION_MISSING");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", recoveryCase.version());
        body.put("receiptId", trigger.event().eventId() + ":" + receiptSuffix);
        body.put("receiptType", receiptType);
        body.put("currentExecutionId", trigger.event().currentExecutionId());
        body.put("cycle", trigger.event().cycle());
        body.put("errorFingerprint", trigger.event().errorFingerprint());
        body.put("repeatedErrorCount", trigger.event().repeatedErrorCount());
        body.put("attentionReason", attentionReason);
        Map<String, Object> data = postEnvelope(
                trigger,
                TRANSITION_PATH,
                body,
                trigger.event().eventId(),
                recoveryCase.caseId());
        return caseView(data);
    }

    /**
     * Applies the exact quarantine preview that passed Java verification and data-sync policy approval.
     *
     * <p>The method exposes no arbitrary tool, URL, selector, or reason. It binds one fixed internal request to the
     * persisted case version, original tenant/project/task/execution/cycle, current authorization and policy digests,
     * Java-validated preview digest and IDs, and the canonical action fingerprint. The stable
     * {@code eventId:quarantine-apply} receipt lets data-sync replay a committed result when an HTTP response was
     * lost, while rejecting reuse with changed facts.</p>
     *
     * <p>User/Agent delegation headers and the internal service token are both sent by {@link #postEnvelope}. The
     * downstream service therefore rechecks the represented user's resource scope and the service-to-service source
     * before changing any error-sample state. This method does not treat HTTP success alone as proof: it requires a
     * response bound to the expected case and receipt. The caller separately requires the durable
     * {@code COMPLETED/APPLIED} state before starting retry.</p>
     *
     * @param trigger verified initial authorization and recovery-event scope
     * @param recoveryCase data-sync case currently approved for the apply action
     * @param response planner candidate containing the canonical repair fingerprint
     * @param preview Java-validated preview digest and exact selected sample IDs
     * @return durable apply receipt bound to this event and recovery case
     * @throws IllegalStateException when local inputs or the downstream receipt violate the fixed contract
     */
    public AgentAutopilotRecoveryQuarantineApplyReceipt applyAutonomousQuarantine(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryCaseView recoveryCase,
            AgentAutopilotRecoveryPlanResponse response,
            AgentAutopilotRecoveryQuarantinePreview preview) {
        if (trigger == null || recoveryCase == null || response == null || preview == null
                || recoveryCase.caseId() == null || recoveryCase.version() == null) {
            throw invalidDataSyncContract("AUTOPILOT_QUARANTINE_APPLY_INPUT_INVALID");
        }
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        String receiptId = requiredResultText(event.eventId()) + ":quarantine-apply";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", recoveryCase.version());
        body.put("tenantId", event.tenantId());
        body.put("projectId", event.projectId());
        body.put("syncTaskId", event.syncTaskId());
        body.put("executionId", event.currentExecutionId());
        body.put("cycle", event.cycle());
        body.put("authorizationDigest", recoveryCase.authorizationDigest());
        body.put("policyDigest", recoveryCase.policyDigest());
        body.put("previewDigest", preview.confirmationDigest());
        body.put("selectedSampleIds", preview.selectedSampleIds());
        body.put("actionFingerprint", response.repairFingerprint());
        body.put("receiptId", receiptId);

        Map<String, Object> data = postEnvelope(
                trigger, QUARANTINE_APPLY_PATH, body, event.eventId(), recoveryCase.caseId());
        AgentAutopilotRecoveryQuarantineApplyReceipt receipt =
                new AgentAutopilotRecoveryQuarantineApplyReceipt(
                        requiredText(data, "receiptId"),
                        requiredLong(data, "caseId"),
                        requiredLong(data, "syncTaskId"),
                        requiredLong(data, "executionId"),
                        requiredInt(data, "selectedCount"),
                        requiredInt(data, "affectedCount"),
                        requiredText(data, "operationState"),
                        requiredText(data, "receiptState"),
                        requiredText(data, "previewDigest"),
                        requiredText(data, "actionFingerprint"));
        if (!receiptId.equals(receipt.receiptId())
                || !recoveryCase.caseId().equals(receipt.caseId())
                || !event.syncTaskId().equals(receipt.syncTaskId())
                || !event.currentExecutionId().equals(receipt.executionId())
                || receipt.selectedCount() != preview.selectedSampleIds().size()
                || !preview.confirmationDigest().equals(receipt.previewDigest())
                || !response.repairFingerprint().equals(receipt.actionFingerprint())) {
            throw invalidDataSyncContract("AUTOPILOT_QUARANTINE_APPLY_RECEIPT_SCOPE_MISMATCH");
        }
        return receipt;
    }

    /**
     * 请求 data-sync 在当前 execution 范围内重新排队全部允许重试的失败对象。
     *
     * <p>输入只有可信触发器，输出为 data-sync 的低层响应字段。方法会产生一次远端重试请求，但不会接受任意
     * 对象 ID 或任意 URL；固定路径和 {@code retryAttemptBudget=1} 把本轮副作用限制在事件所属 execution
     * 的 FAILED 对象中。用户 delegation 让 data-sync 再次检查任务与执行权限，内部令牌限制调用来源。</p>
     *
     * <p>请求把稳定 {@code eventId} 同时作为 data-sync 的 {@code idempotencyKey}。如果 data-sync 已经提交
     * 对象重置但 HTTP 响应丢失，Agent Runtime 使用同一事件重试时会回放首次结果，而不会第二次执行控制面
     * 副作用。下游拒绝、网络失败或响应合同错误仍会向上层传播，执行服务只进行有界重试。</p>
     *
     * @param trigger 已验证且绑定当前同步任务与 execution 的触发器
     * @return 已校验并绑定原任务/执行范围的强类型重排队回执
     * @throws IllegalStateException when the downstream response lacks parseable case data
     */
    public AgentAutopilotRecoveryRetryReceipt retryFailedObjects(
            AgentAutopilotVerifiedRecoveryTrigger trigger) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", trigger.event().eventId());
        body.put("retryAttemptBudget", 1);
        body.put("resetAttemptCount", true);
        body.put("reason", "AUTOPILOT_PREAUTHORIZED_FAILED_OBJECT_RETRY");
        Map<String, Object> data = postEnvelope(
                trigger,
                RETRY_PATH,
                body,
                trigger.event().eventId(),
                trigger.event().syncTaskId(),
                trigger.event().currentExecutionId());
        AgentAutopilotRecoveryRetryReceipt receipt = new AgentAutopilotRecoveryRetryReceipt(
                requiredLong(data, "taskId"),
                requiredLong(data, "executionId"),
                requiredInt(data, "retryObjectCount"),
                requiredText(data, "executionState"),
                requiredText(data, "taskState"));
        if (!receipt.matchesRequeuedScope(trigger.event())) {
            throw invalidDataSyncContract("AUTOPILOT_RETRY_RECEIPT_SCOPE_OR_STATE_INVALID");
        }
        return receipt;
    }

    /**
     * 向固定 data-sync 路径发送 POST，附加双主体认证头，并解包平台响应的 {@code data} 字段。
     *
     * <p>输入路径仅来自本类常量，URI 变量和请求体也由受控调用方构造，因而该方法不是通用内网 HTTP 代理。
     * 输出为复制后的 {@code data} Map；发起 HTTP 调用是唯一副作用。它同时应用用户 delegation 和内部服务
     * 令牌，分别用于 data-sync 的业务 RBAC/作用域验证和服务间来源验证。</p>
     *
     * <p>{@code traceId} 用于跨服务证据和日志关联，但不单独构成幂等键；需要去重的调用必须在 {@code body}
     * 中传入稳定 receipt。HTTP 非成功状态按 {@link RestClient} 语义传播；空响应或没有 Map 形式
     * {@code data} 的响应转换为技术合同异常，避免下游合同漂移被当作空成功。</p>
     *
     * @param trigger 已验证的身份和授权上下文
     * @param path 受控的 data-sync 相对路径
     * @param body 要发送的低敏请求体
     * @param traceId 用于跨服务关联的事件标识
     * @param uriVariables 固定路径模板所需的 URI 变量
     * @return 下游平台 envelope 中复制出的 {@code data} Map
     * @throws IllegalStateException when the response envelope cannot be safely unwrapped
     */
    private Map<String, Object> postEnvelope(AgentAutopilotVerifiedRecoveryTrigger trigger,
                                             String path,
                                             Map<String, Object> body,
                                             String traceId,
                                             Object... uriVariables) {
        RestClient client = httpSupport.serviceClient(restClientBuilder, DATA_SYNC);
        Map<String, Object> response = client.post()
                .uri(path, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    httpSupport.applyUserDelegationHeaders(
                            headers, trigger.session(), trigger.rootRun(), traceId);
                    httpSupport.applyInternalServiceToken(headers);
                })
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (!successfulPlatformEnvelope(response)
                || !(response.get("data") instanceof Map<?, ?> rawData)) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_RESPONSE_INVALID");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        rawData.forEach((key, value) -> data.put(String.valueOf(key), value));
        return data;
    }

    /**
     * 将已解包的平台 case 数据转换为强类型的乐观锁视图。
     *
     * <p>输入是 data-sync 的 {@code data} 字段，输出保留执行范围、策略结论、循环信息和版本号。该纯转换
     * 没有权限或网络副作用，却不能放松任何合同字段：缺少或格式错误的字段会终止当前恢复，而不是构造带空值
     * 的 case。版本和 {@code policyDigest} 是后续迁移与审计证据，不能被调用方自行补造。</p>
     *
     * @param data 已由平台 envelope 解包的 case 字段
     * @return 可用于下一次乐观锁迁移的强类型 case 视图
     * @throws IllegalStateException when a required response field is missing or malformed
     */
    private AgentAutopilotRecoveryCaseView caseView(Map<String, Object> data) {
        return new AgentAutopilotRecoveryCaseView(
                requiredLong(data, "caseId"),
                requiredLong(data, "syncTaskId"),
                requiredLong(data, "rootExecutionId"),
                requiredLong(data, "currentExecutionId"),
                requiredText(data, "state"),
                requiredLong(data, "version"),
                requiredInt(data, "cycle"),
                requiredInt(data, "maxCycles"),
                requiredText(data, "recoveryAction"),
                optionalText(data.get("attentionReason")),
                requiredText(data, "authorizationDigest"),
                requiredText(data, "policyDigest")
        );
    }

    /**
     * 从下游响应读取一个必填的长整数字段。
     *
     * <p>输入 Map 不会被修改，输出为解析后的 {@link Long}。该方法不检查权限也不产生副作用；它只把响应
     * 合同错误统一为稳定原因码，以免不可信或损坏的 case 数据参与幂等版本比较。</p>
     *
     * @param data 下游响应字段
     * @param field 必须存在的字段名
     * @return 解析成功的长整数字段
     * @throws IllegalStateException when the response field is missing or not a valid integer
     */
    private Long requiredLong(Map<String, Object> data, String field) {
        try {
            return Long.parseLong(requiredText(data, field));
        } catch (NumberFormatException exception) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_NUMERIC_FIELD_INVALID");
        }
    }

    /**
     * 从下游响应读取一个必须落在 Java {@code int} 范围内的整数字段。
     *
     * <p>该纯函数复用 {@link #requiredLong(Map, String)} 的缺失和格式验证，再拒绝溢出的值。循环数等字段
     * 是恢复预算和证据的一部分，宁可将异常合同视为冲突，也不截断后继续执行。</p>
     *
     * @param data 下游响应字段
     * @param field 必须存在的字段名
     * @return 已验证且未溢出的整数
     * @throws IllegalStateException when a response integer is missing, malformed, or outside the Java int range
     */
    private Integer requiredInt(Map<String, Object> data, String field) {
        long value = requiredLong(data, field);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_NUMERIC_FIELD_INVALID");
        }
        return (int) value;
    }

    /**
     * 从下游响应读取一个不能为空白的文本字段。
     *
     * <p>输入只读，输出会去除首尾空白；没有副作用或权限判断。状态、动作和摘要等文本会影响策略和审计，
     * 所以空值不被默认为未知或成功，而是以稳定冲突异常终止流程。</p>
     *
     * @param data 下游响应字段
     * @param field 必须存在的字段名
     * @return 非空的已清理文本
     * @throws IllegalStateException when a required response text field is absent or blank
     */
    private String requiredText(Map<String, Object> data, String field) {
        String value = optionalText(data.get(field));
        if (value == null) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_FIELD_MISSING");
        }
        return value;
    }

    /**
     * 将可选响应值转换为去空白后的文本，或明确表示缺失。
     *
     * <p>这是无副作用的格式化辅助方法，不授予权限、不验证证据，也不把值写回 Map。相同输入始终得到相同
     * 输出，便于上层用 {@code null} 区分可选字段缺失与必填字段的合同冲突。</p>
     *
     * @param value 原始响应值，可为空
     * @return 清理后的文本；值为空或空白时返回 {@code null}
     */
    private String optionalText(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value).trim();
    }

    /**
     * Validates a required local trigger-result field before it becomes part of the fixed callback contract.
     *
     * <p>This helper is intentionally small and side-effect free: it trims a single value and rejects null or blank
     * text. A missing event ID, status, or reason code is not a business denial from data-sync; it means the local
     * consumer cannot construct the documented internal request. The resulting {@link IllegalStateException} must
     * therefore remain visible to Kafka retry processing.</p>
     *
     * @param value local result field to normalize
     * @return non-blank trimmed field value
     * @throws IllegalStateException when the callback field is missing
     */
    private String requiredResultText(String value) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw invalidDataSyncContract("AUTOPILOT_TRIGGER_RESULT_INVALID");
        }
        return normalized;
    }

    /**
     * Recognizes a successful platform response envelope without coupling the callback to an optional data payload.
     *
     * <p>The trigger-result endpoint may acknowledge a write with a null or implementation-specific {@code data}
     * field, so this method relies on the platform-wide {@code code == 0} success rule. It accepts JSON number and
     * string representations because generic map deserialization can choose either form. Any other shape is a
     * transport-contract problem, not a terminal Autopilot business decision.</p>
     *
     * @param response response envelope decoded by Spring's JSON converter
     * @return {@code true} only when the platform envelope explicitly reports success
     */
    private boolean successfulPlatformEnvelope(Map<String, Object> response) {
        if (response == null || !response.containsKey("code")) {
            return false;
        }
        Object code = response.get("code");
        if (code instanceof Number number) {
            return number.longValue() == 0L;
        }
        try {
            return Long.parseLong(String.valueOf(code).trim()) == 0L;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Creates a low-sensitive exception for a broken data-sync integration contract.
     *
     * <p>The exception contains only a stable reason code and never embeds a response body, URL, token, or remote
     * message. It is deliberately an {@link IllegalStateException}: a malformed envelope or impossible local request
     * is a technical failure that must not be acknowledged as a permanent business rejection.</p>
     *
     * @param reasonCode stable code for logs, metrics, and retry diagnostics
     * @return technical integration exception for the current callback or response contract
     */
    private IllegalStateException invalidDataSyncContract(String reasonCode) {
        return new IllegalStateException(reasonCode);
    }
}
