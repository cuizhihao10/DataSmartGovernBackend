/**
 * @Author : Cui
 * @Date: 2026/06/01 22:24
 * @Description DataSmart Govern Backend - AgentAsyncToolGuardrailEventSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 异步工具 worker guardrail 事件支撑类。
 *
 * <p>worker 执行前会经过多道安全门：本地状态复核、工具白名单、agent-runtime confirmation 回查、
 * permission-admin 实时授权、策略版本一致性、未来还会有租户配额和下游熔断。过去这些阻断大多只使用
 * `AGENT_ASYNC_TOOL_PRECHECK_REJECTED` 这一个泛化错误码，前端和审计台只能看到一段中文 message，
 * 很难稳定地按“权限拒绝、策略漂移、确认不一致、依赖暂不可用”等维度聚合。</p>
 *
 * <p>本类的职责不是重新做业务判断，而是把已经产生的 pre-check 结果转换为更稳定的事件语义：
 * 1. 给 agent-runtime 状态回调提供更细的 errorCode；
 * 2. 给 output 放入低敏 guardrail 诊断摘要；
 * 3. 避免 `AgentAsyncToolDispatchOnceService` 继续膨胀成一堆字符串分类 if/else。</p>
 *
 * <p>注意：这里输出的所有诊断都必须保持低敏，只能包含状态、字段名、计数、策略版本号、服务名、reason code 等控制面信息，
 * 不能包含 prompt、SQL、token、密码、样本数据或完整工具参数。</p>
 */
@Component
public class AgentAsyncToolGuardrailEventSupport {

    public static final String CODE_PRECHECK_REJECTED = "AGENT_ASYNC_TOOL_PRECHECK_REJECTED";
    public static final String CODE_AUDIT_STATE_REJECTED = "AGENT_ASYNC_TOOL_AUDIT_STATE_REJECTED";
    public static final String CODE_TOOL_NOT_WHITELISTED = "AGENT_ASYNC_TOOL_NOT_WHITELISTED";
    public static final String CODE_CONFIRMATION_REJECTED = "AGENT_ASYNC_TOOL_CONFIRMATION_REJECTED";
    public static final String CODE_PERMISSION_DENIED = "AGENT_ASYNC_TOOL_PERMISSION_DENIED";
    public static final String CODE_APPROVAL_REQUIRED = "AGENT_ASYNC_TOOL_APPROVAL_REQUIRED";
    public static final String CODE_POLICY_VERSION_DRIFT = "AGENT_ASYNC_TOOL_POLICY_VERSION_DRIFT";
    public static final String CODE_CONFIRMATION_UNAVAILABLE = "AGENT_ASYNC_TOOL_CONFIRMATION_UNAVAILABLE";
    public static final String CODE_PERMISSION_UNAVAILABLE = "AGENT_ASYNC_TOOL_PERMISSION_UNAVAILABLE";
    public static final String CODE_PRECHECK_UNAVAILABLE = "AGENT_ASYNC_TOOL_PRECHECK_UNAVAILABLE";

    /**
     * 根据 pre-check 阻断结果推导更稳定的错误码。
     *
     * <p>分类顺序有业务含义：权限中心拒绝、审批要求、策略漂移等属于更具体、更值得前端单独展示的情况，
     * 应优先于“普通 pre-check rejected”。如果未来接入租户配额、工具级限流，可以继续在这里追加专用 code，
     * 而不需要改动 dispatch 主流程。</p>
     */
    public String preCheckErrorCode(AgentAsyncToolExecutionPreCheckResult result) {
        if (result == null) {
            return CODE_PRECHECK_REJECTED;
        }
        Map<String, Object> diagnostics = result.diagnosticOutput();
        String message = normalize(result.message());
        if (Boolean.FALSE.equals(diagnostics.get("permissionAllowed"))) {
            return CODE_PERMISSION_DENIED;
        }
        if (Boolean.TRUE.equals(diagnostics.get("permissionApprovalRequired")) || message.contains("仍需审批")) {
            return CODE_APPROVAL_REQUIRED;
        }
        if (message.contains("策略版本") || message.contains("policyversion")) {
            return CODE_POLICY_VERSION_DRIFT;
        }
        if (message.contains("permission-admin")) {
            return CODE_PERMISSION_DENIED;
        }
        if (message.contains("agent-runtime") || diagnostics.containsKey("remoteConfirmationCheck")) {
            return CODE_CONFIRMATION_REJECTED;
        }
        if (message.contains("白名单适配器")) {
            return CODE_TOOL_NOT_WHITELISTED;
        }
        if (message.contains("审计状态")) {
            return CODE_AUDIT_STATE_REJECTED;
        }
        return result.errorCode() == null || result.errorCode().isBlank()
                ? CODE_PRECHECK_REJECTED
                : result.errorCode().trim();
    }

    /**
     * 构造写回 agent-runtime 的低敏 guardrail 输出摘要。
     *
     * <p>task-management 已经把真实工具副作用阻断在发生前，因此这里输出的不是工具结果，
     * 而是“为什么没有执行工具”的控制面证据。agent-runtime 可以把这些字段投影到 runtime event display，
     * 让用户、审计员和运维知道应该检查权限、审批、策略版本还是确认记录。</p>
     */
    public Map<String, Object> preCheckOutput(AgentAsyncToolExecutionPreCheckResult result) {
        Map<String, Object> output = baseOutput("EXECUTION_PRECHECK", "BLOCKED_BEFORE_SIDE_EFFECT");
        output.put("guardrailReasonCode", preCheckErrorCode(result));
        if (result != null) {
            output.put("originalErrorCode", result.errorCode());
            output.put("message", result.message());
            output.put("diagnostics", result.diagnosticOutput());
            output.put("validationMessageCount", result.validationMessages().size());
        }
        return output;
    }

    /**
     * 将执行前复核依赖不可用异常转换成稳定错误码。
     *
     * <p>这类问题不应被记录为业务失败，因为依赖恢复后任务可能继续执行；但它必须进入 Agent runtime 时间线，
     * 否则用户只会看到工具一直“执行中”，不知道真实原因是 confirmation 或 permission-admin 暂时不可用。</p>
     */
    public String unavailableErrorCode(AgentAsyncToolPreCheckUnavailableException exception) {
        String message = normalize(exception == null ? null : exception.getMessage());
        if (message.contains("permission-admin")) {
            return CODE_PERMISSION_UNAVAILABLE;
        }
        if (message.contains("confirmation") || message.contains("agent-runtime")) {
            return CODE_CONFIRMATION_UNAVAILABLE;
        }
        return CODE_PRECHECK_UNAVAILABLE;
    }

    public Map<String, Object> unavailableOutput(AgentAsyncToolPreCheckUnavailableException exception) {
        Map<String, Object> output = baseOutput("PRECHECK_DEPENDENCY", "DEFERRED_WAITING_RETRY");
        output.put("guardrailReasonCode", unavailableErrorCode(exception));
        output.put("message", exception == null ? null : exception.getMessage());
        return output;
    }

    private Map<String, Object> baseOutput(String guardrailType, String decision) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("guardrail", true);
        output.put("guardrailType", guardrailType);
        output.put("guardrailDecision", decision);
        output.put("payloadPolicy", "SUMMARY_ONLY_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL");
        return output;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
