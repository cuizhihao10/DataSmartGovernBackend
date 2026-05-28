/**
 * @Author : Cui
 * @Date: 2026/05/24 22:19
 * @Description DataSmart Govern Backend - QualityRuleSuggestRequestFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `quality.rule.suggest` 请求构建器。
 *
 * <p>该类负责把 Python ToolPlan 的自由参数，收敛为 data-quality 能理解的规则草案请求。
 * 这里的关键原则是：Agent 可以提出意图和候选参数，但 Java 控制面必须补齐租户/项目边界、
 * 校验必需字段，并限制返回数量，不能把模型输出原样透传到业务服务。</p>
 */
@Component
public class QualityRuleSuggestRequestFactory {

    private static final int DEFAULT_MAX_SUGGESTIONS = 8;
    private static final int AGENT_MAX_SUGGESTIONS = 12;
    private static final String DATASOURCE_METADATA_TOOL_CODE = "datasource.metadata.read";

    private final AgentToolOutputReferenceResolver outputReferenceResolver;

    public QualityRuleSuggestRequestFactory(AgentToolOutputReferenceResolver outputReferenceResolver) {
        this.outputReferenceResolver = outputReferenceResolver;
    }

    /**
     * 构建规则草案请求。
     *
     * @param context 工具执行上下文
     * @return 可发送给 data-quality 的本地请求契约
     */
    public QualityRuleSuggestRequest build(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Long datasourceId = longValue(firstNonNull(arguments.get("datasourceId"), context.variables().get("datasourceId")));
        if (datasourceId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "执行 quality.rule.suggest 必须提供 datasourceId");
        }
        String businessGoal = stringValue(firstNonNull(arguments.get("businessGoal"), context.run().getUserInputPreview()));
        if (businessGoal == null || businessGoal.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "执行 quality.rule.suggest 必须提供 businessGoal");
        }
        return new QualityRuleSuggestRequest(
                context.session().getTenantId(),
                context.session().getProjectId(),
                context.session().getWorkspaceId(),
                datasourceId,
                stringValue(arguments.get("tableName")),
                businessGoal,
                metadata(context, arguments),
                boundedInteger(arguments.get("maxSuggestions"), DEFAULT_MAX_SUGGESTIONS, AGENT_MAX_SUGGESTIONS)
        );
    }

    /**
     * 解析元数据快照。
     *
     * <p>为了兼容不同阶段的 Python Runtime 和工具链输出，这里支持多个参数名：
     * `metadata`、`datasourceMetadata`、`metadataSnapshot`。
     * 如果 Python AgentPlan 没有显式传入 metadata，则优先解析显式 outputRef：
     * `metadataRef` / `datasourceMetadataRef` / `outputRef`。
     * 如果仍没有引用对象，再兼容读取同一 Run 的 `datasource.metadata.read` 最近成功输出。</p>
     */
    private Map<String, Object> metadata(AgentToolExecutionContext context, Map<String, Object> arguments) {
        Object value = firstNonNull(
                arguments.get("metadata"),
                firstNonNull(arguments.get("datasourceMetadata"), arguments.get("metadataSnapshot"))
        );
        if (!(value instanceof Map<?, ?>)) {
            Object reference = firstNonNull(arguments.get("metadataRef"),
                    firstNonNull(arguments.get("datasourceMetadataRef"), arguments.get("outputRef")));
            value = outputReferenceResolver
                    .resolve(context, reference, DATASOURCE_METADATA_TOOL_CODE, "metadata")
                    .orElse(null);
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            metadata.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return metadata;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer boundedInteger(Object value, int defaultValue, int maxValue) {
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
        if (resolved < 1) {
            resolved = defaultValue;
        }
        return Math.min(resolved, maxValue);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
