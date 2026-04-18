package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.entity.ColumnMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.entity.TableMetadataSummary;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.SyncTemplateService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:02
 * @Description DataSmart Govern Backend - SyncTemplateServiceImpl.java
 * @Version:1.0.0
 *
 * 同步模板服务实现。
 * 这一层除了负责模板的创建和更新，还负责把“模板配置是否真的可执行”提前判断出来。
 *
 * 之所以要把智能校验做在模板层，而不是等任务运行时再失败，有三个原因：
 * 1. 模板往往会被很多任务复用，越早发现配置错误，返工成本越低；
 * 2. 很多问题其实在运行前就能判断，例如源表不存在、字段映射写错、增量字段缺失；
 * 3. 产品如果只会“保存成功”，但不会“指出配置风险”，就很难走向真实商用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTemplateServiceImpl extends ServiceImpl<SyncTemplateMapper, SyncTemplate>
        implements SyncTemplateService {

    private final DataSourceConfigMapper dataSourceConfigMapper;
    private final SyncAuditRecordMapper syncAuditRecordMapper;
    private final DataSourceManagementService dataSourceManagementService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public SyncTemplate createTemplate(CreateSyncTemplateRequest request) {
        ensureTemplateNameUnique(request.getTenantId(), request.getName(), null);
        validateDatasourcePair(request.getSourceDatasourceId(), request.getTargetDatasourceId());
        SyncMode syncMode = SyncMode.fromValue(request.getSyncMode());

        SyncTemplate template = new SyncTemplate();
        template.setTenantId(request.getTenantId());
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setSourceDatasourceId(request.getSourceDatasourceId());
        template.setSourceSchemaName(request.getSourceSchemaName());
        template.setSourceObjectName(request.getSourceObjectName());
        template.setTargetDatasourceId(request.getTargetDatasourceId());
        template.setTargetSchemaName(request.getTargetSchemaName());
        template.setTargetObjectName(request.getTargetObjectName());
        template.setSyncMode(syncMode.name());
        template.setPrimaryKeyField(request.getPrimaryKeyField());
        template.setIncrementalField(request.getIncrementalField());
        template.setFieldMappingConfig(request.getFieldMappingConfig());
        template.setFilterConfig(request.getFilterConfig());
        template.setPartitionConfig(request.getPartitionConfig());
        template.setRetryPolicy(request.getRetryPolicy());
        template.setTimeoutPolicy(request.getTimeoutPolicy());
        template.setEnabled(request.getEnabled() == null || request.getEnabled());
        template.setCreatedBy(request.getCreatedBy());
        template.setUpdatedBy(request.getCreatedBy());
        save(template);

        recordAudit(
                template.getTenantId(),
                null,
                null,
                SyncAuditAction.CREATE_TEMPLATE,
                request.getCreatedBy(),
                "PROJECT_OWNER",
                buildPayload("templateId", template.getId(), "templateName", template.getName(),
                        "sourceObjectName", template.getSourceObjectName(), "targetObjectName", template.getTargetObjectName())
        );
        return template;
    }

    @Override
    @Transactional
    public SyncTemplate updateTemplate(Long id, UpdateSyncTemplateRequest request) {
        SyncTemplate template = getRequiredTemplate(id);
        ensureTemplateNameUnique(template.getTenantId(), request.getName(), id);
        validateDatasourcePair(request.getSourceDatasourceId(), request.getTargetDatasourceId());
        SyncMode syncMode = SyncMode.fromValue(request.getSyncMode());

        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setSourceDatasourceId(request.getSourceDatasourceId());
        template.setSourceSchemaName(request.getSourceSchemaName());
        template.setSourceObjectName(request.getSourceObjectName());
        template.setTargetDatasourceId(request.getTargetDatasourceId());
        template.setTargetSchemaName(request.getTargetSchemaName());
        template.setTargetObjectName(request.getTargetObjectName());
        template.setSyncMode(syncMode.name());
        template.setPrimaryKeyField(request.getPrimaryKeyField());
        template.setIncrementalField(request.getIncrementalField());
        template.setFieldMappingConfig(request.getFieldMappingConfig());
        template.setFilterConfig(request.getFilterConfig());
        template.setPartitionConfig(request.getPartitionConfig());
        template.setRetryPolicy(request.getRetryPolicy());
        template.setTimeoutPolicy(request.getTimeoutPolicy());
        template.setEnabled(request.getEnabled());
        template.setUpdatedBy(request.getUpdatedBy());
        updateById(template);

        recordAudit(
                template.getTenantId(),
                null,
                null,
                SyncAuditAction.UPDATE_TEMPLATE,
                request.getUpdatedBy(),
                "PROJECT_OWNER",
                buildPayload("templateId", template.getId(), "templateName", template.getName(),
                        "sourceObjectName", template.getSourceObjectName(), "targetObjectName", template.getTargetObjectName())
        );
        return template;
    }

    /**
     * 智能校验模板。
     * 这一版校验会覆盖四类高价值问题：
     * 1. 源端和目标端对象是否真实存在；
     * 2. 主键和增量字段是否存在、是否和同步模式匹配；
     * 3. 字段映射是否能在源端和目标端结构上对得上；
     * 4. 是否存在明显的产品级风险，例如目标表没有主键/唯一索引、结构信息被截断等。
     */
    @Override
    @Transactional
    public Map<String, Object> validateTemplate(Long id, Long actorId, String actorRole) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canManageTemplates()) {
            throw new IllegalStateException("当前角色无模板管理或智能校验权限: " + role.name());
        }

        SyncTemplate template = getRequiredTemplate(id);
        DataSourceConfig source = getRequiredDatasource(template.getSourceDatasourceId());
        DataSourceConfig target = getRequiredDatasource(template.getTargetDatasourceId());
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("templateId", template.getId());
        result.put("templateName", template.getName());
        result.put("syncMode", template.getSyncMode());
        result.put("validatedBy", actorRole);

        boolean sourceAvailable = isDatasourceAvailable(source);
        boolean targetAvailable = isDatasourceAvailable(target);
        result.put("templateEnabled", Boolean.TRUE.equals(template.getEnabled()));
        result.put("sourceDatasourceAvailable", sourceAvailable);
        result.put("targetDatasourceAvailable", targetAvailable);
        result.put("sourceDatasourceStatus", source.getStatus());
        result.put("targetDatasourceStatus", target.getStatus());

        if (!Boolean.TRUE.equals(template.getEnabled())) {
            errors.add("模板当前未启用，不能作为可执行模板进入任务层");
        }
        if (!sourceAvailable) {
            errors.add("源数据源当前不可用");
        }
        if (!targetAvailable) {
            errors.add("目标数据源当前不可用");
        }

        TableMetadataSummary sourceTable = null;
        TableMetadataSummary targetTable = null;
        if (sourceAvailable) {
            sourceTable = findTemplateTable(source, template.getSourceSchemaName(), template.getSourceObjectName(), actorId, actorRole);
            if (sourceTable == null) {
                errors.add("源端对象不存在或当前不可发现: " + template.getSourceObjectName());
            }
        }
        if (targetAvailable) {
            targetTable = findTemplateTable(target, template.getTargetSchemaName(), template.getTargetObjectName(), actorId, actorRole);
            if (targetTable == null) {
                errors.add("目标端对象不存在或当前不可发现: " + template.getTargetObjectName());
            }
        }

        if (sourceTable != null) {
            result.put("sourceObjectSummary", buildObjectSummary(sourceTable));
        }
        if (targetTable != null) {
            result.put("targetObjectSummary", buildObjectSummary(targetTable));
        }

        Map<String, Object> mappingSummary = new LinkedHashMap<>();
        if (sourceTable != null && targetTable != null) {
            validateModeRequirements(template, sourceTable, errors, warnings);
            validatePrimaryKey(template, sourceTable, targetTable, errors, warnings);
            mappingSummary = validateFieldMappings(template, sourceTable, targetTable, errors, warnings);
            validateStructureRisks(template, sourceTable, targetTable, warnings);
        } else {
            mappingSummary.put("mappingCount", 0);
            mappingSummary.put("validMappingCount", 0);
        }

        boolean passed = errors.isEmpty();
        result.put("passed", passed);
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("mappingSummary", mappingSummary);
        result.put("validatedAt", java.time.LocalDateTime.now());
        result.put("summary", passed
                ? "模板通过智能校验，可以进入后续任务配置和调度流程"
                : "模板未通过智能校验，请根据 errors 修正配置");

        recordAudit(
                template.getTenantId(),
                null,
                null,
                SyncAuditAction.VALIDATE_TEMPLATE,
                actorId,
                actorRole,
                buildPayload("templateId", template.getId(), "passed", passed,
                        "errorCount", errors.size(), "warningCount", warnings.size())
        );
        return result;
    }

    @Override
    public Map<String, Object> previewTemplate(Long id) {
        SyncTemplate template = getRequiredTemplate(id);
        DataSourceConfig source = getRequiredDatasource(template.getSourceDatasourceId());
        DataSourceConfig target = getRequiredDatasource(template.getTargetDatasourceId());

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("templateId", template.getId());
        preview.put("templateName", template.getName());
        preview.put("description", template.getDescription());
        preview.put("sourceDatasourceName", source.getName());
        preview.put("sourceDatasourceType", source.getType());
        preview.put("sourceSchemaName", template.getSourceSchemaName());
        preview.put("sourceObjectName", template.getSourceObjectName());
        preview.put("targetDatasourceName", target.getName());
        preview.put("targetDatasourceType", target.getType());
        preview.put("targetSchemaName", template.getTargetSchemaName());
        preview.put("targetObjectName", template.getTargetObjectName());
        preview.put("syncMode", template.getSyncMode());
        preview.put("primaryKeyField", template.getPrimaryKeyField());
        preview.put("incrementalField", template.getIncrementalField());
        preview.put("fieldMappingConfig", template.getFieldMappingConfig());
        preview.put("filterConfig", template.getFilterConfig());
        preview.put("partitionConfig", template.getPartitionConfig());
        preview.put("retryPolicy", template.getRetryPolicy());
        preview.put("timeoutPolicy", template.getTimeoutPolicy());
        preview.put("enabled", template.getEnabled());
        return preview;
    }

    private TableMetadataSummary findTemplateTable(DataSourceConfig datasource, String schemaName, String objectName,
                                                   Long actorId, String actorRole) {
        MetadataDiscoveryRequest request = new MetadataDiscoveryRequest();
        request.setActorId(actorId);
        request.setActorRole(actorRole);
        request.setSchemaPattern(schemaName);
        request.setTableNamePattern(objectName);
        request.setMaxTables(5);
        request.setMaxColumnsPerTable(200);
        request.setIncludeColumns(true);
        request.setIncludeViews(true);
        request.setIncludePrimaryKeys(true);
        request.setIncludeIndexes(true);
        request.setIncludeSampleRows(false);
        DataSourceMetadataDiscoveryResult discoveryResult =
                dataSourceManagementService.discoverMetadata(datasource.getId(), request);
        return discoveryResult.getTables().stream()
                .filter(table -> table.getTableName() != null && table.getTableName().equalsIgnoreCase(objectName))
                .filter(table -> schemaName == null || schemaName.isBlank()
                        || (table.getSchemaName() != null && table.getSchemaName().equalsIgnoreCase(schemaName)))
                .findFirst()
                .orElse(null);
    }

    private void validateModeRequirements(SyncTemplate template, TableMetadataSummary sourceTable,
                                          List<String> errors, List<String> warnings) {
        SyncMode syncMode = SyncMode.fromValue(template.getSyncMode());
        Set<String> sourceFields = extractColumnNames(sourceTable);
        if ((syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID)
                && isBlank(template.getIncrementalField())) {
            errors.add("当前同步模式要求配置 incrementalField，但模板中未填写");
        }
        if (!isBlank(template.getIncrementalField()) && !sourceFields.contains(normalize(template.getIncrementalField()))) {
            errors.add("配置的增量字段在源端对象中不存在: " + template.getIncrementalField());
        }
        if (syncMode == SyncMode.FULL && !isBlank(template.getIncrementalField())) {
            warnings.add("当前模板是全量同步，但仍配置了 incrementalField，后续请确认是否真的需要保留该字段");
        }
        if (Boolean.TRUE.equals(sourceTable.getColumnsTruncated())) {
            warnings.add("源端对象字段结果被截断，当前校验可能并未覆盖全部字段");
        }
    }

    private void validatePrimaryKey(SyncTemplate template, TableMetadataSummary sourceTable, TableMetadataSummary targetTable,
                                    List<String> errors, List<String> warnings) {
        Set<String> sourceFields = extractColumnNames(sourceTable);
        Set<String> targetFields = extractColumnNames(targetTable);
        List<String> detectedPrimaryKeys = sourceTable.getPrimaryKeys() == null ? List.of() : sourceTable.getPrimaryKeys();

        if (isBlank(template.getPrimaryKeyField())) {
            if (detectedPrimaryKeys.size() == 1) {
                warnings.add("模板未显式配置 primaryKeyField，建议直接使用源端主键字段: " + detectedPrimaryKeys.get(0));
            } else if (detectedPrimaryKeys.isEmpty()) {
                warnings.add("源端对象未发现主键，后续可能影响去重、重试和幂等写入能力");
            } else {
                warnings.add("源端对象存在多个主键字段，建议在模板中显式指定主键策略");
            }
            return;
        }

        if (!sourceFields.contains(normalize(template.getPrimaryKeyField()))) {
            errors.add("配置的主键字段在源端对象中不存在: " + template.getPrimaryKeyField());
        }
        if (!targetFields.contains(normalize(template.getPrimaryKeyField()))) {
            warnings.add("配置的主键字段在目标端对象中不存在同名字段，如依赖字段映射请确认 targetField 是否具备唯一约束");
        }
    }

    private Map<String, Object> validateFieldMappings(SyncTemplate template, TableMetadataSummary sourceTable,
                                                      TableMetadataSummary targetTable, List<String> errors,
                                                      List<String> warnings) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (isBlank(template.getFieldMappingConfig())) {
            warnings.add("模板尚未配置字段映射，当前只能做结构级校验，无法做字段级兼容性校验");
            summary.put("mappingCount", 0);
            summary.put("validMappingCount", 0);
            return summary;
        }

        Map<String, ColumnMetadataSummary> sourceColumns = indexColumnsByName(sourceTable);
        Map<String, ColumnMetadataSummary> targetColumns = indexColumnsByName(targetTable);
        List<String> missingSourceFields = new ArrayList<>();
        List<String> missingTargetFields = new ArrayList<>();
        List<String> typeMismatchMappings = new ArrayList<>();
        int mappingCount = 0;
        int validMappingCount = 0;

        try {
            JsonNode rootNode = objectMapper.readTree(template.getFieldMappingConfig());
            JsonNode mappingsNode = rootNode.isArray() ? rootNode : rootNode.path("mappings");
            if (!mappingsNode.isArray()) {
                errors.add("fieldMappingConfig 不是合法映射数组，建议使用数组或 {\"mappings\": [...]} 结构");
                summary.put("mappingCount", 0);
                summary.put("validMappingCount", 0);
                return summary;
            }

            for (JsonNode mappingNode : mappingsNode) {
                mappingCount++;
                String sourceField = textOf(mappingNode, "sourceField");
                String targetField = textOf(mappingNode, "targetField");
                if (isBlank(sourceField) || isBlank(targetField)) {
                    errors.add("第 " + mappingCount + " 条字段映射缺少 sourceField 或 targetField");
                    continue;
                }

                ColumnMetadataSummary sourceColumn = sourceColumns.get(normalize(sourceField));
                ColumnMetadataSummary targetColumn = targetColumns.get(normalize(targetField));
                if (sourceColumn == null) {
                    missingSourceFields.add(sourceField);
                    continue;
                }
                if (targetColumn == null) {
                    missingTargetFields.add(targetField);
                    continue;
                }

                validMappingCount++;
                if (!isCompatibleType(sourceColumn.getDataTypeName(), targetColumn.getDataTypeName())) {
                    typeMismatchMappings.add(sourceField + " -> " + targetField + "（"
                            + sourceColumn.getDataTypeName() + " -> " + targetColumn.getDataTypeName() + "）");
                }
            }
        } catch (Exception exception) {
            errors.add("fieldMappingConfig 不是合法 JSON: " + exception.getMessage());
        }

        if (!missingSourceFields.isEmpty()) {
            errors.add("字段映射中的源端字段不存在: " + String.join(", ", missingSourceFields));
        }
        if (!missingTargetFields.isEmpty()) {
            errors.add("字段映射中的目标端字段不存在: " + String.join(", ", missingTargetFields));
        }
        if (!typeMismatchMappings.isEmpty()) {
            warnings.add("发现疑似类型不兼容的字段映射: " + String.join("; ", typeMismatchMappings));
        }

        summary.put("mappingCount", mappingCount);
        summary.put("validMappingCount", validMappingCount);
        summary.put("missingSourceFields", missingSourceFields);
        summary.put("missingTargetFields", missingTargetFields);
        summary.put("typeMismatchMappings", typeMismatchMappings);
        return summary;
    }

    private void validateStructureRisks(SyncTemplate template, TableMetadataSummary sourceTable,
                                        TableMetadataSummary targetTable, List<String> warnings) {
        if ((targetTable.getIndexes() == null || targetTable.getIndexes().isEmpty())
                && (targetTable.getPrimaryKeys() == null || targetTable.getPrimaryKeys().isEmpty())) {
            warnings.add("目标端对象未发现主键或索引，后续 upsert、去重和高并发写入性能可能存在明显风险");
        }
        if (isBlank(template.getFieldMappingConfig()) && sourceTable.getTotalColumnCount() != null
                && targetTable.getTotalColumnCount() != null
                && !sourceTable.getTotalColumnCount().equals(targetTable.getTotalColumnCount())) {
            warnings.add("源端与目标端字段数量不同，但模板未显式配置字段映射，后续执行可能出现字段错位或写入失败");
        }
    }

    private Map<String, Object> buildObjectSummary(TableMetadataSummary table) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaName", table.getSchemaName());
        summary.put("tableName", table.getTableName());
        summary.put("tableType", table.getTableType());
        summary.put("columnCount", table.getColumnCount());
        summary.put("totalColumnCount", table.getTotalColumnCount());
        summary.put("primaryKeys", table.getPrimaryKeys());
        summary.put("indexCount", table.getIndexes() == null ? 0 : table.getIndexes().size());
        return summary;
    }

    private Map<String, ColumnMetadataSummary> indexColumnsByName(TableMetadataSummary table) {
        return table.getColumns() == null ? Map.of() : table.getColumns().stream()
                .collect(Collectors.toMap(column -> normalize(column.getColumnName()), column -> column, (left, right) -> left));
    }

    private Set<String> extractColumnNames(TableMetadataSummary table) {
        return table.getColumns() == null ? Set.of() : table.getColumns().stream()
                .map(ColumnMetadataSummary::getColumnName)
                .filter(columnName -> columnName != null && !columnName.isBlank())
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    private boolean isCompatibleType(String sourceType, String targetType) {
        String sourceCategory = normalizeTypeCategory(sourceType);
        String targetCategory = normalizeTypeCategory(targetType);
        return sourceCategory.equals(targetCategory) || "unknown".equals(sourceCategory) || "unknown".equals(targetCategory);
    }

    private String normalizeTypeCategory(String dataTypeName) {
        if (dataTypeName == null || dataTypeName.isBlank()) {
            return "unknown";
        }
        String normalized = dataTypeName.toLowerCase(Locale.ROOT);
        if (normalized.contains("char") || normalized.contains("text") || normalized.contains("clob")) {
            return "string";
        }
        if (normalized.contains("int") || normalized.contains("number") || normalized.contains("decimal")
                || normalized.contains("numeric") || normalized.contains("double") || normalized.contains("float")
                || normalized.contains("real")) {
            return "numeric";
        }
        if (normalized.contains("date") || normalized.contains("time")) {
            return "datetime";
        }
        if (normalized.contains("bool") || normalized.contains("bit")) {
            return "boolean";
        }
        if (normalized.contains("binary") || normalized.contains("blob")) {
            return "binary";
        }
        return "unknown";
    }

    private String textOf(JsonNode mappingNode, String fieldName) {
        JsonNode fieldNode = mappingNode.get(fieldName);
        return fieldNode == null || fieldNode.isNull() ? null : fieldNode.asText();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private SyncTemplate getRequiredTemplate(Long id) {
        SyncTemplate template = getById(id);
        if (template == null) {
            throw new NoSuchElementException("同步模板不存在: " + id);
        }
        return template;
    }

    private void ensureTemplateNameUnique(Long tenantId, String name, Long currentId) {
        LambdaQueryWrapper<SyncTemplate> wrapper = new LambdaQueryWrapper<SyncTemplate>()
                .eq(SyncTemplate::getTenantId, tenantId)
                .eq(SyncTemplate::getName, name)
                .ne(currentId != null, SyncTemplate::getId, currentId);
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("同步模板名称已存在: " + name);
        }
    }

    private void validateDatasourcePair(Long sourceDatasourceId, Long targetDatasourceId) {
        if (sourceDatasourceId.equals(targetDatasourceId)) {
            throw new IllegalArgumentException("源数据源和目标数据源不能相同");
        }
        DataSourceConfig source = getRequiredDatasource(sourceDatasourceId);
        DataSourceConfig target = getRequiredDatasource(targetDatasourceId);
        if (!isDatasourceAvailable(source)) {
            throw new IllegalStateException("源数据源当前不可用: " + sourceDatasourceId);
        }
        if (!isDatasourceAvailable(target)) {
            throw new IllegalStateException("目标数据源当前不可用: " + targetDatasourceId);
        }
    }

    private DataSourceConfig getRequiredDatasource(Long id) {
        DataSourceConfig dataSourceConfig = dataSourceConfigMapper.selectById(id);
        if (dataSourceConfig == null) {
            throw new NoSuchElementException("数据源不存在: " + id);
        }
        return dataSourceConfig;
    }

    private boolean isDatasourceAvailable(DataSourceConfig datasource) {
        return datasource != null && !DataSourceStatus.DELETED.equals(datasource.getStatus())
                && DataSourceStatus.ACTIVE.equals(datasource.getStatus());
    }

    private void recordAudit(Long tenantId, Long syncTaskId, Long executionId, SyncAuditAction action,
                             Long actorId, String actorRole, String payload) {
        SyncAuditRecord record = new SyncAuditRecord();
        record.setTenantId(tenantId);
        record.setSyncTaskId(syncTaskId);
        record.setExecutionId(executionId);
        record.setActionType(action.name());
        record.setActorId(actorId);
        record.setActorRole(actorRole);
        record.setActionPayload(payload);
        syncAuditRecordMapper.insert(record);
    }

    private String buildPayload(Object... pairs) {
        StringBuilder builder = new StringBuilder("{");
        for (int index = 0; index < pairs.length; index += 2) {
            if (index > 0) {
                builder.append(", ");
            }
            Object key = pairs[index];
            Object value = index + 1 < pairs.length ? pairs[index + 1] : "";
            builder.append("\"").append(key).append("\":\"").append(escape(String.valueOf(value))).append("\"");
        }
        builder.append("}");
        return builder.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
