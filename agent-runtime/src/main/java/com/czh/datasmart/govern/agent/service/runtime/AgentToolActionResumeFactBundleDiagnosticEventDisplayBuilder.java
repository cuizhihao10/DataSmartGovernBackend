/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleDiagnosticEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 恢复事实包诊断事件的 timeline 展示解释器。
 *
 * <p>fact bundle diagnostic event 解决的是“恢复预检为什么不能继续”的人类可读问题。
 * 原始事件 attributes 保存的是低敏机器字段，例如 missingFactTypes、rejectedFactTypes、locatorIndexHit；
 * 本类把这些字段翻译成管理台更容易理解的状态：事实已齐、仍缺事实、事实被拒绝、数据范围不完整等。</p>
 *
 * <p>注意：展示解释器只读 runtime event projection 中已经脱敏/低敏化的字段，
 * 不会回查 approvalFactId、outboxId、payloadReference 或工具参数。自动化执行仍必须读取稳定机器字段和
 * permission-admin/outbox/worker receipt 等服务端事实，不能根据 display.title 或 display.summary 直接执行。</p>
 */
final class AgentToolActionResumeFactBundleDiagnosticEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentToolActionResumeFactBundleDiagnosticEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        int missingCount = intValue(attributes, "missingFactTypeCount");
        int rejectedCount = intValue(attributes, "rejectedFactTypeCount");
        int availableCount = intValue(attributes, "availableFactTypeCount");
        boolean locatorIndexHit = bool(attributes, "locatorIndexHit");
        Map<String, Object> metrics = metrics(attributes, availableCount, missingCount, rejectedCount, locatorIndexHit);
        return new AgentRuntimeEventDisplayView(
                "TOOL_ACTION_RESUME_FACT_BUNDLE",
                title(missingCount, rejectedCount, locatorIndexHit),
                summary(record, availableCount, missingCount, rejectedCount, locatorIndexHit),
                status(missingCount, rejectedCount),
                "resume-facts",
                rejectedCount > 0 || missingCount > 0 || !identityPresent(attributes),
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(attributes, missingCount, rejectedCount, locatorIndexHit),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static Map<String, Object> metrics(Map<String, Object> attributes,
                                               int availableCount,
                                               int missingCount,
                                               int rejectedCount,
                                               boolean locatorIndexHit) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("availableFactTypeCount", availableCount);
        metrics.put("missingFactTypeCount", missingCount);
        metrics.put("rejectedFactTypeCount", rejectedCount);
        metrics.put("requiredFactTypeCount", intValue(attributes, "requiredFactTypeCount"));
        metrics.put("locatorIndexHit", locatorIndexHit);
        metrics.put("locatorIndexEvidenceCodeCount", listSize(attributes.get("locatorIndexEvidenceCodes")));
        metrics.put("checkpointIdPresent", bool(attributes, "checkpointIdPresent"));
        metrics.put("threadIdPresent", bool(attributes, "threadIdPresent"));
        metrics.put("commandIdPresent", bool(attributes, "commandIdPresent"));
        metrics.put("approvalFactIdPresent", bool(attributes, "approvalFactIdPresent"));
        metrics.put("outboxIdPresent", bool(attributes, "outboxIdPresent"));
        metrics.put("clarificationFactIdPresent", bool(attributes, "clarificationFactIdPresent"));
        metrics.put("toolCode", text(attributes, "toolCode", null));
        metrics.put("outboxSummaryPresent", nestedBool(attributes, "outboxSummary", "present"));
        metrics.put("receiptSummaryPresent", nestedBool(attributes, "receiptSummary", "present"));
        metrics.put("receiptCount", nestedInt(attributes, "receiptSummary", "receiptCount"));
        metrics.put("latestReceiptOutcome", nestedText(attributes, "receiptSummary", "latestOutcome"));
        metrics.put("latestReceiptErrorCode", nestedText(attributes, "receiptSummary", "latestErrorCode"));
        metrics.put("identityPresent", identityPresent(attributes));
        metrics.put("explicitProjectScope", nestedBool(attributes, "securityBoundary", "explicitProjectScope"));
        metrics.put("authorizedProjectCount", nestedInt(attributes, "securityBoundary", "authorizedProjectCount"));
        metrics.put("missingProductionRequirementCount",
                nestedInt(attributes, "productionReadiness", "missingProductionRequirementCount"));
        return metrics;
    }

    private static String title(int missingCount, int rejectedCount, boolean locatorIndexHit) {
        if (rejectedCount > 0) {
            return "恢复事实包存在被拒绝事实";
        }
        if (missingCount > 0) {
            return locatorIndexHit ? "恢复定位已命中但事实仍缺失" : "恢复事实包仍缺少服务端事实";
        }
        return locatorIndexHit ? "恢复事实包已由 locator index 补齐" : "恢复事实包已完成预检";
    }

    private static String summary(AgentRuntimeEventProjectionRecord record,
                                  int availableCount,
                                  int missingCount,
                                  int rejectedCount,
                                  boolean locatorIndexHit) {
        if (record.message() != null && !record.message().isBlank()) {
            return record.message();
        }
        return "恢复事实包诊断：已采信 " + availableCount
                + " 个，缺失 " + missingCount
                + " 个，拒绝 " + rejectedCount
                + " 个，locatorIndexHit=" + locatorIndexHit + "。";
    }

    private static String status(int missingCount, int rejectedCount) {
        if (rejectedCount > 0) {
            return "REJECTED_BEFORE_RESUME";
        }
        if (missingCount > 0) {
            return "WAITING_RESUME_FACTS";
        }
        return "FACTS_READY_FOR_PREVIEW_ONLY";
    }

    @SuppressWarnings("unchecked")
    private static List<String> recommendedActions(Map<String, Object> attributes,
                                                   int missingCount,
                                                   int rejectedCount,
                                                   boolean locatorIndexHit) {
        Object value = attributes.get("recommendedActions");
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return (List<String>) value;
        }
        if (rejectedCount > 0) {
            return List.of("检查 rejectedFactTypes 和 factSummaries.issueCodes，重新生成被拒绝的审批、outbox 或 worker receipt 事实。");
        }
        if (missingCount > 0 && locatorIndexHit) {
            return List.of("locator index 已补齐定位符，继续检查缺失事实源：permission-admin、command outbox、clarification store 或 worker receipt。");
        }
        if (missingCount > 0) {
            return List.of("先补齐 checkpoint/thread 到 command/outbox/approval 的 locator，再重新查询恢复事实包。");
        }
        return List.of("事实包当前仅表示 resume-preview 可继续，真实工具恢复执行仍必须经过 outbox、审批、幂等和 worker receipt 闭环。");
    }

    private static boolean identityPresent(Map<String, Object> attributes) {
        return nestedBool(attributes, "securityBoundary", "identityPresent");
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

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }
}
