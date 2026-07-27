/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - CdcReadinessToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the read-only CDC admission probe from same-run datasource evidence.
 *
 * <p>Datasource IDs are derived from successful metadata tool outputs. The model may propose
 * object mappings, but cannot replace the source/target resources or provide probe SQL.</p>
 */
@Component
@RequiredArgsConstructor
public class CdcReadinessToolAdapter implements AgentToolAdapter {

    public static final String TOOL_CODE = "sync.cdc.readiness.check";

    private static final String DATASOURCE_SERVICE = "datasource-management";

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;
    private final AgentToolOutputReferenceResolver referenceResolver;

    @Override
    public boolean supports(String toolCode) {
        return TOOL_CODE.equals(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        try {
            Map<String, Object> arguments = context.audit().getPlanArguments();
            Long sourceDatasourceId = datasourceId(
                    context, arguments.get("sourceMetadataRef"),
                    DatasourceAccessToolAdapter.SOURCE_METADATA, "缺少可信源端元数据引用");
            Long targetDatasourceId = datasourceId(
                    context, arguments.get("targetMetadataRef"),
                    DatasourceAccessToolAdapter.TARGET_METADATA, "缺少可信目标端元数据引用");

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("targetDatasourceId", targetDatasourceId);
            request.put("objectMappings", boundedObjectMappings(arguments.get("objectMappings")));
            Map<String, Object> response = restClientBuilder
                    .baseUrl(httpSupport.baseUrl(DATASOURCE_SERVICE))
                    .build()
                    .post()
                    .uri("/datasources/{sourceDatasourceId}/cdc-readiness/check", sourceDatasourceId)
                    .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            Map<String, Object> result = requireSuccessData(response);
            result.put("sourceDatasourceId", sourceDatasourceId);
            result.put("targetDatasourceId", targetDatasourceId);
            boolean ready = booleanValue(result.get("ready"));
            return AgentToolExecutionOutcome.succeeded(
                    ready
                            ? "实时同步 CDC 准入检查已通过。"
                            : "实时同步 CDC 准入检查完成，但仍有阻断项，系统不会创建或运行实时任务。",
                    result);
        } catch (PlatformBusinessException exception) {
            return AgentToolExecutionOutcome.failed("CDC_READINESS_VALIDATION_FAILED", exception.getMessage());
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed(
                    "CDC_READINESS_DOWNSTREAM_ERROR",
                    "调用 datasource-management CDC 准入检查失败: " + safeMessage(exception));
        }
    }

    private Long datasourceId(AgentToolExecutionContext context,
                              Object reference,
                              String defaultTool,
                              String missingMessage) {
        Object value = referenceResolver.resolve(
                        context, referenceWithPath(reference, "datasourceId"), defaultTool, "datasourceId")
                .orElse(null);
        Long id = longValue(value);
        if (id == null || id <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, missingMessage);
        }
        return id;
    }

    private List<Map<String, Object>> boundedObjectMappings(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST, "CDC 准入检查至少需要一条源表到目标表映射");
        }
        if (values.size() > 100) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST, "单次 CDC 准入检查最多支持 100 条对象映射");
        }
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (Object valueItem : values) {
            if (!(valueItem instanceof Map<?, ?> raw)) {
                throw new PlatformBusinessException(
                        PlatformErrorCode.BAD_REQUEST, "CDC 对象映射格式无效");
            }
            Map<String, Object> item = copyMap(raw);
            String sourceObjectName = requiredText(item.get("sourceObjectName"), "CDC 源表名称不能为空");
            String targetObjectName = requiredText(item.get("targetObjectName"), "CDC 目标表名称不能为空");
            Map<String, Object> mapping = new LinkedHashMap<>();
            putIfPresent(mapping, "sourceSchemaName", item.get("sourceSchemaName"));
            mapping.put("sourceObjectName", sourceObjectName);
            putIfPresent(mapping, "targetSchemaName", item.get("targetSchemaName"));
            mapping.put("targetObjectName", targetObjectName);
            mappings.add(mapping);
        }
        return List.copyOf(mappings);
    }

    private Map<String, Object> requireSuccessData(Map<String, Object> response) {
        if (response == null) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.INTERNAL_ERROR, "CDC 准入检查返回空响应");
        }
        if (integerValue(response.get("code"), -1) != 0) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "CDC 准入检查失败: " + requiredText(response.get("message"), "下游未返回具体原因"));
        }
        if (!(response.get("data") instanceof Map<?, ?> raw)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.INTERNAL_ERROR, "CDC 准入检查响应缺少 data");
        }
        return copyMap(raw);
    }

    private Object referenceWithPath(Object reference, String path) {
        if (!(reference instanceof Map<?, ?> raw)) {
            return reference;
        }
        Map<String, Object> result = copyMap(raw);
        result.put("jsonPath", path);
        result.remove("path");
        return result;
    }

    private Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, nested) -> result.put(String.valueOf(key), nested));
        return result;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, String.valueOf(value).trim());
        }
    }

    private String requiredText(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return text;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int integerValue(Object value, int fallback) {
        Long parsed = longValue(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
