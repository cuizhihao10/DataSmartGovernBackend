/**
 * @Author : Cui
 * @Date: 2026/07/08 22:06
 * @Description DataSmart Govern Backend - SyncTemplateMetadataAwarePrecheckSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryClient;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 基于两端真实元数据的同步模板预检查组件。
 *
 * <p>原来的 {@link SyncTemplateExecutionPrecheckSupport} 主要回答“从能力矩阵和控制面合同看，这个模板是否具备执行条件”，
 * 它不会访问源端或目标端元数据。因此用户在创建向导第二步可以自定义填写目标 schema/table 后，旧预检只能判断
 * {@code objectMappingConfig/fieldMappingConfig} 语法是否大体存在，却无法回答这些更贴近真实执行的问题：</p>
 *
 * <p>1. 源端表是否真的存在；</p>
 * <p>2. 目标端 schema/table 是否真的存在；</p>
 * <p>3. 勾选同步的源字段是否存在于源表；</p>
 * <p>4. 勾选同步的目标字段是否存在于目标表；</p>
 * <p>5. 勾选同步的字段类型是否属于当前最小 runner 可以安全直接搬运的兼容族。</p>
 *
 * <p>本组件补上的就是这层“元数据感知预检”。它仍然遵守模块边界：data-sync 不直接持有 JDBC 连接、不读取业务数据、
 * 不拉样本行、不执行 SQL，只通过 datasource-management 的低敏 metadata discovery 合同读取表名、schema、主键和字段摘要。
 * 这样既能满足创建向导第四步自动预检查的用户体验，也不会把数据源凭据和连接池职责从 datasource-management 挪到 data-sync。</p>
 *
 * <p>特别注意：这里允许“源字段存在但目标字段不存在且未勾选同步”“目标字段存在但源字段为空且未勾选同步”。
 * 这是用户刚刚明确的产品语义：这些字段必须在字段映射页展示出来，但只要没有被勾选同步，就不代表配置错误。
 * 真正需要阻断的是“用户明确勾选同步的字段对无法在两端表结构中找到或类型明显不兼容”。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTemplateMetadataAwarePrecheckSupport {

    private static final int MAX_TABLES_PER_LOOKUP = 10;
    private static final int MAX_COLUMNS_PER_TABLE = 500;

    private final DatasourceMetadataDiscoveryClient metadataDiscoveryClient;
    private final ObjectMapper objectMapper;

    /**
     * 执行元数据感知预检查。
     *
     * @param template 已完成租户、项目可见性校验的同步模板。
     * @param actorContext 当前操作者上下文；用于 datasource-management 侧审计与权限判断。
     * @return 低敏 issue/action/note 集合，供总预检报告合并。
     */
    public MetadataAwarePrecheckResult evaluate(SyncTemplate template, SyncActorContext actorContext) {
        List<String> issueCodes = new ArrayList<>();
        List<String> recommendedActions = new ArrayList<>();
        List<String> safetyNotes = new ArrayList<>();
        if (template == null) {
            return new MetadataAwarePrecheckResult(issueCodes, recommendedActions, safetyNotes);
        }
        /*
         * 旧内部调用路径仍然会使用 precheck(template) 的无 actor 版本。
         * 对这些路径先不主动访问 datasource-management，避免批量导入、管理操作或单元测试因为缺少真实用户上下文而被网络预检拖住。
         * 外部创建向导第四步和正式创建任务入口会传入 actorContext，从而启用真实元数据校验。
         */
        if (actorContext == null) {
            safetyNotes.add("当前预检查未携带操作者上下文，已跳过真实元数据存在性检查；创建向导第四步会携带上下文执行完整校验。");
            return new MetadataAwarePrecheckResult(issueCodes, recommendedActions, safetyNotes);
        }

        JsonNode objectRoot = parseJson(template.getObjectMappingConfig(),
                "METADATA_OBJECT_MAPPING_JSON_INVALID",
                "对象映射 JSON 无法解析，请检查 objectMappingConfig 是否为合法 JSON。",
                issueCodes,
                recommendedActions);
        JsonNode fieldRoot = parseJson(template.getFieldMappingConfig(),
                "METADATA_FIELD_MAPPING_JSON_INVALID",
                "字段映射 JSON 无法解析，请检查 fieldMappingConfig 是否为合法 JSON。",
                issueCodes,
                recommendedActions);
        if (issueCodes.contains("METADATA_OBJECT_MAPPING_JSON_INVALID")
                || issueCodes.contains("METADATA_FIELD_MAPPING_JSON_INVALID")) {
            return new MetadataAwarePrecheckResult(issueCodes, recommendedActions, safetyNotes);
        }

        List<ObjectMapping> objectMappings = parseObjectMappings(template, objectRoot, fieldRoot);
        if (objectMappings.isEmpty() && !isCustomSqlMode(template)) {
            issueCodes.add("METADATA_OBJECT_MAPPING_EMPTY");
            recommendedActions.add("请至少配置一组源端对象与目标端对象映射；全量传输、定期全量和定期批量都需要明确对象边界。");
            return new MetadataAwarePrecheckResult(issueCodes, recommendedActions, safetyNotes);
        }

        Map<String, List<FieldMapping>> fieldMappingsByObject = parseFieldMappings(fieldRoot);
        for (ObjectMapping mapping : objectMappings) {
            validateObjectMapping(template, mapping, fieldMappingsByObject, actorContext,
                    issueCodes, recommendedActions, safetyNotes);
        }
        return new MetadataAwarePrecheckResult(issueCodes, recommendedActions, safetyNotes);
    }

    /**
     * 校验一组源端对象到目标端对象的映射。
     *
     * <p>这里的目标 schema/table 不要求与源端同名，也不要求来自下拉选择；用户可以手动输入任何目标对象名。
     * 但一旦进入第四步预检，后端必须基于目标端真实元数据给出明确判断：存在则继续检查字段，不存在则返回
     * {@code METADATA_TARGET_OBJECT_NOT_FOUND}，由前端弹窗/预检面板提示用户修改。</p>
     */
    private void validateObjectMapping(SyncTemplate template,
                                       ObjectMapping mapping,
                                       Map<String, List<FieldMapping>> fieldMappingsByObject,
                                       SyncActorContext actorContext,
                                       List<String> issueCodes,
                                       List<String> recommendedActions,
                                       List<String> safetyNotes) {
        boolean customSqlMode = isCustomSqlMode(template);
        DatasourceMetadataDiscoveryResponse.TableSummary sourceTable = null;
        if (!customSqlMode) {
            if (!hasText(mapping.sourceObject())) {
                issueCodes.add("METADATA_SOURCE_OBJECT_REQUIRED");
                recommendedActions.add("非 SQL 自定义传输必须声明源端表/对象；请回到对象映射步骤选择源端对象。");
            } else {
                sourceTable = discoverTable(template.getSourceDatasourceId(), template.getSourceConnectorType(),
                        mapping.sourceSchema(), mapping.sourceObject(), "SOURCE", actorContext,
                        issueCodes, recommendedActions);
            }
        }

        DatasourceMetadataDiscoveryResponse.TableSummary targetTable = null;
        if (!hasText(mapping.targetObject())) {
            issueCodes.add("METADATA_TARGET_OBJECT_REQUIRED");
            recommendedActions.add("必须声明目标端表/对象；目标 schema/table 可以自定义填写，但不能为空。");
        } else {
            targetTable = discoverTable(template.getTargetDatasourceId(), template.getTargetConnectorType(),
                    mapping.targetSchema(), mapping.targetObject(), "TARGET", actorContext,
                    issueCodes, recommendedActions);
        }

        if (targetTable != null && targetTable.getPrimaryKeys() != null && targetTable.getPrimaryKeys().isEmpty()) {
            safetyNotes.add("目标对象 " + lowSensitiveObject(mapping.targetSchema(), mapping.targetObject())
                    + " 未发现主键信息；INSERT 可继续预检，UPDATE/merge 场景后续需要冲突字段或目标约束支持。");
        }
        if (sourceTable == null || targetTable == null) {
            return;
        }

        List<FieldMapping> mappings = fieldMappingsByObject.get(mapping.key());
        if ((mappings == null || mappings.isEmpty()) && fieldMappingsByObject.size() == 1) {
            mappings = fieldMappingsByObject.values().iterator().next();
        }
        validateFieldMappings(mapping, sourceTable, targetTable,
                mappings == null ? List.of() : mappings,
                issueCodes, recommendedActions, safetyNotes);
    }

    /**
     * 校验字段映射是否与两端真实表结构一致。
     *
     * <p>未勾选同步的行仅用于展示“源端独有字段”或“目标端独有字段”，不会阻断预检。
     * 勾选同步的行才会被当成真实 reader -> writer 字段对校验。</p>
     */
    private void validateFieldMappings(ObjectMapping objectMapping,
                                       DatasourceMetadataDiscoveryResponse.TableSummary sourceTable,
                                       DatasourceMetadataDiscoveryResponse.TableSummary targetTable,
                                       List<FieldMapping> mappings,
                                       List<String> issueCodes,
                                       List<String> recommendedActions,
                                       List<String> safetyNotes) {
        Map<String, DatasourceMetadataDiscoveryResponse.ColumnSummary> sourceColumns = columnsByName(sourceTable);
        Map<String, DatasourceMetadataDiscoveryResponse.ColumnSummary> targetColumns = columnsByName(targetTable);
        List<FieldMapping> enabledMappings = mappings.stream()
                .filter(FieldMapping::syncEnabled)
                .toList();
        if (enabledMappings.isEmpty()) {
            issueCodes.add("METADATA_FIELD_MAPPING_SELECTED_EMPTY");
            recommendedActions.add("对象 " + lowSensitiveObject(objectMapping.sourceSchema(), objectMapping.sourceObject())
                    + " 尚未勾选任何可同步字段；请在字段映射步骤至少选择一组源字段 -> 目标字段。");
            return;
        }
        for (FieldMapping mapping : enabledMappings) {
            DatasourceMetadataDiscoveryResponse.ColumnSummary sourceColumn =
                    sourceColumns.get(normalizeKey(mapping.sourceField()));
            DatasourceMetadataDiscoveryResponse.ColumnSummary targetColumn =
                    targetColumns.get(normalizeKey(mapping.targetField()));
            if (!hasText(mapping.sourceField()) || sourceColumn == null) {
                issueCodes.add("METADATA_SOURCE_FIELD_NOT_FOUND");
                recommendedActions.add("已勾选同步的源字段不存在，请重新检查字段映射；字段名=" + safeField(mapping.sourceField()));
            }
            if (!hasText(mapping.targetField()) || targetColumn == null) {
                issueCodes.add("METADATA_TARGET_FIELD_NOT_FOUND");
                recommendedActions.add("已勾选同步的目标字段不存在，请重新检查目标表字段；字段名=" + safeField(mapping.targetField()));
            }
            if (sourceColumn != null && targetColumn != null
                    && !typeCompatible(sourceColumn.getDataTypeName(), targetColumn.getDataTypeName())) {
                issueCodes.add("METADATA_FIELD_MAPPING_TYPE_INCOMPATIBLE");
                recommendedActions.add("字段 " + safeField(mapping.sourceField()) + " -> " + safeField(mapping.targetField())
                        + " 的类型族不兼容；当前最小 runner 不会自动执行复杂转换，请取消勾选或补充转换规则。");
            }
        }
        if (enabledMappings.size() < sourceColumns.size()) {
            safetyNotes.add("存在未勾选同步的源字段；预检允许该场景，执行时这些源字段不会写入目标端。");
        }
        if (enabledMappings.size() < targetColumns.size()) {
            safetyNotes.add("存在未由源端写入的目标字段；预检允许该场景，最终值由目标表 NULL/DEFAULT/触发器等结构约束决定。");
        }
    }

    private DatasourceMetadataDiscoveryResponse.TableSummary discoverTable(Long datasourceId,
                                                                           String connectorType,
                                                                           String schemaName,
                                                                           String tableName,
                                                                           String side,
                                                                           SyncActorContext actorContext,
                                                                           List<String> issueCodes,
                                                                           List<String> recommendedActions) {
        if (datasourceId == null) {
            issueCodes.add("METADATA_" + side + "_DATASOURCE_MISSING");
            recommendedActions.add(side + " 数据源 ID 为空，无法执行元数据存在性预检。");
            return null;
        }
        try {
            DatasourceMetadataDiscoveryRequest request = new DatasourceMetadataDiscoveryRequest();
            applyActorContext(request, actorContext);
            request.setSchemaPattern(isMysqlFamily(connectorType) ? null : trimToNull(schemaName));
            request.setTableNamePattern(trimToNull(tableName));
            request.setMaxTables(MAX_TABLES_PER_LOOKUP);
            request.setMaxColumnsPerTable(MAX_COLUMNS_PER_TABLE);
            request.setIncludeColumns(Boolean.TRUE);
            request.setIncludePrimaryKeys(Boolean.TRUE);
            request.setIncludeViews(Boolean.TRUE);
            request.setIncludeIndexes(Boolean.FALSE);
            request.setIncludeSampleRows(Boolean.FALSE);
            request.setSampleRowLimit(null);
            DatasourceMetadataDiscoveryResponse response =
                    metadataDiscoveryClient.discover(datasourceId, request, actorContext);
            return findExactTable(response, schemaName, tableName, connectorType, side, issueCodes, recommendedActions);
        } catch (RuntimeException exception) {
            issueCodes.add("METADATA_DISCOVERY_FAILED");
            recommendedActions.add(side + " 元数据发现失败，请检查数据源连接状态、权限和表名；低敏原因="
                    + lowSensitiveException(exception));
            return null;
        }
    }

    private DatasourceMetadataDiscoveryResponse.TableSummary findExactTable(DatasourceMetadataDiscoveryResponse response,
                                                                            String schemaName,
                                                                            String tableName,
                                                                            String connectorType,
                                                                            String side,
                                                                            List<String> issueCodes,
                                                                            List<String> recommendedActions) {
        List<DatasourceMetadataDiscoveryResponse.TableSummary> tables =
                response == null || response.getTables() == null ? List.of() : response.getTables();
        for (DatasourceMetadataDiscoveryResponse.TableSummary table : tables) {
            if (table == null || !equalsIgnoreCase(table.getTableName(), tableName)) {
                continue;
            }
            String requestedSchema = trimToNull(schemaName);
            if (requestedSchema == null || isMysqlFamily(connectorType)
                    || equalsIgnoreCase(table.getSchemaName(), requestedSchema)) {
                return table;
            }
        }
        issueCodes.add("METADATA_" + side + "_OBJECT_NOT_FOUND");
        recommendedActions.add(side + " 对象不存在或当前账号无权发现："
                + lowSensitiveObject(schemaName, tableName)
                + "；如果是 PostgreSQL，请确认 schema 与 table 均填写正确。");
        return null;
    }

    private List<ObjectMapping> parseObjectMappings(SyncTemplate template, JsonNode objectRoot, JsonNode fieldRoot) {
        List<ObjectMapping> mappings = new ArrayList<>();
        appendObjectMappings(mappings, firstArray(objectRoot, "mappings", "objectMappings"));
        if (mappings.isEmpty()) {
            appendObjectMappings(mappings, firstArray(fieldRoot, "objectMappings"));
        }
        if (mappings.isEmpty() && hasText(template.getSourceObjectName()) && hasText(template.getTargetObjectName())) {
            mappings.add(new ObjectMapping(
                    trimToNull(template.getSourceSchemaName()),
                    trimToNull(template.getSourceObjectName()),
                    trimToNull(template.getTargetSchemaName()),
                    trimToNull(template.getTargetObjectName())));
        }
        return mappings.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private void appendObjectMappings(List<ObjectMapping> mappings, JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (JsonNode node : arrayNode) {
            mappings.add(new ObjectMapping(
                    firstText(node, "sourceSchema", "sourceSchemaName"),
                    firstText(node, "sourceObject", "sourceTable", "sourceTableName"),
                    firstText(node, "targetSchema", "targetSchemaName"),
                    firstText(node, "targetObject", "targetTable", "targetTableName")));
        }
    }

    private Map<String, List<FieldMapping>> parseFieldMappings(JsonNode fieldRoot) {
        Map<String, List<FieldMapping>> result = new LinkedHashMap<>();
        if (fieldRoot == null || fieldRoot.isMissingNode() || fieldRoot.isNull()) {
            return result;
        }
        JsonNode objectMappings = firstArray(fieldRoot, "objectMappings");
        if (objectMappings != null && objectMappings.isArray()) {
            for (JsonNode objectNode : objectMappings) {
                ObjectMapping objectMapping = new ObjectMapping(
                        firstText(objectNode, "sourceSchema", "sourceSchemaName"),
                        firstText(objectNode, "sourceObject", "sourceTable", "sourceTableName"),
                        firstText(objectNode, "targetSchema", "targetSchemaName"),
                        firstText(objectNode, "targetObject", "targetTable", "targetTableName"));
                result.put(objectMapping.key(), parseFieldMappingRows(firstArray(objectNode, "mappings", "fieldMappings")));
            }
            return result;
        }
        List<FieldMapping> topLevelMappings = parseFieldMappingRows(firstArray(fieldRoot, "mappings", "fieldMappings"));
        if (!topLevelMappings.isEmpty()) {
            result.put("single", topLevelMappings);
        }
        return result;
    }

    private List<FieldMapping> parseFieldMappingRows(JsonNode mappingsNode) {
        if (mappingsNode == null || !mappingsNode.isArray()) {
            return List.of();
        }
        List<FieldMapping> mappings = new ArrayList<>();
        for (JsonNode node : mappingsNode) {
            boolean syncEnabled = !node.has("syncEnabled") || node.path("syncEnabled").asBoolean(true);
            mappings.add(new FieldMapping(
                    firstText(node, "sourceField", "sourceColumn"),
                    firstText(node, "targetField", "targetColumn"),
                    syncEnabled));
        }
        return mappings;
    }

    private JsonNode parseJson(String text,
                               String issueCode,
                               String action,
                               List<String> issueCodes,
                               List<String> recommendedActions) {
        String normalized = trimToNull(text);
        if (normalized == null) {
            return objectMapper.missingNode();
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception exception) {
            issueCodes.add(issueCode);
            recommendedActions.add(action);
            return objectMapper.missingNode();
        }
    }

    private JsonNode firstArray(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull() && hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private Map<String, DatasourceMetadataDiscoveryResponse.ColumnSummary> columnsByName(
            DatasourceMetadataDiscoveryResponse.TableSummary table) {
        Map<String, DatasourceMetadataDiscoveryResponse.ColumnSummary> result = new LinkedHashMap<>();
        if (table == null || table.getColumns() == null) {
            return result;
        }
        for (DatasourceMetadataDiscoveryResponse.ColumnSummary column : table.getColumns()) {
            String key = normalizeKey(column == null ? null : column.getColumnName());
            if (key != null) {
                result.putIfAbsent(key, column);
            }
        }
        return result;
    }

    private void applyActorContext(DatasourceMetadataDiscoveryRequest request, SyncActorContext actorContext) {
        request.setActorId(actorContext.actorId() == null ? 0L : actorContext.actorId());
        request.setActorRole(trimToNull(actorContext.actorRole()) == null ? "SERVICE_ACCOUNT" : actorContext.actorRole());
        request.setActorTenantId(actorContext.tenantId() == null ? 0L : actorContext.tenantId());
    }

    private boolean isCustomSqlMode(SyncTemplate template) {
        return SyncMode.CUSTOM_SQL_QUERY.name().equalsIgnoreCase(trimToNull(template.getSyncMode()));
    }

    private boolean isMysqlFamily(String connectorType) {
        String normalized = connectorType == null ? "" : connectorType.toUpperCase(Locale.ROOT);
        return normalized.contains("MYSQL") || normalized.contains("MARIADB");
    }

    private boolean typeCompatible(String sourceType, String targetType) {
        String sourceFamily = typeFamily(sourceType);
        String targetFamily = typeFamily(targetType);
        return !"UNKNOWN".equals(sourceFamily) && sourceFamily.equals(targetFamily);
    }

    private String typeFamily(String typeName) {
        String normalized = trimToNull(typeName);
        if (normalized == null) {
            return "UNKNOWN";
        }
        String type = normalized.toUpperCase(Locale.ROOT);
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB") || type.contains("STRING")
                || type.contains("ENUM")) {
            return "TEXT";
        }
        if (type.contains("INT") || type.contains("NUMBER") || type.contains("NUMERIC") || type.contains("DECIMAL")
                || type.contains("FLOAT") || type.contains("DOUBLE") || type.contains("REAL") || type.contains("SERIAL")) {
            return "NUMERIC";
        }
        if (type.contains("DATE") || type.contains("TIME")) {
            return "TEMPORAL";
        }
        if (type.contains("BOOL") || "BIT".equals(type)) {
            return "BOOLEAN";
        }
        if (type.contains("BINARY") || type.contains("BLOB") || type.contains("BYTEA")) {
            return "BINARY";
        }
        if (type.contains("JSON")) {
            return "JSON";
        }
        if (type.contains("UUID")) {
            return "UUID";
        }
        return "UNKNOWN";
    }

    private String normalizeKey(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeField(String fieldName) {
        String normalized = trimToNull(fieldName);
        if (normalized == null) {
            return "<EMPTY>";
        }
        return normalized.length() > 64 ? normalized.substring(0, 64) + "..." : normalized;
    }

    private String lowSensitiveObject(String schemaName, String tableName) {
        String schema = trimToNull(schemaName);
        String table = trimToNull(tableName);
        return schema == null ? String.valueOf(table) : schema + "." + table;
    }

    private String lowSensitiveException(RuntimeException exception) {
        if (exception instanceof PlatformBusinessException businessException && hasText(businessException.getMessage())) {
            return businessException.getMessage();
        }
        return exception.getClass().getSimpleName();
    }

    /**
     * 元数据预检的低敏结果。
     *
     * @param issueCodes 问题码，供前端和日志按机器可读方式识别。
     * @param recommendedActions 用户可理解的修复动作。
     * @param safetyNotes 不阻断执行但需要解释的治理提示。
     */
    public record MetadataAwarePrecheckResult(List<String> issueCodes,
                                              List<String> recommendedActions,
                                              List<String> safetyNotes) {
    }

    private record ObjectMapping(String sourceSchema,
                                 String sourceObject,
                                 String targetSchema,
                                 String targetObject) {

        private String key() {
            return String.join("|",
                    normalize(sourceSchema),
                    normalize(sourceObject),
                    normalize(targetSchema),
                    normalize(targetObject));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    private record FieldMapping(String sourceField,
                                String targetField,
                                boolean syncEnabled) {
    }
}
