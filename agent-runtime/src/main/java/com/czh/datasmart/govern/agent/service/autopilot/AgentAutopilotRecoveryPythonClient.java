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
    private static final Set<String> MODEL_FAILURE_REASON_CODES = Set.of(
            "MODEL_TIMEOUT",
            "MODEL_PROVIDER_ERROR",
            "MODEL_RESPONSE_INVALID_JSON",
            "MODEL_RESPONSE_CONTRACT_VIOLATION",
            "MODEL_RESULT_UNAVAILABLE",
            "MODEL_ADAPTER_ERROR");
    private static final Set<String> MODEL_FAILURE_SOURCES = Set.of(
            "MODEL_PROVIDER_TRANSPORT",
            "MODEL_PROVIDER_RESPONSE",
            "MODEL_RESPONSE_PARSER",
            "MODEL_RESPONSE_CONTRACT",
            "MODEL_RESULT_READER",
            "SPECIALIST_MODEL_ADAPTER");
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
 * @throws PlatformBusinessException 调用方没有提供已校验触发事件时抛出
 * @throws IllegalStateException Python 返回空、格式错误或不符合 schema 的规划响应时抛出
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
                || !retryReceipt.matchesRequeuedScope(trigger.event(), recoveryCase.currentExecutionId())) {
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
        request.put("currentExecutionId", recoveryCase.currentExecutionId());
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
     * 校验 HTTP 成功响应是否严格符合 Java 端约定的低敏恢复规划合同。
     *
     * <p>输入包括 Spring 已反序列化的 2xx 响应体，以及发起请求前已经验证的触发器。HTTP 成功本身并不
     * 等于规划有效：还必须校验版本化 schema、原始事件与错误指纹绑定、固定载荷策略、安全原因码、有限状态
     * 和 0 到 1 之间的有限置信度。若状态为 {@code CANDIDATE_READY}，还必须提供后续治理门禁要使用的动作、
     * 风险等级和修复指纹。</p>
     *
     * <p>本方法自身不执行 I/O、持久化、授权或重试。合同违规会转换成固定的
     * {@link IllegalStateException}；消费者记录规划失败后继续抛出，让 Kafka 有界重试处理损坏的 Python
     * 合同。证据范围、摘要、来源和时效性由专用证据验证器负责，确定性业务拒绝则落为持久
     * {@code REJECTED} 结果。</p>
     *
     * @param trigger Java 已验证的触发器，提供权威事件和错误指纹绑定
     * @param response 已反序列化的 Python 2xx 响应体，可能为空或字段不完整
     * @throws IllegalStateException 任一规划响应合同规则不满足时抛出
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
        boolean planningModelFailed = "FAILED".equals(response.status())
                && "RECOVERY_PLANNING_MODEL_FAILED".equals(response.reasonCode());
        if (planningModelFailed
                && (!MODEL_FAILURE_REASON_CODES.contains(response.modelFailureReasonCode())
                || !MODEL_FAILURE_SOURCES.contains(response.modelFailureSource()))) {
            throw invalidPlannerResponse();
        }
        if (!planningModelFailed
                && (!blank(response.modelFailureReasonCode()) || !blank(response.modelFailureSource()))) {
            throw invalidPlannerResponse();
        }
    }

    /**
     * 识别规划响应合同使用的固定 64 位十六进制指纹格式。
     *
     * <p>输入是不可信响应字段，返回值只说明语法是否可用。该纯函数不会重新计算摘要，也不会据此认定证据
     * 有效；将已接受指纹绑定到已验证触发器，仍由
     * {@link #validatePlanResponse(AgentAutopilotVerifiedRecoveryTrigger, AgentAutopilotRecoveryPlanResponse)}
     * 负责。</p>
     *
     * @param value Python 返回的候选错误或修复指纹
     * @return 仅非空 64 位十六进制指纹返回 {@code true}
     */
    private boolean fingerprint(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    /**
     * 按合同的空白规则判断规划器必填文本是否缺失。
     *
     * <p>该方法没有副作用，也不会为后续使用改写字段；它只防止空动作或空风险等级越过 Java/Python 合同
     * 边界并被误当成有效治理输入。</p>
     *
     * @param value 不可信的规划器文本字段
     * @return 字段为空或全空白时返回 {@code true}
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 为所有“HTTP 成功但规划合同无效”的响应创建稳定技术异常。
     *
     * <p>异常只携带固定低敏原因码，不包含远端响应正文或解析消息，也不产生副作用。调用方必须让异常到达
     * Kafka 监听器，使损坏的 Python 合同进入已配置的有界重试；多次仍无法恢复时再进入 DLT。</p>
     *
     * @return 当前规划响应对应的可重试技术合同异常
     */
    private IllegalStateException invalidPlannerResponse() {
        return new IllegalStateException("PYTHON_AUTOPILOT_RECOVERY_PLANNER_RESPONSE_INVALID");
    }
}
