/**
 * @Author : Cui
 * @Date: 2026/08/11 20:05
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryPythonClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.service.tool.AgentToolDownstreamHttpSupport;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 调用 Python Runtime 内部 Autopilot Recovery 规划入口的强类型客户端。
 *
 * <p>客户端只发送 Java 验证后的作用域、循环和错误指纹，不发送完整授权 JSON、用户 prompt、日志正文
 * 或数据样本。Python 返回的是建议，不是执行许可；响应还必须经过证据验证和 Java/data-sync 双策略。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAutopilotRecoveryPythonClient {

    private static final String PYTHON_AI_RUNTIME = "python-ai-runtime";
    private static final String PLAN_PATH = "/internal/agent/autopilot/recovery/plan";
    private static final String POST_ACTION_VERIFICATION_PATH =
            "/internal/agent/autopilot/recovery/post-action-verification";
    private static final String RESPONSE_SCHEMA = "datasmart.autopilot.recovery-candidate.v1";
    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY";
    private static final String POST_ACTION_VERIFICATION_SCHEMA =
            "datasmart.autopilot.post-recovery-verification.v1";
    private static final String POST_ACTION_VERIFICATION_PAYLOAD_POLICY =
            "LOW_SENSITIVE_AUTOPILOT_POST_RECOVERY_VERIFICATION_ONLY";
    private static final Set<String> REQUIRED_POST_ACTION_ROLES = Set.of(
            "PRECHECK_AGENT", "MONITOR_AGENT");
    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            "CANDIDATE_READY", "ATTENTION_REQUIRED", "FAILED");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z0-9_.:-]{1,96}");

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;

    /**
     * 把已验证的恢复触发器投影为固定内部合同，并请求 Python 生成一份低敏恢复建议。
     *
     * <p>输入只能是已完成 session、run 和授权一致性校验的 {@code trigger}；输出是 Python 的候选或阻断
     * 响应，不是 Java 的执行许可。方法会向固定的内部路径发送一次 POST，并只附带内部服务令牌和已经验证过
     * 的范围、循环、指纹等最小字段，不转发用户 prompt、日志正文或完整授权快照。</p>
     *
     * <p>权限仍由 Java 和 data-sync 决定：Python 无法通过此调用扩大动作、风险或租户范围。请求带有
     * {@code eventId} 供跨服务关联，但该客户端没有本地去重缓存；重复 Kafka 投递可以重复规划，后续的
     * 证据校验、双策略和持久 receipt 才负责阻止重复副作用。网络失败、非成功 HTTP 响应会按
     * {@link RestClient} 的异常语义向上层传播，空响应会转换为技术合同异常。</p>
     *
     * @param trigger 已重新验证 session、run、授权和恢复时限的可信触发器
     * @return Python 返回的低敏候选或阻断结果，仍须经过 Java 证据和策略校验
     * @throws PlatformBusinessException when the caller supplies no verified trigger
     * @throws IllegalStateException when Python returns an empty, malformed, or schema-incompatible planner response
     */
    public AgentAutopilotRecoveryPlanResponse plan(AgentAutopilotVerifiedRecoveryTrigger trigger) {
        if (trigger == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Verified Autopilot recovery trigger is required");
        }
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", event.eventId());
        request.put("rootSessionId", event.rootSessionId());
        request.put("rootRunId", event.rootRunId());
        request.put("tenantId", event.tenantId());
        request.put("applicationId", event.applicationId());
        request.put("projectId", event.projectId());
        request.put("userId", event.userId());
        request.put("actorId", event.actorId());
        request.put("agentId", event.agentId());
        request.put("delegationId", event.delegationId());
        request.put("workspaceKey", trigger.session().getWorkspaceKey());
        request.put("syncTaskId", event.syncTaskId());
        request.put("rootExecutionId", event.rootExecutionId());
        request.put("currentExecutionId", event.currentExecutionId());
        request.put("cycle", event.cycle());
        request.put("maxRecoveryCycles", event.maxRecoveryCycles());
        request.put("deadlineAt", trigger.deadlineAt().toString());
        request.put("errorFingerprint", event.errorFingerprint());
        request.put("repeatedErrorCount", event.repeatedErrorCount());
        request.put("previousRepairFingerprint", event.previousRepairFingerprint());
        request.put("issueCodes", event.issueCodes());
        request.put("triggeredAt", event.triggeredAt());

        RestClient client = httpSupport.serviceClient(restClientBuilder, PYTHON_AI_RUNTIME);
        AgentAutopilotRecoveryPlanResponse response = client.post()
                .uri(PLAN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> httpSupport.applyPythonRuntimeInternalServiceToken(headers))
                .body(request)
                .retrieve()
                .body(AgentAutopilotRecoveryPlanResponse.class);
        validatePlanResponse(trigger, response);
        return response;
    }

    /**
     * 在 data-sync 返回真实重排队回执后，调用 Python 执行 PRECHECK/MONITOR 只读复核。
     *
     * <p>输入由三部分组成：已验证触发器提供双主体与授权范围，data-sync receipt 提供真实任务/执行定位，
     * recovery case 提供持久 case 与动作身份。方法只调用固定内部路径，不接受模型生成 URL 或任意角色列表。
     * Python 必须复用既有 Specialist coordinator、角色工具白名单、LangGraph checkpoint 与 Java fact sink；
     * 只有两角色都完成且事实持久化后才返回 {@code VERIFIED}。</p>
     *
     * <p>空响应、范围漂移、缺少任一角色或非 COMPLETED 批次都是技术合同失败。异常不会转成
     * ATTENTION_REQUIRED，而是传播到 Kafka listener，让同一个 eventId 在有限重试中复用 data-sync
     * retry receipt、Python checkpoint 和 Specialist fact 幂等键。</p>
     *
     * @param trigger Java 已重新验证的恢复触发器
     * @param recoveryCase data-sync 持久恢复 case
     * @param recoveryAction 已通过 Java 与 data-sync 双策略批准的动作
     * @param retryReceipt data-sync 返回的真实重排队回执
     * @return 已通过完整字段复核的低敏 Python 响应
     * @throws IllegalStateException 当本地输入或 Python 响应不符合固定合同
     */
    public AgentAutopilotPostRecoveryVerificationResponse verifyPostRecoveryAction(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryCaseView recoveryCase,
            String recoveryAction,
            AgentAutopilotRecoveryRetryReceipt retryReceipt) {
        if (trigger == null || recoveryCase == null || recoveryCase.caseId() == null
                || recoveryCase.caseId() <= 0 || retryReceipt == null
                || !retryReceipt.matchesRequeuedScope(trigger.event())) {
            throw new IllegalStateException("AUTOPILOT_POST_RECOVERY_VERIFICATION_INPUT_INVALID");
        }
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", event.eventId());
        request.put("rootSessionId", event.rootSessionId());
        request.put("rootRunId", event.rootRunId());
        request.put("tenantId", event.tenantId());
        request.put("applicationId", event.applicationId());
        request.put("projectId", event.projectId());
        request.put("userId", event.userId());
        request.put("actorId", event.actorId());
        request.put("agentId", event.agentId());
        request.put("delegationId", event.delegationId());
        request.put("workspaceKey", trigger.session().getWorkspaceKey());
        request.put("syncTaskId", event.syncTaskId());
        request.put("currentExecutionId", event.currentExecutionId());
        request.put("taskId", retryReceipt.taskId());
        request.put("executionId", retryReceipt.executionId());
        request.put("caseId", recoveryCase.caseId());
        request.put("recoveryAction", recoveryAction);
        request.put("cycle", event.cycle());

        AgentAutopilotPostRecoveryVerificationResponse response = httpSupport
                .serviceClient(restClientBuilder, PYTHON_AI_RUNTIME)
                .post()
                .uri(POST_ACTION_VERIFICATION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> httpSupport.applyPythonRuntimeInternalServiceToken(headers))
                .body(request)
                .retrieve()
                .body(AgentAutopilotPostRecoveryVerificationResponse.class);
        validatePostRecoveryVerificationResponse(trigger, retryReceipt, response);
        return response;
    }

    /**
     * 复核 Python 后置响应确实绑定当前事件、真实 receipt 和两个必需角色。
     *
     * <p>列表使用集合比较，允许 Python 以稳定排序返回角色而不把顺序当业务语义；同时要求列表长度为二，
     * 防止重复一个角色后恰好集合相等。该方法无网络或持久化副作用，失败只抛固定低敏机器码。</p>
     */
    private void validatePostRecoveryVerificationResponse(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryRetryReceipt receipt,
            AgentAutopilotPostRecoveryVerificationResponse response) {
        if (response == null
                || !POST_ACTION_VERIFICATION_SCHEMA.equals(response.schemaVersion())
                || !"VERIFIED".equals(response.status())
                || !trigger.event().eventId().equals(response.eventId())
                || !receipt.taskId().equals(response.taskId())
                || !receipt.executionId().equals(response.executionId())
                || response.executedRoles().size() != REQUIRED_POST_ACTION_ROLES.size()
                || response.completedRoles().size() != REQUIRED_POST_ACTION_ROLES.size()
                || !Set.copyOf(response.executedRoles()).equals(REQUIRED_POST_ACTION_ROLES)
                || !Set.copyOf(response.completedRoles()).equals(REQUIRED_POST_ACTION_ROLES)
                || !"COMPLETED".equals(response.batchStatus())
                || blank(response.checkpointThreadId())
                || !POST_ACTION_VERIFICATION_PAYLOAD_POLICY.equals(response.payloadPolicy())) {
            throw new IllegalStateException("PYTHON_AUTOPILOT_POST_RECOVERY_VERIFICATION_RESPONSE_INVALID");
        }
    }

    /**
     * Validates that a successful HTTP response is the exact low-sensitive recovery-planning contract expected by Java.
     *
     * <p>The input is a 2xx body already deserialized by Spring and the verified trigger that originated the request.
     * HTTP success alone is insufficient: this method requires the versioned schema, the original event and error
     * fingerprint bindings, the fixed payload policy, a safe reason code, a finite supported status, and a finite
     * confidence value. For a {@code CANDIDATE_READY} response it additionally requires the action, risk level, and
     * repair fingerprint that later governance checks consume.</p>
     *
     * <p>The method performs no I/O, persistence, authorization, or retry itself. A violation becomes a fixed
     * {@link IllegalStateException}; the consumer records planning failure and rethrows it so Kafka bounded retry can
     * handle malformed Python contracts. It intentionally leaves evidence scope, digest, source, and freshness facts
     * to the evidence verifier, where deterministic business denials become durable {@code REJECTED} results.</p>
     *
     * @param trigger Java-verified trigger that supplies the authoritative event and error-fingerprint bindings
     * @param response deserialized Python 2xx body, which may be null or incomplete
     * @throws IllegalStateException when any required planner response contract rule is violated
     */
    private void validatePlanResponse(AgentAutopilotVerifiedRecoveryTrigger trigger,
                                      AgentAutopilotRecoveryPlanResponse response) {
        if (response == null
                || !RESPONSE_SCHEMA.equals(response.schemaVersion())
                || !trigger.event().eventId().equals(response.eventId())
                || response.status() == null || !SUPPORTED_STATUSES.contains(response.status())
                || response.reasonCode() == null || !SAFE_CODE.matcher(response.reasonCode()).matches()
                || !PAYLOAD_POLICY.equals(response.payloadPolicy())
                || !fingerprint(response.errorFingerprint())
                || !trigger.event().errorFingerprint().equalsIgnoreCase(response.errorFingerprint())
                || !Double.isFinite(response.confidence())
                || response.confidence() < 0.0d || response.confidence() > 1.0d) {
            throw invalidPlannerResponse();
        }
        if ("CANDIDATE_READY".equals(response.status())
                && (blank(response.action())
                || !SAFE_CODE.matcher(response.action()).matches()
                || blank(response.riskLevel())
                || !SAFE_CODE.matcher(response.riskLevel()).matches()
                || !fingerprint(response.repairFingerprint()))) {
            throw invalidPlannerResponse();
        }
    }

    /**
     * Recognizes the fixed 64-character hexadecimal fingerprint form used by the planner response contract.
     *
     * <p>The input is an untrusted response field and the output only says whether its syntax is usable. This pure
     * helper does not recompute a digest or grant evidence validity; binding the accepted fingerprint to the verified
     * trigger remains the responsibility of {@link #validatePlanResponse(AgentAutopilotVerifiedRecoveryTrigger,
     * AgentAutopilotRecoveryPlanResponse)}.</p>
     *
     * @param value candidate error or repair fingerprint from Python
     * @return {@code true} only for a non-null 64-character hexadecimal fingerprint
     */
    private boolean fingerprint(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    /**
     * Tests whether a required planner text field is absent after applying the contract's whitespace rule.
     *
     * <p>The method has no side effects and does not normalize a value for later use; it only prevents null or blank
     * action and risk fields from crossing the Java/Python contract boundary as if they were valid governance input.</p>
     *
     * @param value untrusted planner text field
     * @return {@code true} when the field is null or blank
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Creates the stable technical exception used for all invalid successful-HTTP planner responses.
     *
     * <p>The output intentionally contains a fixed low-sensitive reason code rather than a remote response body or
     * parsing message. It has no side effects. Callers must let this exception reach the Kafka listener so malformed
     * Python contracts enter the configured bounded retry and, if still unresolved, DLT path.</p>
     *
     * @return retryable technical contract exception for the current planner response
     */
    private IllegalStateException invalidPlannerResponse() {
        return new IllegalStateException("PYTHON_AUTOPILOT_RECOVERY_PLANNER_RESPONSE_INVALID");
    }
}
