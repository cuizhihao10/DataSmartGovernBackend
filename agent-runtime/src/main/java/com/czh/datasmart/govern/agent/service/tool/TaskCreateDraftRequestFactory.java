/**
 * @Author : Cui
 * @Date: 2026/05/24 23:10
 * @Description DataSmart Govern Backend - TaskCreateDraftRequestFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * `task.create.draft` 请求工厂。
 *
 * <p>Agent 规划层通常只会给出“我要为质量规则生成一个扫描任务”这样的意图，
 * 但商业化后端不能把模型参数原样交给任务中心。该工厂负责把自由参数整理为受控草稿请求：</p>
 *
 * <p>1. 租户、项目、工作空间只信任 Java Session，不信任模型传入值；</p>
 * <p>2. objective 可以来自 ToolPlan，也可以退化使用 Run 的用户输入摘要；</p>
 * <p>3. taskType 优先使用显式参数，如果前序存在质量规则建议，则可推断为 DATA_QUALITY_SCAN；</p>
 * <p>4. 前序 `quality.rule.suggest` 输出会被读取为任务草稿来源，避免模型重复复制大 JSON；</p>
 * <p>5. retry/defer/priority 在草稿阶段先做保守归一，真实落库时仍要由 task-management 复核。</p>
 */
@Component
public class TaskCreateDraftRequestFactory {

    private static final String QUALITY_RULE_SUGGEST_TOOL_CODE = "quality.rule.suggest";
    private static final String DEFAULT_QUALITY_TASK_TYPE = "DATA_QUALITY_SCAN";
    private static final String DEFAULT_MANUAL_TASK_TYPE = "MANUAL_REVIEW";
    private static final String DEFAULT_PRIORITY = "MEDIUM";
    private static final int DEFAULT_MAX_RETRY_COUNT = 3;
    private static final int DEFAULT_MAX_DEFER_COUNT = 20;

    private final AgentToolOutputReferenceResolver outputReferenceResolver;

    public TaskCreateDraftRequestFactory(AgentToolOutputReferenceResolver outputReferenceResolver) {
        this.outputReferenceResolver = outputReferenceResolver;
    }

    /**
     * 构建任务草稿请求。
     *
     * @param context 工具执行上下文，包含会话、Run、审计和计划参数。
     * @return 可交给草稿适配器继续组装输出的内部请求。
     */
    public TaskCreateDraftRequest build(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Map<String, Object> sourceSuggestion = sourceSuggestion(context, arguments);
        String objective = stringValue(firstNonNull(arguments.get("objective"), context.run().getUserInputPreview()));
        if (objective == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "执行 task.create.draft 必须提供 objective，用于说明任务要解决的业务目标");
        }

        return new TaskCreateDraftRequest(
                context.session().getTenantId(),
                context.session().getProjectId(),
                context.session().getWorkspaceId(),
                resolveTaskType(arguments, sourceSuggestion),
                objective,
                normalizePriority(arguments.get("priority")),
                boundedInteger(arguments.get("maxRetryCount"), DEFAULT_MAX_RETRY_COUNT, 0, 20),
                boundedInteger(arguments.get("maxDeferCount"), DEFAULT_MAX_DEFER_COUNT, 0, 10_000),
                sourceSuggestion
        );
    }

    /**
     * 读取前序质量规则建议输出。
     *
     * <p>这里同时支持显式参数和 Run 内输出仓储两种来源：
     * 显式参数适合 Python Runtime 已经把来源对象精确放入 ToolPlan 的场景。
     * 如果没有显式对象，则优先解析 `suggestionRef` / `qualityRuleSuggestionRef` / `outputRef`；
     * 最后才回退到同一 Run 内最近一次 `quality.rule.suggest` 输出。</p>
     */
    private Map<String, Object> sourceSuggestion(AgentToolExecutionContext context, Map<String, Object> arguments) {
        Object value = firstNonNull(arguments.get("suggestion"), arguments.get("qualityRuleSuggestion"));
        if (!(value instanceof Map<?, ?>)) {
            Object reference = firstNonNull(arguments.get("suggestionRef"),
                    firstNonNull(arguments.get("qualityRuleSuggestionRef"), arguments.get("outputRef")));
            value = outputReferenceResolver
                    .resolve(context, reference, QUALITY_RULE_SUGGEST_TOOL_CODE, "suggestion")
                    .orElse(null);
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    /**
     * 解析任务类型。
     *
     * <p>如果用户显式指定 taskType/type，则尊重输入并统一转大写。
     * 如果没有显式指定，但前序存在规则草案，说明当前最自然的任务就是质量扫描任务。
     * 如果两者都没有，则退回 MANUAL_REVIEW，表示这只是一个人工复核任务草稿，不会误导执行器认领。</p>
     */
    private String resolveTaskType(Map<String, Object> arguments, Map<String, Object> sourceSuggestion) {
        String explicit = stringValue(firstNonNull(arguments.get("taskType"), arguments.get("type")));
        if (explicit != null) {
            return explicit.toUpperCase(Locale.ROOT);
        }
        if (!sourceSuggestion.isEmpty()) {
            return DEFAULT_QUALITY_TASK_TYPE;
        }
        return DEFAULT_MANUAL_TASK_TYPE;
    }

    private String normalizePriority(Object value) {
        String priority = stringValue(value);
        if (priority == null) {
            return DEFAULT_PRIORITY;
        }
        String normalized = priority.toUpperCase(Locale.ROOT);
        if ("HIGH".equals(normalized) || "MEDIUM".equals(normalized) || "LOW".equals(normalized)) {
            return normalized;
        }
        return DEFAULT_PRIORITY;
    }

    private Integer boundedInteger(Object value, int defaultValue, int minValue, int maxValue) {
        int resolved = defaultValue;
        if (value instanceof Number number) {
            resolved = number.intValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                resolved = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                resolved = defaultValue;
            }
        }
        return Math.max(minValue, Math.min(resolved, maxValue));
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
