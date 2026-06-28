/**
 * @Author : Cui
 * @Date: 2026/06/28 13:26
 * @Description DataSmart Govern Backend - QualityRemediationTaskDraftRequestFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Map;

/**
 * `quality.remediation.task.draft` 请求工厂。
 *
 * <p>Python Runtime 会把质量异常治理意图规划成 `remediationScope + dryRun` 形式的 ToolPlan，
 * 但 Java agent-runtime 不能把模型或 Python 传来的参数原样转发给 data-quality。该工厂承担
 * “执行前协议收口”的职责：</p>
 *
 * <p>1. 租户、项目、工作空间优先取 Java Session，防止模型伪造跨项目范围；</p>
 * <p>2. 只读取 `remediationScope` 白名单字段，忽略任何 payload、arguments、prompt 或 SQL 字段；</p>
 * <p>3. `dryRun` 永远强制为 true，当前阶段只生成治理任务预览，不真实创建 task-management 任务；</p>
 * <p>4. 优先级、重试次数、聚合数量都做边界裁剪，避免异常参数污染下游任务中心。</p>
 */
@Component
public class QualityRemediationTaskDraftRequestFactory {

    private static final String DEFAULT_REMEDIATION_TYPE = "MANUAL_REVIEW";
    private static final String DEFAULT_REASON = "基于低敏质量异常聚合生成治理任务草案，需人工复核后再提交。";
    private static final String DEFAULT_RECOMMENDATION = "MANUAL_REVIEW_AND_ASSIGN_OWNER";
    private static final String DEFAULT_PRIORITY = "MEDIUM";

    /**
     * 将工具审计里的 planArguments 转换成 data-quality dry-run 请求。
     *
     * @param context 当前工具执行上下文，包含 Session、Run、Audit 和 traceId。
     * @return 可序列化为 JSON 的受控请求模型。
     */
    public QualityRemediationTaskDraftRequest build(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Map<String, Object> scope = scope(arguments.get("remediationScope"));
        return new QualityRemediationTaskDraftRequest(
                firstNonNull(context.session().getTenantId(), longValue(scope.get("tenantId"))),
                firstNonNull(context.session().getProjectId(), longValue(scope.get("projectId"))),
                firstNonNull(context.session().getWorkspaceId(), longValue(scope.get("workspaceId"))),
                longValue(scope.get("reportId")),
                longValue(scope.get("ruleId")),
                stringValue(scope.get("anomalyType")),
                stringValue(scope.get("fieldName")),
                normalizeSeverity(scope.get("severity")),
                stringValue(scope.get("targetObject")),
                timeText(scope.get("startTime")),
                timeText(scope.get("endTime")),
                normalizeCode(firstNonNull(arguments.get("remediationType"), DEFAULT_REMEDIATION_TYPE)),
                boundedText(firstNonNull(arguments.get("reason"), DEFAULT_REASON), DEFAULT_REASON),
                boundedText(firstNonNull(arguments.get("recommendation"), DEFAULT_RECOMMENDATION),
                        DEFAULT_RECOMMENDATION),
                firstNonNull(longValue(scope.get("assigneeActorId")), longValue(arguments.get("assigneeActorId"))),
                normalizePriority(arguments.get("priority")),
                boundedInteger(arguments.get("maxRetryCount"), 3, 0, 20),
                boundedInteger(arguments.get("aggregationLimit"), 10, 1, 50),
                null,
                true
        );
    }

    /**
     * 提取 remediationScope。
     *
     * <p>如果调用方没有提供 scope，返回空 Map，让 data-quality 根据 projectId 或 reportId 等字段决定是否
     * 能生成预览。这里不会从其它不受控字段中“猜测”范围，避免把自由文本解析成错误的治理任务。</p>
     */
    private Map<String, Object> scope(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        return Map.ofEntries(
                entry("tenantId", rawMap.get("tenantId")),
                entry("projectId", rawMap.get("projectId")),
                entry("workspaceId", rawMap.get("workspaceId")),
                entry("reportId", rawMap.get("reportId")),
                entry("ruleId", rawMap.get("ruleId")),
                entry("anomalyType", rawMap.get("anomalyType")),
                entry("fieldName", rawMap.get("fieldName")),
                entry("severity", rawMap.get("severity")),
                entry("targetObject", rawMap.get("targetObject")),
                entry("startTime", rawMap.get("startTime")),
                entry("endTime", rawMap.get("endTime")),
                entry("assigneeActorId", rawMap.get("assigneeActorId"))
        );
    }

    private Map.Entry<String, Object> entry(String key, Object value) {
        return Map.entry(key, value == null ? "" : value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer boundedInteger(Object value, int defaultValue, int minValue, int maxValue) {
        int resolved = defaultValue;
        if (value instanceof Number number) {
            resolved = number.intValue();
        } else {
            String text = stringValue(value);
            if (text != null) {
                try {
                    resolved = Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    resolved = defaultValue;
                }
            }
        }
        return Math.max(minValue, Math.min(resolved, maxValue));
    }

    private String normalizePriority(Object value) {
        String normalized = normalizeCode(firstNonNull(value, DEFAULT_PRIORITY));
        if ("HIGH".equals(normalized) || "MEDIUM".equals(normalized) || "LOW".equals(normalized)) {
            return normalized;
        }
        return DEFAULT_PRIORITY;
    }

    private String normalizeSeverity(Object value) {
        String normalized = normalizeCode(value);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW" -> normalized;
            default -> null;
        };
    }

    private String normalizeCode(Object value) {
        String text = stringValue(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private String boundedText(Object value, String defaultValue) {
        String text = stringValue(value);
        if (text == null) {
            return defaultValue;
        }
        String cleaned = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (cleaned.length() > 200) {
            return cleaned.substring(0, 200);
        }
        return cleaned;
    }

    private String timeText(Object value) {
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        return stringValue(value);
    }

    private String stringValue(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
