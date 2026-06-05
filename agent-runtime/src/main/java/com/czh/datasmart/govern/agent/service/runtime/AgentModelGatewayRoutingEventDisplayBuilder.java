/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentModelGatewayRoutingEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 模型网关路由事件的展示解释构建器。
 *
 * <p>该类从主 `AgentRuntimeEventDisplaySupport` 中拆出，原因有两个：</p>
 * <p>1. 主 display support 已接近 500 行，继续堆事件解释会重新形成大而耦合的工具类；</p>
 * <p>2. 模型网关路由有独立产品语义：它解释 Provider 选择、fallback、预算和 cache plan，不应混在
 * DAG dry-run、Skill 可见性或工具 guardrail 的展示逻辑中。</p>
 *
 * <p>展示层只解释已经发生的低敏事件事实，不重新计算模型路由，也不访问 Python Runtime 或模型 Provider。</p>
 */
final class AgentModelGatewayRoutingEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentModelGatewayRoutingEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        if (isBasicMasked(attributes)) {
            return new AgentRuntimeEventDisplayView(
                    "MODEL_GATEWAY_ROUTING",
                    "模型网关路由决策已记录",
                    "当前角色只能查看脱敏后的模型网关路由进度，可联系项目负责人或审计员查看低敏聚合摘要。",
                    "SUMMARY_MASKED",
                    "model-gateway",
                    false,
                    REPLAY_POLICY_APPEND_AND_ACK,
                    List.of("如需排查 fallback、Provider 健康或 cache plan，请使用具备项目或审计数据范围的账号查看详情。"),
                    Map.of("detailsMasked", true)
            );
        }

        String selectedProvider = textAttribute(attributes, "selectedProvider");
        String selectedHealthStatus = defaultedText(attributes, "selectedHealthStatus", "unknown");
        boolean fallbackUsed = booleanAttribute(attributes, "fallbackUsed");
        boolean budgetAllowed = booleanAttribute(attributes, "budgetAllowed");
        boolean cachePlanEnabled = booleanAttribute(attributes, "cachePlanEnabled");
        boolean routeScoringTruncated = booleanAttribute(attributes, "routeScoringTruncated");

        Map<String, Object> metrics = new LinkedHashMap<>();
        putIfPresent(metrics, "selectedProvider", selectedProvider);
        putIfPresent(metrics, "selectedModel", textAttribute(attributes, "selectedModel"));
        putIfPresent(metrics, "selectedHealthStatus", selectedHealthStatus);
        putIfPresent(metrics, "configuredPrimaryProvider", textAttribute(attributes, "configuredPrimaryProvider"));
        metrics.put("fallbackUsed", fallbackUsed);
        metrics.put("budgetAllowed", budgetAllowed);
        metrics.put("budgetWarning", booleanAttribute(attributes, "budgetWarning"));
        metrics.put("cacheAwareRouting", booleanAttribute(attributes, "cacheAwareRouting"));
        metrics.put("cachePlanEnabled", cachePlanEnabled);
        metrics.put("cachePlanIssueCount", listSize(attributes.get("cachePlanIssues")));
        metrics.put("candidateCount", intAttribute(attributes, "candidateCount"));
        metrics.put("orderedCandidateProviderCount", listSize(attributes.get("orderedCandidateProviders")));
        metrics.put("routeScoringCount", intAttribute(attributes, "routeScoringCount"));
        metrics.put("routeScoringTruncated", routeScoringTruncated);

        return new AgentRuntimeEventDisplayView(
                "MODEL_GATEWAY_ROUTING",
                title(fallbackUsed, budgetAllowed, selectedHealthStatus, cachePlanEnabled),
                summary(selectedProvider, selectedHealthStatus, fallbackUsed, budgetAllowed, cachePlanEnabled),
                status(fallbackUsed, budgetAllowed, selectedHealthStatus),
                "model-gateway",
                requiresAttention(fallbackUsed, budgetAllowed, selectedHealthStatus, routeScoringTruncated),
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(fallbackUsed, budgetAllowed, selectedHealthStatus, cachePlanEnabled, routeScoringTruncated),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static String title(boolean fallbackUsed,
                                boolean budgetAllowed,
                                String selectedHealthStatus,
                                boolean cachePlanEnabled) {
        if (!budgetAllowed) {
            return "模型网关预算阻断";
        }
        if (fallbackUsed) {
            return "模型网关已使用备用 Provider";
        }
        if (isRiskyHealth(selectedHealthStatus)) {
            return "模型网关路由存在健康风险";
        }
        return cachePlanEnabled ? "模型网关已完成 cache-aware 路由" : "模型网关路由决策已记录";
    }

    private static String summary(String selectedProvider,
                                  String selectedHealthStatus,
                                  boolean fallbackUsed,
                                  boolean budgetAllowed,
                                  boolean cachePlanEnabled) {
        String provider = selectedProvider == null || selectedProvider.isBlank() ? "未选择 Provider" : selectedProvider;
        return "选中 Provider：" + provider
                + "，健康状态：" + selectedHealthStatus
                + "，fallback：" + fallbackUsed
                + "，预算允许：" + budgetAllowed
                + "，cache plan：" + cachePlanEnabled + "。";
    }

    private static String status(boolean fallbackUsed, boolean budgetAllowed, String selectedHealthStatus) {
        if (!budgetAllowed) {
            return "BUDGET_BLOCKED";
        }
        if (fallbackUsed) {
            return "FALLBACK_USED";
        }
        if (isRiskyHealth(selectedHealthStatus)) {
            return "ROUTED_WITH_HEALTH_RISK";
        }
        return "ROUTED";
    }

    private static boolean requiresAttention(boolean fallbackUsed,
                                             boolean budgetAllowed,
                                             String selectedHealthStatus,
                                             boolean routeScoringTruncated) {
        return !budgetAllowed || fallbackUsed || isRiskyHealth(selectedHealthStatus) || routeScoringTruncated;
    }

    private static List<String> recommendedActions(boolean fallbackUsed,
                                                   boolean budgetAllowed,
                                                   String selectedHealthStatus,
                                                   boolean cachePlanEnabled,
                                                   boolean routeScoringTruncated) {
        List<String> actions = new ArrayList<>();
        if (!budgetAllowed) {
            actions.add("检查 permission-admin 或租户套餐中的模型调用预算，必要时切换低成本模型或转离线任务。");
        }
        if (fallbackUsed) {
            actions.add("查看 configuredPrimaryProvider 与 selectedProvider 差异，结合 Provider 健康和熔断状态排查主路由降级原因。");
        }
        if (isRiskyHealth(selectedHealthStatus)) {
            actions.add("当前选中 Provider 健康状态不是 healthy，建议结合 provider health diagnostics 与主动探测指标确认是否需要摘除。");
        }
        if (!cachePlanEnabled) {
            actions.add("如需优化延迟，可查看 cachePlanIssues，确认 sessionId、projectId、cache scope 和安全隔离条件是否满足。");
        }
        if (routeScoringTruncated) {
            actions.add("routeScoring 已被截断，说明候选 Provider 较多；建议管理台只展示 Top 候选，完整排障走受控诊断。");
        }
        if (actions.isEmpty()) {
            actions.add("可将该路由事件与 Skill 可见性、工具预算和 Provider 健康指标一起用于会话治理排障。");
        }
        return List.copyOf(actions);
    }

    private static boolean isRiskyHealth(String selectedHealthStatus) {
        String normalized = normalize(selectedHealthStatus);
        return "degraded".equals(normalized) || "unavailable".equals(normalized) || "unknown".equals(normalized);
    }

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private static boolean isBasicMasked(Map<String, Object> attributes) {
        Object visibilityLevel = attributes.get(AgentRuntimeEventVisibilitySupport.VISIBILITY_LEVEL_ATTRIBUTE);
        return "BASIC".equals(Objects.toString(visibilityLevel, ""));
    }

    private static int intAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String stringValue) {
            try {
                return Math.max(0, Integer.parseInt(stringValue));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean booleanAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        return switch (Objects.toString(value, "").trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }

    private static int listSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private static String textAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private static String defaultedText(Map<String, Object> attributes, String key, String defaultValue) {
        String value = textAttribute(attributes, key);
        return value.isBlank() ? defaultValue : value;
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
