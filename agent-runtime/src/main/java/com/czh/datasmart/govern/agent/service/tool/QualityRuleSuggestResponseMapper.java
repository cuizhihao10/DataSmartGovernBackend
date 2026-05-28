/**
 * @Author : Cui
 * @Date: 2026/05/24 22:20
 * @Description DataSmart Govern Backend - QualityRuleSuggestResponseMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * data-quality 规则草案建议响应映射器。
 *
 * <p>和元数据读取工具一样，Agent 工具输出不能只是远端响应原样透传。
 * 这里会把 data-quality 的统一响应转换为稳定的工具执行结果，并额外生成摘要，
 * 方便审计、前端确认页和后续 Agent 编排优先消费。</p>
 */
@Component
public class QualityRuleSuggestResponseMapper {

    public AgentToolExecutionOutcome toOutcome(Long datasourceId, Map<String, Object> response) {
        if (response == null) {
            return AgentToolExecutionOutcome.failed("QUALITY_RULE_SUGGEST_EMPTY_RESPONSE",
                    "data-quality 返回空响应，无法确认规则草案是否生成成功");
        }
        int code = integerValue(response.get("code"), 0);
        if (code != 0) {
            return AgentToolExecutionOutcome.failed("QUALITY_RULE_SUGGEST_FAILED",
                    "data-quality 规则草案生成失败: " + safeMessage(response.get("message")));
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> rawData)) {
            return AgentToolExecutionOutcome.failed("QUALITY_RULE_SUGGEST_MISSING_DATA",
                    "data-quality 响应缺少 data，工具无法生成规则草案摘要");
        }

        Map<String, Object> suggestion = copyStringKeyMap(rawData);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("datasourceId", datasourceId);
        summary.put("suggestionCount", integerValue(suggestion.get("suggestionCount"), 0));
        summary.put("generationStrategy", suggestion.get("generationStrategy"));
        summary.put("warnings", suggestion.get("warnings"));
        summary.put("recommendedActions", suggestion.get("recommendedActions"));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("datasourceId", datasourceId);
        output.put("remoteMessage", response.get("message"));
        output.put("summary", summary);
        output.put("suggestion", suggestion);
        return AgentToolExecutionOutcome.succeeded("质量规则草案建议生成成功，等待用户确认后再保存为 DRAFT。", output);
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private int integerValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String safeMessage(Object value) {
        if (value == null) {
            return "下游未返回错误说明";
        }
        String message = String.valueOf(value);
        return message.isBlank() ? "下游未返回错误说明" : message;
    }
}
