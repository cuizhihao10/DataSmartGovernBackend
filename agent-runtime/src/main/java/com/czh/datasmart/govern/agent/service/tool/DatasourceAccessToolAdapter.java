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

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
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

    public static final String SOURCE_CATALOG_SEARCH = "datasource.source.catalog.search";
    public static final String TARGET_CATALOG_SEARCH = "datasource.target.catalog.search";
    public static final String SOURCE_TEST = "datasource.source.connection.test";
    public static final String TARGET_TEST = "datasource.target.connection.test";
    public static final String SOURCE_METADATA = "datasource.source.metadata.read";
    public static final String TARGET_METADATA = "datasource.target.metadata.read";

    private static final String TARGET_SERVICE = "datasource-management";
    private static final Set<String> SUPPORTED = Set.of(
            SOURCE_CATALOG_SEARCH,
            TARGET_CATALOG_SEARCH,
            SOURCE_TEST,
            TARGET_TEST,
            SOURCE_METADATA,
            TARGET_METADATA
    );

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;
    private final DatasourceMetadataReadResponseMapper metadataResponseMapper;
    private final AgentToolOutputReferenceResolver referenceResolver;

    @Override
    public boolean supports(String toolCode) {
        return SUPPORTED.contains(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        try {
            return switch (context.audit().getToolCode()) {
                case SOURCE_CATALOG_SEARCH, TARGET_CATALOG_SEARCH -> searchDatasourceCatalog(context);
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

    /**
     * 按用户在自然语言中提供的数据源名称检索当前项目的授权目录。
     *
     * <p>该工具只返回低敏候选，不返回 JDBC URL、用户名、密码或凭据密文。自动继续的条件刻意设置为
     * “唯一精确名称匹配”：模糊匹配只有一个结果也不会直接选中，避免模型把相似名称的数据源用于真实同步。
     * 多个候选或无候选会作为结构化结果回填模型，由 Agent 精准追问用户。</p>
     */
    private AgentToolExecutionOutcome searchDatasourceCatalog(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        String keyword = nullableText(arguments.get("keyword"));
        if (keyword == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "数据源目录检索必须提供用户提到的数据源名称");
        }
        String usagePurpose = SOURCE_CATALOG_SEARCH.equals(context.audit().getToolCode())
                ? "SOURCE"
                : "TARGET";
        Map<String, Object> response = restClientBuilder
                .baseUrl(httpSupport.baseUrl(TARGET_SERVICE))
                .build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/datasources")
                        .queryParam("current", 1)
                        .queryParam("size", 20)
                        .queryParam("usagePurpose", usagePurpose)
                        .queryParam("status", "ENABLED")
                        .queryParam("keyword", keyword)
                        .build())
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        Map<String, Object> page = requireSuccessData(response, "数据源目录检索");
        List<Map<String, Object>> records = mapList(page.get("records"));
        return AgentToolExecutionOutcome.succeeded(
                "数据源目录检索完成。",
                buildCatalogSearchOutput(keyword, usagePurpose, records)
        );
    }

    /**
     * 把数据源分页结果收敛成允许进入模型上下文的低敏候选。
     *
     * <p>该方法保持 package-private，便于单元测试直接保护“唯一精确匹配才能自动解析”的产品语义。</p>
     */
    static Map<String, Object> buildCatalogSearchOutput(
            String keyword,
            String usagePurpose,
            List<Map<String, Object>> records) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Map<String, Object>> exactMatches = new ArrayList<>();
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> record : records.stream().limit(10).toList()) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("datasourceId", record.get("id"));
            candidate.put("name", record.get("name"));
            candidate.put("type", record.get("type"));
            candidate.put("usagePurpose", record.get("usagePurpose"));
            candidate.put("status", record.get("status"));
            candidates.add(candidate);
            Object name = record.get("name");
            if (name != null && String.valueOf(name).trim().toLowerCase(Locale.ROOT).equals(normalizedKeyword)) {
                exactMatches.add(candidate);
            }
        }

        String matchStatus;
        if (exactMatches.size() == 1) {
            matchStatus = "EXACT";
        } else if (!candidates.isEmpty()) {
            matchStatus = "AMBIGUOUS";
        } else {
            matchStatus = "NOT_FOUND";
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("keyword", keyword);
        output.put("usagePurpose", usagePurpose);
        output.put("matchStatus", matchStatus);
        output.put("candidateCount", candidates.size());
        output.put("exactMatchCount", exactMatches.size());
        output.put("candidates", candidates);
        if (exactMatches.size() == 1) {
            output.put("resolvedDatasourceId", exactMatches.getFirst().get("datasourceId"));
            output.put("resolvedDatasourceName", exactMatches.getFirst().get("name"));
            output.put("resolvedDatasourceType", exactMatches.getFirst().get("type"));
        }
        output.put("requiresUserChoice", !"EXACT".equals(matchStatus));
        return output;
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
        Map<String, Object> arguments = context.audit().getPlanArguments();
        Long explicitDatasourceId = longValue(arguments.get("datasourceId"));
        DatasourceReferenceRule referenceRule = datasourceReferenceRule(context.audit().getToolCode());
        Long referencedDatasourceId = null;
        if (referenceRule != null && arguments.containsKey(referenceRule.argumentName())) {
            Object referencedValue = referenceResolver.resolve(
                            context,
                            arguments.get(referenceRule.argumentName()),
                            referenceRule.sourceToolCode(),
                            referenceRule.outputPath())
                    .orElseThrow(() -> new PlatformBusinessException(
                            PlatformErrorCode.BAD_REQUEST,
                            "无法从同一 Agent 会话的受控工具结果解析数据源 ID: "
                                    + referenceRule.sourceToolCode() + "." + referenceRule.outputPath()));
            referencedDatasourceId = longValue(referencedValue);
            if (referencedDatasourceId == null || referencedDatasourceId <= 0) {
                throw new PlatformBusinessException(
                        PlatformErrorCode.BAD_REQUEST,
                        "上游受控工具结果没有返回有效数据源 ID: "
                                + referenceRule.sourceToolCode() + "." + referenceRule.outputPath());
            }
        }
        Long datasourceId = selectTrustedDatasourceId(explicitDatasourceId, referencedDatasourceId);
        if (datasourceId == null || datasourceId <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "数据源工具必须提供有效 datasourceId");
        }
        return datasourceId;
    }

    /**
     * 选择当前节点真正允许使用的数据源 ID。
     *
     * <p>模型可能在后续轮次中重复携带 datasourceId，但连接测试和元数据读取的真实对象必须由上一节点
     * 的控制面输出决定。只要存在同会话引用，就无条件覆盖模型参数；没有引用时才兼容用户在结构化向导中
     * 已明确选择的数据源 ID。该规则既保留手工向导兼容性，也阻止模型把目标端误写成源端。</p>
     */
    static Long selectTrustedDatasourceId(Long explicitDatasourceId, Long referencedDatasourceId) {
        return referencedDatasourceId != null && referencedDatasourceId > 0
                ? referencedDatasourceId
                : explicitDatasourceId;
    }

    private DatasourceReferenceRule datasourceReferenceRule(String toolCode) {
        return switch (toolCode) {
            case SOURCE_TEST -> new DatasourceReferenceRule(
                    "catalogSearchRef", SOURCE_CATALOG_SEARCH, "resolvedDatasourceId");
            case TARGET_TEST -> new DatasourceReferenceRule(
                    "catalogSearchRef", TARGET_CATALOG_SEARCH, "resolvedDatasourceId");
            case SOURCE_METADATA -> new DatasourceReferenceRule(
                    "connectionTestRef", SOURCE_TEST, "datasourceId");
            case TARGET_METADATA -> new DatasourceReferenceRule(
                    "connectionTestRef", TARGET_TEST, "datasourceId");
            default -> null;
        };
    }

    /**
     * 描述一个数据源生命周期节点应当消费的受控上游引用。
     */
    private record DatasourceReferenceRule(
            String argumentName,
            String sourceToolCode,
            String outputPath) {
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

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, nestedValue) -> copy.put(String.valueOf(key), nestedValue));
            result.add(copy);
        }
        return result;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
