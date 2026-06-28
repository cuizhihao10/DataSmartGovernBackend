/**
 * @Author : Cui
 * @Date: 2026/06/28 23:10
 * @Description DataSmart Govern Backend - QualityRemediationTaskSubmissionRequestBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadRecord;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 质量治理真实提交请求构建器。
 *
 * <p>`quality.remediation.task.draft` 的 dry-run 结果已经被物化到 `agent-payload:`，其中包含
 * data-quality 返回的低敏治理草案。真实提交时不能让 task-management worker 直接拿正文，也不能让模型重新生成参数；
 * 本构建器只在 agent-runtime Host 内部读取 payload body，并把它收口成 data-quality
 * `/quality-rules/remediation-tasks` 可消费的请求，且显式设置 `dryRun=false`。</p>
 *
 * <p>字段来源原则：</p>
 * <p>1. tenant/project/actor 优先使用 payload record 元数据，而不是 payload body；</p>
 * <p>2. report/rule/severity/anomaly/field/target 等低敏筛选条件来自草案中的 scope 或 payloadPreview.filters；</p>
 * <p>3. reason/recommendation 只允许短文本，遇到 SQL、prompt、token、URL 等风险片段直接拒绝；</p>
 * <p>4. 不读取 topFields/topTypes/topSeverities，因为真实提交时 data-quality 会重新按当前数据库事实统计聚合。</p>
 */
@Component
public class QualityRemediationTaskSubmissionRequestBuilder {

    private static final String DEFAULT_REMEDIATION_TYPE = "MANUAL_REVIEW";
    private static final String DEFAULT_REASON = "经 Agent Host 审批确认后提交质量异常治理任务。";
    private static final String DEFAULT_RECOMMENDATION = "MANUAL_REVIEW_AND_ASSIGN_OWNER";
    private static final String DEFAULT_PRIORITY = "MEDIUM";

    /**
     * 从服务端 payload 记录构造真实提交请求。
     *
     * @param payloadRecord 已通过 TTL 与作用域复核的 payload 记录。
     * @param commandPayload command envelope 白名单字段，用于补充 priority/maxRetryCount 等低敏执行参数。
     * @return `dryRun=false` 的 data-quality 请求。
     */
    public QualityRemediationTaskDraftRequest build(AgentToolActionPayloadRecord payloadRecord,
                                                    Map<String, Object> commandPayload) {
        if (payloadRecord == null || !Boolean.TRUE.equals(payloadRecord.payloadBodyAvailable())) {
            throw new IllegalStateException("质量治理真实提交需要已物化的 agent-payload body");
        }
        Map<String, Object> body = payloadRecord.payloadBody();
        Map<String, Object> draft = map(body.get("remediationTaskDraft"));
        Map<String, Object> scope = map(draft.get("scope"));
        Map<String, Object> preview = map(draft.get("lowSensitivePayloadPreview"));
        Map<String, Object> filters = map(preview.get("filters"));
        return new QualityRemediationTaskDraftRequest(
                requiredLong(payloadRecord.tenantId(), "tenantId"),
                requiredLong(payloadRecord.projectId(), "projectId"),
                firstLong(scope.get("workspaceId"), preview.get("workspaceId"), filters.get("workspaceId")),
                firstLong(scope.get("reportId"), preview.get("reportId"), filters.get("reportId")),
                firstLong(scope.get("ruleId"), preview.get("ruleId"), filters.get("ruleId")),
                safeCode(firstText(scope.get("anomalyType"), filters.get("anomalyType")), 80),
                safeBusinessText(firstText(scope.get("fieldName"), filters.get("fieldName")), 128, null),
                normalizeSeverity(firstText(scope.get("severity"), preview.get("severity"), filters.get("severity"))),
                safeBusinessText(firstText(scope.get("targetObject"), preview.get("targetObject"),
                        filters.get("targetObject")), 256, null),
                safeBusinessText(firstText(filters.get("startTime")), 80, null),
                safeBusinessText(firstText(filters.get("endTime")), 80, null),
                safeCode(firstText(preview.get("remediationType"), draft.get("remediationType")),
                        80, DEFAULT_REMEDIATION_TYPE),
                safeBusinessText(firstText(preview.get("reason"), draft.get("reason")),
                        200, DEFAULT_REASON),
                safeBusinessText(firstText(preview.get("recommendation"), draft.get("recommendation")),
                        200, DEFAULT_RECOMMENDATION),
                firstLong(preview.get("assigneeActorId"), draft.get("assigneeActorId")),
                safeCode(firstText(commandPayload.get("priority"), draft.get("priority")),
                        20, DEFAULT_PRIORITY),
                boundedInteger(commandPayload.get("maxRetryCount"), 3, 0, 20),
                boundedInteger(commandPayload.get("aggregationLimit"), 10, 1, 50),
                false
        );
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        return rawMap.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()),
                        Map.Entry::getValue,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
    }

    private Long requiredLong(String value, String fieldName) {
        Long resolved = firstLong(value);
        if (resolved == null) {
            throw new IllegalStateException("payload record 缺少 " + fieldName + "，不能提交真实质量治理任务");
        }
        return resolved;
    }

    private Long firstLong(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            String text = firstText(value);
            if (text == null) {
                continue;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // 尝试下一个候选值。
            }
        }
        return null;
    }

    private Integer boundedInteger(Object value, int defaultValue, int minValue, int maxValue) {
        int resolved = defaultValue;
        if (value instanceof Number number) {
            resolved = number.intValue();
        } else {
            String text = firstText(value);
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

    private String normalizeSeverity(String value) {
        String code = safeCode(value, 20, null);
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW" -> code;
            default -> null;
        };
    }

    private String safeCode(String value, int maxLength) {
        return safeCode(value, maxLength, null);
    }

    private String safeCode(String value, int maxLength, String fallback) {
        String text = firstText(value);
        if (text == null) {
            return fallback;
        }
        String normalized = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String safeBusinessText(String value, int maxLength, String fallback) {
        String text = firstText(value);
        if (text == null) {
            return fallback;
        }
        String cleaned = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (looksSensitive(cleaned)) {
            throw new IllegalStateException("质量治理提交字段疑似包含 SQL、prompt、token、凭据或内部 endpoint，已拒绝提交");
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private boolean looksSensitive(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("prompt")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }
}
