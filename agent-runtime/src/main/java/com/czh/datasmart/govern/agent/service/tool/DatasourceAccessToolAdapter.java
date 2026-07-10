/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - DatasourceAccessToolAdapter.java
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 已登记数据源的连接测试与元数据发现工具。
 *
 * <p>数据源密码只能由用户在 datasource-management 的可信表单或未来 Vault/KMS 集成中提交，不能进入 prompt、
 * LangGraph state、AgentPlan、Run variables 或工具审计。Agent 只接收已经完成安全登记的数据源 ID，并在当前
 * 项目权限范围内执行连接测试和元数据发现。</p>
 */
@Component
@RequiredArgsConstructor
public class DatasourceAccessToolAdapter implements AgentToolAdapter {

    public static final String SOURCE_TEST = "datasource.source.connection.test";
    public static final String TARGET_TEST = "datasource.target.connection.test";
    public static final String SOURCE_METADATA = "datasource.source.metadata.read";
    public static final String TARGET_METADATA = "datasource.target.metadata.read";

    private static final String TARGET_SERVICE = "datasource-management";
    private static final Set<String> SUPPORTED = Set.of(SOURCE_TEST, TARGET_TEST, SOURCE_METADATA, TARGET_METADATA);

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;
    private final DatasourceMetadataReadResponseMapper metadataResponseMapper;

    @Override
    public boolean supports(String toolCode) {
        return SUPPORTED.contains(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        try {
            return switch (context.audit().getToolCode()) {
                case SOURCE_TEST, TARGET_TEST -> testDatasource(context);
                case SOURCE_METADATA, TARGET_METADATA -> discoverMetadata(context);
                default -> AgentToolExecutionOutcome.failed("DATASOURCE_TOOL_UNSUPPORTED", "不支持的数据源工具节点");
            };
        } catch (PlatformBusinessException exception) {
            return AgentToolExecutionOutcome.failed("DATASOURCE_TOOL_VALIDATION_FAILED", exception.getMessage());
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed("DATASOURCE_DOWNSTREAM_ERROR",
                    "调用 datasource-management 失败: " + exception.getMessage());
        }
    }

    private AgentToolExecutionOutcome testDatasource(AgentToolExecutionContext context) {
        Long datasourceId = resolveDatasourceId(context);
        Map<String, Object> response = restClientBuilder
                .baseUrl(httpSupport.baseUrl(TARGET_SERVICE))
                .build()
                .post()
                .uri("/datasources/{id}/test", datasourceId)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        Map<String, Object> data = requireSuccessData(response, "数据源连接测试");
        boolean success = isSuccessfulConnectionTest(data);
        if (!success) {
            return AgentToolExecutionOutcome.failed("DATASOURCE_CONNECTION_TEST_FAILED",
                    "数据源连接测试未通过: " + safeText(data.get("message"), "下游未返回具体原因"));
        }
        return AgentToolExecutionOutcome.succeeded("数据源连接测试通过。", Map.of(
                "datasourceId", datasourceId,
                "success", true,
                "databaseProductName", safeText(
                        data.get("productName") == null ? data.get("databaseProductName") : data.get("productName"),
                        "UNKNOWN"),
                "databaseProductVersion", safeText(
                        data.get("productVersion") == null ? data.get("databaseProductVersion") : data.get("productVersion"),
                        "UNKNOWN"),
                "latencyMs", defaultLong(data.get("latencyMs"))
        ));
    }

    static boolean isSuccessfulConnectionTest(Map<String, Object> data) {
        Object testStatus = data.get("testStatus");
        if (testStatus != null && "SUCCESS".equalsIgnoreCase(String.valueOf(testStatus).trim())) {
            return true;
        }
        Object legacySuccess = data.get("success");
        return legacySuccess instanceof Boolean bool
                ? bool
                : legacySuccess != null && Boolean.parseBoolean(String.valueOf(legacySuccess));
    }

    private AgentToolExecutionOutcome discoverMetadata(AgentToolExecutionContext context) {
        Long datasourceId = resolveDatasourceId(context);
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("actorId", httpSupport.numericActorId(context));
        request.put("actorRole", httpSupport.delegatedActorRole(context));
        request.put("actorTenantId", context.session().getTenantId());
        request.put("catalog", nullableText(arguments.get("catalog")));
        request.put("schemaPattern", nullableText(arguments.get("schemaPattern")));
        request.put("tableNamePattern", nullableText(arguments.get("tableNamePattern")));
        request.put("maxTables", 100);
        request.put("maxColumnsPerTable", 300);
        request.put("includeColumns", true);
        request.put("includeViews", false);
        request.put("includePrimaryKeys", true);
        request.put("includeIndexes", true);
        request.put("includeSampleRows", false);

        Map<String, Object> response = restClientBuilder
                .baseUrl(httpSupport.baseUrl(TARGET_SERVICE))
                .build()
                .post()
                .uri("/datasources/{id}/metadata/discover", datasourceId)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return metadataResponseMapper.toOutcome(datasourceId, response);
    }

    private Long resolveDatasourceId(AgentToolExecutionContext context) {
        Object value = context.audit().getPlanArguments().get("datasourceId");
        Long datasourceId = longValue(value);
        if (datasourceId == null || datasourceId <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "数据源工具必须提供有效 datasourceId");
        }
        return datasourceId;
    }

    private Map<String, Object> requireSuccessData(Map<String, Object> response, String action) {
        if (response == null) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, action + "返回空响应");
        }
        if (integerValue(response.get("code"), -1) != 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    action + "失败: " + safeText(response.get("message"), "下游未返回具体原因"));
        }
        if (!(response.get("data") instanceof Map<?, ?> rawData)) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, action + "响应缺少 data");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        rawData.forEach((key, item) -> data.put(String.valueOf(key), item));
        return data;
    }

    private String nullableText(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String safeText(Object value, String fallback) {
        String text = nullableText(value);
        return text == null ? fallback : text;
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

    private long defaultLong(Object value) {
        Long result = longValue(value);
        return result == null ? 0L : result;
    }

    private Integer integerValue(Object value, int fallback) {
        Long result = longValue(value);
        return result == null ? fallback : result.intValue();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
