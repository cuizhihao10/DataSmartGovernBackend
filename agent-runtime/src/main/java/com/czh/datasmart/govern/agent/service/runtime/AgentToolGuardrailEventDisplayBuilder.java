/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolGuardrailEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 工具执行 guardrail 事件展示解释器。
 *
 * <p>这类事件来自 Java worker 或 agent-runtime 执行前保护链路，例如 permission-admin 拒绝、
 * selected-node confirmation 不一致、策略版本漂移、目标服务暂不可用等。它们和“工具已经执行失败”不同：
 * 多数情况下真实副作用还没有发生，是平台在执行前主动 fail-closed 或 defer。</p>
 *
 * <p>从 {@link AgentRuntimeEventDisplaySupport} 拆出本类，是为了让通用 display support 只负责事件类型分发，
 * 不再承载所有业务解释规则。后续如果 guardrail 规则继续扩展，例如加入租户配额、服务账号签名、mTLS、
 * worker 租约或幂等冲突，也只需要改这个 builder。</p>
 */
final class AgentToolGuardrailEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentToolGuardrailEventDisplayBuilder() {
    }

    /**
     * 判断一条通用工具状态事件是否属于执行前 guardrail。
     *
     * <p>当前历史事件类型仍复用 `agent.tool_execution.state_changed`，所以不能只靠 eventType 区分。
     * 这里通过低敏 errorCode、message 和 stage 识别“执行前保护”语义，而不是把所有工具状态都渲染成阻断卡片。</p>
     */
    static boolean matches(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        String errorCode = textAttribute(attributes, "errorCode");
        String message = normalize(record.message());
        return errorCode.startsWith("AGENT_ASYNC_TOOL_")
                || message.contains("permission-admin")
                || message.contains("confirmation")
                || message.contains("执行前复核")
                || message.contains("worker 已阻止副作用");
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        String errorCode = textAttribute(attributes, "errorCode");
        String message = record.message() == null || record.message().isBlank()
                ? "Agent worker 执行前保护机制已触发。"
                : record.message();
        boolean deferred = isDeferredGuardrail(errorCode, message, record.stage());
        Map<String, Object> metrics = new LinkedHashMap<>();
        putIfPresent(metrics, "errorCode", errorCode);
        putIfPresent(metrics, "toolCode", textAttribute(attributes, "toolCode"));
        putIfPresent(metrics, "currentState", textAttribute(attributes, "currentState"));
        putIfPresent(metrics, "targetService", textAttribute(attributes, "targetService"));
        putIfPresent(metrics, "preCheckDecision", textAttribute(attributes, "preCheckDecision"));
        metrics.put("issueCodeCount", listSize(attributes.get("issueCodes")));
        metrics.put("sideEffectPrevented", !deferred);

        return new AgentRuntimeEventDisplayView(
                "AGENT_TOOL_GUARDRAIL",
                title(errorCode, message),
                message,
                deferred ? "DEFERRED_WAITING_RETRY" : "BLOCKED_BEFORE_SIDE_EFFECT",
                "guardrail",
                true,
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(errorCode, message),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static String title(String errorCode, String message) {
        return switch (normalize(errorCode)) {
            case "agent_async_tool_permission_denied" -> "Agent 工具被权限策略阻断";
            case "agent_async_tool_approval_required" -> "Agent 工具仍需审批";
            case "agent_async_tool_policy_version_drift" -> "Agent 工具策略版本已变化";
            case "agent_async_tool_confirmation_rejected" -> "Agent 工具确认记录复核失败";
            case "agent_async_tool_permission_unavailable" -> "权限中心暂不可用，工具等待重试";
            case "agent_async_tool_confirmation_unavailable" -> "确认记录暂不可用，工具等待重试";
            case "agent_async_tool_not_whitelisted" -> "Agent 工具未配置白名单适配器";
            case "agent_async_tool_audit_state_rejected" -> "Agent 工具状态不允许执行";
            case "agent_async_tool_precheck_blocked" -> "Agent 异步命令执行前复核阻断";
            case "agent_async_tool_precheck_deferred" -> "Agent 异步命令执行前复核暂缓";
            default -> normalize(message).contains("暂时不可用")
                    ? "Agent 工具执行前复核暂不可用"
                    : "Agent 工具执行前保护已触发";
        };
    }

    private static List<String> recommendedActions(String errorCode, String message) {
        String normalizedCode = normalize(errorCode);
        String normalizedMessage = normalize(message);
        if (normalizedCode.contains("permission_denied")) {
            return List.of("检查 permission-admin 路由策略、服务账号委托关系和用户项目权限。");
        }
        if (normalizedCode.contains("approval_required")) {
            return List.of("进入审批面板完成当前工具动作审批，审批通过后重新确认或重试。");
        }
        if (normalizedCode.contains("policy_version_drift")) {
            return List.of("重新执行 dry-run/selected-node confirmation，使用最新策略版本生成新的执行证据。");
        }
        if (normalizedCode.contains("confirmation_rejected")) {
            return List.of("核对 confirmationId、sessionId、runId、auditId 和 commandId 是否来自同一次 DAG 确认。");
        }
        if (normalizedCode.contains("precheck")) {
            return List.of("检查 selected-node confirmation、policyVersion、sandboxIssueCodes 和 runtimeProtectionIssueCodes，确认应重新确认、人工补偿还是等待退避重试。");
        }
        if (normalizedCode.contains("permission_unavailable") || normalizedMessage.contains("permission-admin")) {
            return List.of("等待 permission-admin 恢复后由 worker 自动重试；若持续出现，请检查服务发现、超时和权限中心健康。");
        }
        if (normalizedCode.contains("confirmation_unavailable") || normalizedMessage.contains("confirmation")) {
            return List.of("等待 agent-runtime confirmation 查询恢复后由 worker 自动重试；若持续出现，请检查运行时控制面健康。");
        }
        if (normalizedCode.contains("not_whitelisted")) {
            return List.of("为该 toolCode 增加受控 Java 工具适配器，并确认不会按任意 targetEndpoint 转发。");
        }
        return List.of("查看同一 runId 的前后事件，确认是配置、权限、审批、状态还是控制面依赖导致。");
    }

    private static boolean isDeferredGuardrail(String errorCode, String message, String stage) {
        String normalizedCode = normalize(errorCode);
        String normalizedMessage = normalize(message);
        String normalizedStage = normalize(stage);
        return normalizedCode.contains("unavailable")
                || normalizedMessage.contains("暂时不可用")
                || normalizedMessage.contains("等待重试")
                || normalizedStage.contains("deferred")
                || "tool_executing".equals(normalizedStage);
    }

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record == null || record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private static int listSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private static String textAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
