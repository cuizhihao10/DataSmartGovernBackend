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
import com.czh.datasmart.govern.datasync.integration.datasource.tableprobe.DatasourceTableRowCountProbeClient;
import com.czh.datasmart.govern.datasync.integration.datasource.tableprobe.DatasourceTableRowCountProbeRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.tableprobe.DatasourceTableRowCountProbeResponse;
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
    private final DatasourceTableRowCountProbeClient rowCountProbeClient;
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
            boolean sourceSchemaMissing = requiresSchemaName(template.getSourceConnectorType()) && !hasText(mapping.sourceSchema());
            if (sourceSchemaMissing) {
                issueCodes.add("METADATA_SOURCE_SCHEMA_REQUIRED");
                recommendedActions.add("源端连接器 " + safeConnector(template.getSourceConnectorType())
                        + " 使用 schema 命名空间；请回到对象映射步骤填写源端 schema。MySQL/MariaDB 不需要填写 schema。");
            }
            if (!hasText(mapping.sourceObject())) {
                issueCodes.add("METADATA_SOURCE_OBJECT_REQUIRED");
                recommendedActions.add("非 SQL 自定义传输必须声明源端表/对象；请回到对象映射步骤选择源端对象。");
            } else if (!sourceSchemaMissing) {
                sourceTable = discoverTable(template.getSourceDatasourceId(), template.getSourceConnectorType(),
                        mapping.sourceSchema(), mapping.sourceObject(), "SOURCE", actorContext,
                        issueCodes, recommendedActions);
            }
        }

        DatasourceMetadataDiscoveryResponse.TableSummary targetTable = null;
        boolean targetSchemaMissing = requiresSchemaName(template.getTargetConnectorType()) && !hasText(mapping.targetSchema());
        if (targetSchemaMissing) {
            issueCodes.add("METADATA_TARGET_SCHEMA_REQUIRED");
            recommendedActions.add("目标端连接器 " + safeConnector(template.getTargetConnectorType())
                    + " 使用 schema 命名空间；请回到对象映射步骤填写目标端 schema。MySQL/MariaDB 不需要填写 schema。");
        }
        if (!hasText(mapping.targetObject())) {
            issueCodes.add("METADATA_TARGET_OBJECT_REQUIRED");
            recommendedActions.add("必须声明目标端表/对象；目标 schema/table 可以自定义填写，但不能为空。");
        } else if (!targetSchemaMissing) {
            targetTable = discoverTable(template.getTargetDatasourceId(), template.getTargetConnectorType(),
                    mapping.targetSchema(), mapping.targetObject(), "TARGET", actorContext,
                    issueCodes, recommendedActions);
        }

        if (targetTable != null && isConflictWriteStrategy(template) && !hasText(template.getPrimaryKeyField())
                && !hasPrimaryKey(targetTable)) {
            /*
             * 用户创建向导只暴露 INSERT / UPDATE 两种产品语义，不再让普通用户手填 primaryKeyField/conflictField。
             * 因此 UPDATE/merge 的幂等边界必须由系统自动读取目标表元数据来判断：目标表如果没有主键，
             * runner 就无法稳定知道“哪一行是同一条业务记录”，继续执行会演变成重复写入、全表扫描或不可预期覆盖。
             */
            issueCodes.add("METADATA_TARGET_PRIMARY_KEY_REQUIRED_FOR_UPDATE");
            recommendedActions.add("写入策略为 update/merge 时，目标对象 "
                    + lowSensitiveObject(mapping.targetSchema(), mapping.targetObject())
                    + " 必须具备主键或显式冲突字段；请先为目标表建立主键/唯一约束，或将写入策略改为 INSERT。");
        } else if (targetTable != null && !hasPrimaryKey(targetTable)) {
            safetyNotes.add("目标对象 " + lowSensitiveObject(mapping.targetSchema(), mapping.targetObject())
                    + " 未发现主键信息；INSERT 可继续预检，但未来如果改为 UPDATE/merge 将需要主键或唯一约束。");
        }
        if (targetTable != null && isInsertWriteStrategy(template) && isFullLikeMode(template)) {
            /*
             * INSERT 的语义必须说清楚：它不会覆盖已有行；如果目标表已有相同主键或唯一键，数据库会直接报错。
             * 因此 FULL/SCHEDULED_FULL 这类“把源端当前范围整体写入目标”的场景，必须在执行前确认目标表为空，
             * 否则用户以为“全量传输会自动覆盖/清空”就非常危险。
             *
             * 这里仍然遵守微服务边界：data-sync 不直接连接目标库，而是调用 datasource-management 的 internal
             * row-count probe。datasource-management 才持有 JDBC 凭据和只读连接能力，返回给 data-sync 的只是低敏
             * rowCount/empty 事实。
             */
            probeTargetRowCountForInsert(template, mapping, targetTable, actorContext,
                    issueCodes, recommendedActions, safetyNotes);
        }
        if (targetTable == null) {
            return;
        }

        List<FieldMapping> mappings = fieldMappingsByObject.get(mapping.key());
        if ((mappings == null || mappings.isEmpty()) && fieldMappingsByObject.size() == 1) {
            mappings = fieldMappingsByObject.values().iterator().next();
        }
        if (customSqlMode) {
            /*
             * CUSTOM_SQL_QUERY 的 Reader 输出不是一张固定源表，而是一段只读 SQL 的结果集。
             * 因此这里不能拿 sourceTable 去校验“源字段是否存在于源表”，否则 SQL alias、表达式列、
             * 聚合列都会被误判为源字段不存在。正确边界是：
             * 1. SQL 自身的只读性、危险关键字、源表引用由 SQL 合同和后续 SQL 检查负责；
             * 2. 目标表是否存在、目标字段是否存在、UPDATE 是否具备主键由 metadata-aware precheck 负责；
             * 3. sourceField 在该模式下表示 SQL 结果集列名或别名，只要求用户明确填写，供执行器按 ResultSet 列名读取。
             */
            validateCustomSqlFieldMappings(mapping, targetTable,
                    mappings == null ? List.of() : mappings,
                    issueCodes, recommendedActions, safetyNotes);
            return;
        }
        if (sourceTable == null) {
            return;
        }
        validateFieldMappings(mapping, sourceTable, targetTable,
                mappings == null ? List.of() : mappings,
                issueCodes, recommendedActions, safetyNotes);
    }

    /**
     * 校验自定义 SQL 结果集到目标表字段的映射。
     *
     * <p>普通表同步可以同时读取源表和目标表元数据，因此 {@link #validateFieldMappings(ObjectMapping,
     * DatasourceMetadataDiscoveryResponse.TableSummary, DatasourceMetadataDiscoveryResponse.TableSummary, List, List, List, List)}
     * 能同时验证源字段、目标字段和类型兼容性。CUSTOM_SQL_QUERY 不同：源端是一段 SELECT 结果，
     * 结果列可能来自别名、函数、表达式或多表 join，控制面在不执行 SQL 的前提下无法稳定获得 ResultSet metadata。
     * 所以这里采取更安全也更产品化的策略：只把 sourceField 当作“SQL 输出列/别名声明”检查是否为空，
     * 把 targetField 按目标表真实元数据严格校验是否存在。这样既不误伤合法 SQL alias，又能保证写入端不会因为字段不存在而炸在执行阶段。</p>
     */
    private void validateCustomSqlFieldMappings(ObjectMapping objectMapping,
                                                DatasourceMetadataDiscoveryResponse.TableSummary targetTable,
                                                List<FieldMapping> mappings,
                                                List<String> issueCodes,
                                                List<String> recommendedActions,
                                                List<String> safetyNotes) {
        Map<String, DatasourceMetadataDiscoveryResponse.ColumnSummary> targetColumns = columnsByName(targetTable);
        List<FieldMapping> enabledMappings = mappings.stream()
                .filter(FieldMapping::syncEnabled)
                .toList();
        if (enabledMappings.isEmpty()) {
            issueCodes.add("METADATA_FIELD_MAPPING_SELECTED_EMPTY");
            recommendedActions.add("SQL 自定义传输必须至少声明一个 SQL 输出列/别名 -> 目标字段的映射；"
                    + "请在字段映射步骤中把 SELECT 结果列映射到目标表字段。");
            return;
        }
        for (FieldMapping mapping : enabledMappings) {
            if (!hasText(mapping.sourceField())) {
                issueCodes.add("METADATA_SOURCE_FIELD_NOT_FOUND");
                recommendedActions.add("SQL 自定义传输中存在未填写的 SQL 输出列/别名；"
                        + "sourceField 应填写 SELECT 输出字段名或别名，例如 member_name。");
            }
            DatasourceMetadataDiscoveryResponse.ColumnSummary targetColumn =
                    targetColumns.get(normalizeKey(mapping.targetField()));
            if (!hasText(mapping.targetField()) || targetColumn == null) {
                issueCodes.add("METADATA_TARGET_FIELD_NOT_FOUND");
                recommendedActions.add("SQL 自定义传输的目标字段不存在于目标对象 "
                        + lowSensitiveObject(objectMapping.targetSchema(), objectMapping.targetObject())
                        + "；字段名=" + safeField(mapping.targetField()));
            }
        }
        if (enabledMappings.size() < targetColumns.size()) {
            safetyNotes.add("SQL 自定义传输未覆盖目标表的全部字段；预检查允许该场景，"
                    + "未写入字段的最终值由目标表 NULL/DEFAULT/触发器等结构约束决定。");
        }
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

    /**
     * 对全量 INSERT 目标表做行数探测。
     *
     * <p>这是本轮预检查收敛的关键点：过去系统只能说“当前元数据合同没有目标表行数，因此需要确认”；
     * 现在改为在服务端主动获取 row-count 事实。对于商用数据同步产品来说，这比单纯提示风险更重要，
     * 因为用户往往会把“全量传输”理解成“目标端可以被安全重建”，但 INSERT 实际上既不清空目标表，也不覆盖已有行。</p>
     *
     * <p>判断策略采用 fail-closed：</p>
     * <p>1. row-count 成功且为 0：允许继续预检查；</p>
     * <p>2. row-count 成功且大于 0：硬阻断，要求用户清空目标表、改为 UPDATE/merge 或重新选择目标表；</p>
     * <p>3. row-count 探测失败：硬阻断，因为系统无法证明目标表为空，不应冒险放行。</p>
     */
    private void probeTargetRowCountForInsert(SyncTemplate template,
                                              ObjectMapping mapping,
                                              DatasourceMetadataDiscoveryResponse.TableSummary targetTable,
                                              SyncActorContext actorContext,
                                              List<String> issueCodes,
                                              List<String> recommendedActions,
                                              List<String> safetyNotes) {
        if (rowCountProbeClient == null) {
            issueCodes.add("METADATA_TARGET_ROW_COUNT_PROBE_UNAVAILABLE");
            recommendedActions.add("全量 INSERT 需要目标表空表检查，但当前 data-sync 未注入 row-count probe 客户端；请检查服务配置。");
            return;
        }
        DatasourceTableRowCountProbeRequest request = new DatasourceTableRowCountProbeRequest();
        request.setDatasourceId(template.getTargetDatasourceId());
        request.setConnectorType(template.getTargetConnectorType());
        request.setObjectLocator(targetObjectLocator(template.getTargetConnectorType(), mapping, targetTable));
        request.setPurpose("PRECHECK_INSERT_TARGET_EMPTY");
        try {
            DatasourceTableRowCountProbeResponse response = rowCountProbeClient.probeRowCount(request, actorContext);
            if (response == null || !response.probed() || response.getRowCount() == null) {
                issueCodes.add("METADATA_TARGET_ROW_COUNT_PROBE_FAILED");
                recommendedActions.add("全量 INSERT 需要确认目标对象为空，但目标表行数探测未成功；请检查目标库权限、表名/schema 或稍后重试。");
                return;
            }
            Long rowCount = response.getRowCount();
            if (rowCount > 0L) {
                issueCodes.add("METADATA_TARGET_NOT_EMPTY_FOR_INSERT_FULL");
                recommendedActions.add("目标对象 " + lowSensitiveObject(mapping.targetSchema(), mapping.targetObject())
                        + " 当前行数为 " + rowCount + "。全量 INSERT 不会覆盖旧行，可能发生主键冲突或重复写入；"
                        + "请先清空/新建目标表，或把写入策略改为 UPDATE/merge。");
            } else {
                safetyNotes.add("目标对象 " + lowSensitiveObject(mapping.targetSchema(), mapping.targetObject())
                        + " 已通过 row-count 探测确认为空，全量 INSERT 不会与既有目标数据冲突。");
            }
            if (response.getWarnings() != null) {
                response.getWarnings().stream()
                        .filter(Objects::nonNull)
                        .map(warning -> "目标表行数探测：" + warning)
                        .forEach(safetyNotes::add);
            }
        } catch (RuntimeException exception) {
            issueCodes.add("METADATA_TARGET_ROW_COUNT_PROBE_FAILED");
            recommendedActions.add("全量 INSERT 目标表行数探测失败，已按 fail-closed 阻断执行准入；低敏原因="
                    + lowSensitiveException(exception));
        }
    }

    private String targetObjectLocator(String connectorType,
                                       ObjectMapping mapping,
                                       DatasourceMetadataDiscoveryResponse.TableSummary targetTable) {
        String table = firstNonBlank(targetTable == null ? null : targetTable.getTableName(), mapping.targetObject());
        if (isMysqlFamily(connectorType)) {
            /*
             * MySQL 的“库名”是 catalog，不是 PostgreSQL 风格 schema。
             * 如果 metadata discovery 返回了 catalog，则使用 database.table；否则使用 table，让连接当前 catalog 决定。
             */
            String catalog = targetTable == null ? null : targetTable.getCatalog();
            return firstNonBlank(catalog) == null ? table : catalog + "." + table;
        }
        String schema = firstNonBlank(mapping.targetSchema(), targetTable == null ? null : targetTable.getSchemaName());
        return schema == null ? table : schema + "." + table;
    }

    private List<ObjectMapping> parseObjectMappings(SyncTemplate template, JsonNode objectRoot, JsonNode fieldRoot) {
        List<ObjectMapping> mappings = new ArrayList<>();
        appendObjectMappings(mappings, firstArray(objectRoot, "mappings", "objectMappings"));
        if (mappings.isEmpty()) {
            appendObjectMappings(mappings, firstArray(fieldRoot, "objectMappings"));
        }
        if (mappings.isEmpty() && isCustomSqlMode(template) && hasText(template.getTargetObjectName())) {
            /*
             * SQL 自定义传输只有目标写入对象，源端对象由 SQL 文本决定。
             * 如果仍然要求 sourceObjectName 和 targetObjectName 同时存在，metadata-aware precheck
             * 就会跳过 SQL 模式的目标表存在性检查，导致“目标表不存在”只能在执行阶段暴露。
             */
            mappings.add(new ObjectMapping(
                    null,
                    null,
                    trimToNull(template.getTargetSchemaName()),
                    trimToNull(template.getTargetObjectName())));
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
                    firstText(node, "sourceObject", "sourceObjectName", "sourceTable", "sourceTableName"),
                    firstText(node, "targetSchema", "targetSchemaName"),
                    firstText(node, "targetObject", "targetObjectName", "targetTable", "targetTableName")));
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
                        firstText(objectNode, "sourceObject", "sourceObjectName", "sourceTable", "sourceTableName"),
                        firstText(objectNode, "targetSchema", "targetSchemaName"),
                        firstText(objectNode, "targetObject", "targetObjectName", "targetTable", "targetTableName"));
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

    private boolean isInsertWriteStrategy(SyncTemplate template) {
        String writeStrategy = trimToNull(template.getWriteStrategy());
        return writeStrategy == null
                || "INSERT".equalsIgnoreCase(writeStrategy)
                || "APPEND".equalsIgnoreCase(writeStrategy);
    }

    private boolean isConflictWriteStrategy(SyncTemplate template) {
        String writeStrategy = trimToNull(template.getWriteStrategy());
        return "UPDATE".equalsIgnoreCase(writeStrategy)
                || "UPSERT".equalsIgnoreCase(writeStrategy)
                || "INSERT_IGNORE".equalsIgnoreCase(writeStrategy)
                || "REPLACE".equalsIgnoreCase(writeStrategy);
    }

    private boolean isFullLikeMode(SyncTemplate template) {
        String syncMode = trimToNull(template.getSyncMode());
        return SyncMode.FULL.name().equalsIgnoreCase(syncMode)
                || SyncMode.SCHEDULED_FULL.name().equalsIgnoreCase(syncMode);
    }

    private boolean hasPrimaryKey(DatasourceMetadataDiscoveryResponse.TableSummary table) {
        if (table == null) {
            return false;
        }
        if (table.getPrimaryKeys() != null && !table.getPrimaryKeys().isEmpty()) {
            return true;
        }
        return table.getColumns() != null && table.getColumns().stream()
                .anyMatch(column -> column != null && column.isPrimaryKey());
    }

    private boolean isMysqlFamily(String connectorType) {
        String normalized = connectorType == null ? "" : connectorType.toUpperCase(Locale.ROOT);
        return normalized.contains("MYSQL") || normalized.contains("MARIADB");
    }

    private boolean requiresSchemaName(String connectorType) {
        String normalized = connectorType == null ? "" : connectorType.toUpperCase(Locale.ROOT);
        /*
         * MySQL/MariaDB 的业务命名空间是 database/catalog，创建任务页面已经不再暴露 schema 必填。
         * PostgreSQL 和 SQL Server 则存在真实 schema 命名空间；如果不要求用户填写 schema，
         * 后端可能在多个 schema 中误命中同名表，也可能因为 search_path 不同导致预检查和执行阶段结果不一致。
         */
        return hasText(normalized) && !isMysqlFamily(normalized);
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
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

    private String safeConnector(String connectorType) {
        String normalized = trimToNull(connectorType);
        return normalized == null ? "<UNKNOWN>" : normalized.toUpperCase(Locale.ROOT);
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
