/**
 * @Author : Cui
 * @Date: 2026/07/27 18:00
 * @Description DataSmart Govern Backend - TargetTableCreateToolAdapter.java
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates a missing target table from a trusted source metadata snapshot.
 *
 * <p>The model can choose the source/target object names, but it cannot provide raw DDL or
 * authoritative column definitions. Columns are copied from a same-run metadata result, reduced
 * to a type allow-list, previewed by datasource-management, and bound to a confirmation digest.
 * Apply then consumes only that preview and refreshes target metadata for the task draft tool.</p>
 */
@Component
@RequiredArgsConstructor
public class TargetTableCreateToolAdapter implements AgentToolAdapter {

    public static final String PREVIEW = "datasource.target-table.create.preview";
    public static final String APPLY = "datasource.target-table.create.apply";

    private static final String DATASOURCE_SERVICE = "datasource-management";
    private static final Set<String> SUPPORTED = Set.of(PREVIEW, APPLY);

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;
    private final AgentToolOutputReferenceResolver referenceResolver;
    private final DatasourceMetadataReadResponseMapper metadataResponseMapper;

    @Override
    public boolean supports(String toolCode) {
        return SUPPORTED.contains(toolCode);
    }

    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        try {
            return switch (context.audit().getToolCode()) {
                case PREVIEW -> preview(context);
                case APPLY -> apply(context);
                default -> AgentToolExecutionOutcome.failed(
                        "TARGET_TABLE_CREATE_TOOL_UNSUPPORTED", "不支持的目标表创建工具");
            };
        } catch (PlatformBusinessException exception) {
            return AgentToolExecutionOutcome.failed(
                    "TARGET_TABLE_CREATE_VALIDATION_FAILED", exception.getMessage());
        } catch (RestClientException exception) {
            return AgentToolExecutionOutcome.failed(
                    "TARGET_TABLE_CREATE_DOWNSTREAM_ERROR",
                    "调用 datasource-management 创建目标表失败: " + safeMessage(exception));
        }
    }

    private AgentToolExecutionOutcome preview(AgentToolExecutionContext context) {
        Map<String, Object> arguments = context.audit().getPlanArguments();
        MetadataReference source = metadataReference(
                context,
                arguments.get("sourceMetadataRef"),
                DatasourceAccessToolAdapter.SOURCE_METADATA,
                "缺少同一 Agent Run 内的源端元数据引用");
        MetadataReference target = metadataReference(
                context,
                arguments.get("targetMetadataRef"),
                DatasourceAccessToolAdapter.TARGET_METADATA,
                "缺少同一 Agent Run 内的目标端元数据引用");

        String sourceSchemaName = nullableText(arguments.get("sourceSchemaName"));
        String sourceTableName = requiredText(arguments.get("sourceTableName"), "创建目标表必须指定源表名称");
        String targetSchemaName = nullableText(arguments.get("targetSchemaName"));
        String targetTableName = requiredText(arguments.get("targetTableName"), "创建目标表必须指定目标表名称");

        Map<String, Object> sourceTable = findTable(
                source.metadata(), sourceSchemaName, sourceTableName, "源端");
        ensureCompleteSourceTable(sourceTable, sourceSchemaName, sourceTableName);
        ensureTargetTableAbsent(target.metadata(), targetSchemaName, targetTableName);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operation", "CREATE_TABLE");
        putIfPresent(request, "schemaName", targetSchemaName);
        request.put("tableName", targetTableName);
        request.put("columns", trustedColumns(sourceTable));

        Map<String, Object> data = postData(
                context,
                "/datasources/{datasourceId}/schema-repair-plans/preview",
                target.datasourceId(),
                request,
                "目标表创建预览");
        Map<String, Object> output = new LinkedHashMap<>(data);
        output.put("sourceDatasourceId", source.datasourceId());
        output.put("targetDatasourceId", target.datasourceId());
        putIfPresent(output, "sourceSchemaName", sourceSchemaName);
        output.put("sourceTableName", sourceTableName);
        putIfPresent(output, "targetSchemaName", targetSchemaName);
        output.put("targetTableName", targetTableName);
        output.put("sourceMetadataRef", arguments.get("sourceMetadataRef"));
        return AgentToolExecutionOutcome.succeeded(
                "已根据真实源表结构生成目标空表创建预览，尚未执行任何 DDL。", output);
    }

    private AgentToolExecutionOutcome apply(AgentToolExecutionContext context) {
        Map<String, Object> preview = referencedMap(
                context,
                context.audit().getPlanArguments().get("previewRef"),
                PREVIEW,
                "缺少目标表创建预览");
        Long datasourceId = requiredLong(
                preview.get("targetDatasourceId") == null
                        ? preview.get("datasourceId")
                        : preview.get("targetDatasourceId"),
                "目标表创建预览缺少目标数据源 ID");

        Map<String, Object> applyRequest = Map.of(
                "planId", requiredLong(preview.get("planId"), "目标表创建预览缺少 planId"),
                "confirmationDigest", requiredText(
                        preview.get("confirmationDigest"), "目标表创建预览缺少确认摘要"),
                "confirmed", true);
        Map<String, Object> applied = postData(
                context,
                "/datasources/{datasourceId}/schema-repair-plans/apply",
                datasourceId,
                applyRequest,
                "目标表创建应用");

        String targetSchemaName = nullableText(preview.get("targetSchemaName"));
        String targetTableName = requiredText(preview.get("targetTableName"), "目标表创建预览缺少目标表名称");
        Map<String, Object> metadataRequest = new LinkedHashMap<>();
        metadataRequest.put("actorId", httpSupport.numericActorId(context));
        metadataRequest.put("actorRole", httpSupport.delegatedActorRole(context));
        metadataRequest.put("actorTenantId", context.session().getTenantId());
        putIfPresent(metadataRequest, "schemaPattern", targetSchemaName);
        metadataRequest.put("tableNamePattern", targetTableName);
        metadataRequest.put("maxTables", 5);
        metadataRequest.put("maxColumnsPerTable", 300);
        metadataRequest.put("includeColumns", true);
        metadataRequest.put("includeViews", false);
        metadataRequest.put("includePrimaryKeys", true);
        metadataRequest.put("includeIndexes", true);
        metadataRequest.put("includeSampleRows", false);

        Map<String, Object> discoveryResponse = postEnvelope(
                context,
                "/datasources/{datasourceId}/metadata/discover",
                datasourceId,
                metadataRequest);
        AgentToolExecutionOutcome metadataOutcome = metadataResponseMapper.toOutcome(
                datasourceId, discoveryResponse);
        if (!metadataOutcome.success()) {
            return AgentToolExecutionOutcome.failed(
                    "TARGET_TABLE_CREATED_METADATA_REFRESH_FAILED",
                    "目标表已经创建，但重新读取目标表元数据失败: " + metadataOutcome.message(),
                    applied);
        }
        Map<String, Object> refreshedMetadata = requiredMap(
                metadataOutcome.output().get("metadata"), "目标表元数据刷新结果为空");
        findTable(refreshedMetadata, targetSchemaName, targetTableName, "新建目标端");

        Map<String, Object> output = new LinkedHashMap<>(applied);
        output.putAll(metadataOutcome.output());
        putIfPresent(output, "sourceDatasourceId", preview.get("sourceDatasourceId"));
        putIfPresent(output, "sourceSchemaName", preview.get("sourceSchemaName"));
        putIfPresent(output, "sourceTableName", preview.get("sourceTableName"));
        putIfPresent(output, "targetSchemaName", targetSchemaName);
        output.put("targetTableName", targetTableName);
        putIfPresent(output, "sourceMetadataRef", preview.get("sourceMetadataRef"));
        return AgentToolExecutionOutcome.succeeded(
                "用户确认的目标空表已创建，并已重新读取可供任务草稿使用的目标元数据。", output);
    }

    private MetadataReference metadataReference(AgentToolExecutionContext context,
                                                Object reference,
                                                String defaultTool,
                                                String missingMessage) {
        Map<String, Object> metadata = referencedMapAtPath(
                context, reference, defaultTool, "metadata", missingMessage);
        Object datasourceValue = referenceResolver.resolve(
                        context, referenceWithPath(reference, "datasourceId"), defaultTool, "datasourceId")
                .orElse(null);
        return new MetadataReference(requiredLong(datasourceValue, missingMessage + "，且缺少 datasourceId"), metadata);
    }

    private Map<String, Object> referencedMap(AgentToolExecutionContext context,
                                              Object reference,
                                              String defaultTool,
                                              String message) {
        return referencedMapAtPath(context, reference, defaultTool, null, message);
    }

    private Map<String, Object> referencedMapAtPath(AgentToolExecutionContext context,
                                                    Object reference,
                                                    String defaultTool,
                                                    String path,
                                                    String message) {
        Object value = referenceResolver.resolve(
                        context, referenceWithPath(reference, path), defaultTool, path)
                .orElse(null);
        return requiredMap(value, message);
    }

    private Object referenceWithPath(Object reference, String path) {
        if (!(reference instanceof Map<?, ?> raw)) {
            return reference;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
        if (path == null) {
            copy.remove("jsonPath");
            copy.remove("path");
        } else {
            copy.put("jsonPath", path);
            copy.remove("path");
        }
        return copy;
    }

    private Map<String, Object> findTable(Map<String, Object> metadata,
                                          String schemaName,
                                          String tableName,
                                          String side) {
        for (Map<String, Object> table : mapList(metadata.get("tables"))) {
            String candidateName = nullableText(table.get("tableName"));
            String candidateSchema = nullableText(table.get("schemaName"));
            boolean tableMatches = candidateName != null && candidateName.equalsIgnoreCase(tableName);
            boolean schemaMatches = schemaName == null
                    || (candidateSchema != null && candidateSchema.equalsIgnoreCase(schemaName));
            if (tableMatches && schemaMatches) {
                return table;
            }
        }
        throw new PlatformBusinessException(
                PlatformErrorCode.NOT_FOUND,
                side + "元数据中不存在表 " + objectName(schemaName, tableName));
    }

    private void ensureTargetTableAbsent(Map<String, Object> metadata,
                                         String schemaName,
                                         String tableName) {
        for (Map<String, Object> table : mapList(metadata.get("tables"))) {
            String candidateName = nullableText(table.get("tableName"));
            String candidateSchema = nullableText(table.get("schemaName"));
            boolean tableMatches = candidateName != null && candidateName.equalsIgnoreCase(tableName);
            boolean schemaMatches = schemaName == null
                    || (candidateSchema != null && candidateSchema.equalsIgnoreCase(schemaName));
            if (tableMatches && schemaMatches) {
                throw new PlatformBusinessException(
                        PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "目标表已经存在，无需自动创建: " + objectName(schemaName, tableName));
            }
        }
    }

    private void ensureCompleteSourceTable(Map<String, Object> table,
                                           String schemaName,
                                           String tableName) {
        if (booleanValue(table.get("columnsTruncated"), false)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "源表字段元数据被截断，不能据此创建不完整目标表: " + objectName(schemaName, tableName));
        }
        if (mapList(table.get("columns")).isEmpty()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "源表没有可用字段元数据，不能创建目标表: " + objectName(schemaName, tableName));
        }
    }

    private List<Map<String, Object>> trustedColumns(Map<String, Object> sourceTable) {
        List<Map<String, Object>> sourceColumns = new ArrayList<>(mapList(sourceTable.get("columns")));
        sourceColumns.sort(Comparator.comparingInt(column -> integerValue(column.get("ordinalPosition"), 0)));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : sourceColumns) {
            String columnName = requiredText(source.get("columnName"), "源表存在没有字段名的元数据记录");
            String sourceType = requiredText(source.get("dataTypeName"), "源字段 " + columnName + " 缺少类型");
            String targetType = normalizeTargetType(sourceType, source.get("columnSize"));
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("columnName", columnName);
            column.put("dataType", targetType);
            if ("VARCHAR".equals(targetType)) {
                int length = integerValue(source.get("columnSize"), 0);
                if (length < 1 || length > 65_535) {
                    throw new PlatformBusinessException(
                            PlatformErrorCode.BAD_REQUEST,
                            "源字段 " + columnName + " 的字符长度无法安全映射到 VARCHAR: " + length);
                }
                column.put("length", length);
            }
            if ("DECIMAL".equals(targetType)) {
                column.put("precision", integerValue(source.get("columnSize"), 0));
                column.put("scale", integerValue(source.get("decimalDigits"), 0));
            }
            boolean primaryKey = booleanValue(source.get("primaryKey"), false);
            column.put("nullable", primaryKey ? false : booleanValue(source.get("nullable"), true));
            column.put("primaryKey", primaryKey);
            result.add(column);
        }
        return List.copyOf(result);
    }

    private String normalizeTargetType(String sourceType, Object columnSize) {
        String normalized = sourceType.trim().toUpperCase(Locale.ROOT)
                .replace(" UNSIGNED", "")
                .replaceAll("\\(.*\\)", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.contains("CHAR") || normalized.contains("ENUM") || normalized.contains("SET")) {
            return "VARCHAR";
        }
        return switch (normalized) {
            case "TINYINT", "SMALLINT", "INT2" -> "SMALLINT";
            case "INT", "INTEGER", "INT4", "MEDIUMINT", "SERIAL", "YEAR" -> "INTEGER";
            case "BIGINT", "INT8", "BIGSERIAL" -> "BIGINT";
            case "DECIMAL", "NUMERIC", "NUMBER", "MONEY" -> "DECIMAL";
            case "FLOAT", "REAL", "DOUBLE", "DOUBLE PRECISION" -> "DOUBLE";
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "BIT" -> integerValue(columnSize, 1) == 1 ? "BOOLEAN" : "BINARY";
            case "DATE" -> "DATE";
            case "TIME", "TIME WITHOUT TIME ZONE", "TIME WITH TIME ZONE" -> "TIME";
            case "DATETIME", "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMP WITHOUT TIME ZONE",
                    "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMP";
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB" -> "TEXT";
            case "BINARY", "VARBINARY", "BLOB", "BYTEA", "LONGBLOB", "RAW" -> "BINARY";
            case "JSON", "JSONB" -> "JSON";
            case "UUID", "UNIQUEIDENTIFIER" -> "UUID";
            default -> throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "源字段类型 " + sourceType + " 没有安全的自动建表映射，请改选已有目标表或进入高级编辑");
        };
    }

    private Map<String, Object> postData(AgentToolExecutionContext context,
                                         String uri,
                                         Long datasourceId,
                                         Object body,
                                         String action) {
        return requireSuccessData(postEnvelope(context, uri, datasourceId, body), action);
    }

    private Map<String, Object> postEnvelope(AgentToolExecutionContext context,
                                             String uri,
                                             Long datasourceId,
                                             Object body) {
        return restClientBuilder.baseUrl(httpSupport.baseUrl(DATASOURCE_SERVICE)).build()
                .post()
                .uri(uri, datasourceId)
                .headers(headers -> httpSupport.applyUserDelegationHeaders(headers, context))
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
    }

    private Map<String, Object> requireSuccessData(Map<String, Object> response, String action) {
        if (response == null) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, action + "返回空响应");
        }
        if (integerValue(response.get("code"), -1) != 0) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    action + "失败: " + requiredText(response.get("message"), "下游未返回具体原因"));
        }
        return requiredMap(response.get("data"), action + "响应缺少 data");
    }

    private Map<String, Object> requiredMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> mapped = new LinkedHashMap<>();
                raw.forEach((key, nested) -> mapped.put(String.valueOf(key), nested));
                result.add(mapped);
            }
        }
        return result;
    }

    private Long requiredLong(Object value, String message) {
        Long result = longValue(value);
        if (result == null || result <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return result;
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

    private int integerValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private String requiredText(Object value, String message) {
        String result = nullableText(value);
        if (result == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
        }
        return result;
    }

    private String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            target.put(key, value);
        }
    }

    private String objectName(String schemaName, String tableName) {
        return schemaName == null ? tableName : schemaName + "." + tableName;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record MetadataReference(Long datasourceId, Map<String, Object> metadata) {
    }
}
