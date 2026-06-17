/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 澄清事实 runtime event 的 timeline 展示解释器。
 *
 * <p>澄清事实事件表达的是“用户或上游 Agent 已经补齐某个恢复前置事实”，不是工具执行结果。
 * 因此展示层需要反复强调：AVAILABLE 只表示 CLARIFICATION_FACT 这类 host fact 可回查，不代表 outbox
 * 已写入、审批已通过、worker 已执行或副作用已经产生。</p>
 *
 * <p>本 builder 只读取 publisher 写入的低敏 attributes。它不会回查 Store，也不会展示 clarificationFactId 原文、
 * 澄清正文、工具参数、SQL、prompt、payload 或模型输出。</p>
 */
final class AgentToolActionClarificationFactEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentToolActionClarificationFactEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        boolean expired = bool(attributes, "expired");
        boolean available = bool(attributes, "available");
        String status = status(attributes, expired, available);
        Map<String, Object> metrics = metrics(attributes, expired, available);
        return new AgentRuntimeEventDisplayView(
                "TOOL_ACTION_CLARIFICATION_FACT",
                title(status),
                summary(record, attributes, expired, available),
                status,
                "clarification-fact",
                expired || !available,
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(attributes, status),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static Map<String, Object> metrics(Map<String, Object> attributes,
                                               boolean expired,
                                               boolean available) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("available", available);
        metrics.put("expired", expired);
        metrics.put("clarificationFactIdPresent", bool(attributes, "clarificationFactIdPresent"));
        metrics.put("runIdPresent", bool(attributes, "runIdPresent"));
        metrics.put("sessionIdPresent", bool(attributes, "sessionIdPresent"));
        metrics.put("commandIdPresent", bool(attributes, "commandIdPresent"));
        metrics.put("expiresAtPresent", bool(attributes, "expiresAtPresent"));
        metrics.put("evidenceCodeCount", intValue(attributes, "evidenceCodeCount"));
        metrics.put("issueCodeCount", intValue(attributes, "issueCodeCount"));
        metrics.put("toolCode", text(attributes, "toolCode", null));
        metrics.put("requestedPolicyVersion", text(attributes, "requestedPolicyVersion", null));
        metrics.put("identityPresent", nestedBool(attributes, "securityBoundary", "identityPresent"));
        metrics.put("actorRole", nestedText(attributes, "securityBoundary", "actorRole"));
        metrics.put("dataScopeLevel", nestedText(attributes, "securityBoundary", "dataScopeLevel"));
        metrics.put("explicitProjectScope", nestedBool(attributes, "securityBoundary", "explicitProjectScope"));
        metrics.put("authorizedProjectCount", nestedInt(attributes, "securityBoundary", "authorizedProjectCount"));
        return metrics;
    }

    private static String status(Map<String, Object> attributes, boolean expired, boolean available) {
        String factStatus = text(attributes, "status", "UNKNOWN");
        if (expired) {
            return "CLARIFICATION_FACT_EXPIRED";
        }
        if ("REVOKED".equalsIgnoreCase(factStatus)) {
            return "CLARIFICATION_FACT_REVOKED";
        }
        if ("REJECTED".equalsIgnoreCase(factStatus)) {
            return "CLARIFICATION_FACT_REJECTED";
        }
        if (available) {
            return "CLARIFICATION_FACT_AVAILABLE";
        }
        return "CLARIFICATION_FACT_RECORDED";
    }

    private static String title(String status) {
        return switch (normalize(status)) {
            case "clarification_fact_expired" -> "澄清事实已过期";
            case "clarification_fact_revoked" -> "澄清事实已撤销";
            case "clarification_fact_rejected" -> "澄清事实已拒绝";
            case "clarification_fact_available" -> "澄清事实已登记";
            default -> "澄清事实已记录";
        };
    }

    private static String summary(AgentRuntimeEventProjectionRecord record,
                                  Map<String, Object> attributes,
                                  boolean expired,
                                  boolean available) {
        if (record.message() != null && !record.message().isBlank()) {
            return record.message();
        }
        return "澄清事实状态：" + text(attributes, "status", "UNKNOWN")
                + "，available=" + available
                + "，expired=" + expired
                + "，evidenceCodeCount=" + intValue(attributes, "evidenceCodeCount")
                + "，issueCodeCount=" + intValue(attributes, "issueCodeCount")
                + "。";
    }

    @SuppressWarnings("unchecked")
    private static List<String> recommendedActions(Map<String, Object> attributes, String status) {
        Object value = attributes.get("recommendedActions");
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return (List<String>) value;
        }
        return switch (normalize(status)) {
            case "clarification_fact_expired" -> List.of("重新发起澄清，过期事实不能继续作为恢复预检依据。");
            case "clarification_fact_revoked" -> List.of("尊重撤销结果，重新生成澄清请求并避免复用旧事实。");
            case "clarification_fact_rejected" -> List.of("检查 issueCodes，回到计划或参数收集阶段。");
            default -> List.of("澄清事实只表示可回查前置事实，真实执行仍需经过审批、outbox、worker receipt 和审计闭环。");
        };
    }

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        return record == null || record.attributes() == null ? Map.of() : record.attributes();
    }

    private static String text(Map<String, Object> attributes, String key, String fallback) {
        Object value = attributes.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static String nestedText(Map<String, Object> attributes, String parentKey, String childKey) {
        Object parent = attributes.get(parentKey);
        if (!(parent instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(childKey);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private static boolean nestedBool(Map<String, Object> attributes, String parentKey, String childKey) {
        Object parent = attributes.get(parentKey);
        if (!(parent instanceof Map<?, ?> map)) {
            return false;
        }
        Object value = map.get(childKey);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private static int intValue(Map<String, Object> attributes, String key) {
        return intValue(attributes.get(key));
    }

    private static int nestedInt(Map<String, Object> attributes, String parentKey, String childKey) {
        Object parent = attributes.get(parentKey);
        if (!(parent instanceof Map<?, ?> map)) {
            return 0;
        }
        return intValue(map.get(childKey));
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
