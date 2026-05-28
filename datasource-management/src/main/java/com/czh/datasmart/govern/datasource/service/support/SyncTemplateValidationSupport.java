/**
 * @Author : Cui
 * @Date: 2026/05/05 23:25
 * @Description DataSmart Govern Backend - SyncTemplateValidationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.IndexMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.entity.TableMetadataSummary;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.SyncMode;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同步模板智能校验支持组件。
 *
 * <p>模板校验不是普通 CRUD 校验，而是“执行前风险评估”：它要基于当前数据源状态、
 * 源端和目标端实时元数据、同步模式、写入策略、主键/增量字段和字段映射配置，
 * 尽量提前判断这个模板进入任务层以后是否会失败、重复写入、覆盖数据或造成结构不兼容。</p>
 *
 * <p>把这部分从 `SyncTemplateServiceImpl` 拆出来后，主服务只保留模板入口编排，
 * 本组件负责模板可执行性判断。后续如果产品支持 PostgreSQL、Kafka、MongoDB、对象存储、
 * CDC、回放、补数或异步元数据快照，这里也可以继续扩展为多连接器校验策略注册表，
 * 而不是继续推高模板主服务的耦合度。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTemplateValidationSupport {

    /**
     * 数据源管理服务。
     *
     * <p>模板校验需要复用统一元数据发现入口，不能在模板模块里再写一套 JDBC 探查逻辑。
     * 这样才能让缓存、权限、限流、字段截断、样本开关等数据源能力保持同一条产品口径。</p>
     */
    private final DataSourceManagementService dataSourceManagementService;

    /**
     * 权限评估器。
     *
     * <p>模板校验虽然不修改模板字段，但它会读取数据源结构和判断可执行性，
     * 因此仍属于模板管理动作，不能被普通只读用户随意触发。</p>
     */
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    /**
     * 字段映射校验组件。
     *
     * <p>字段映射是一条独立复杂能力线，由专门组件处理 JSON 解析、类型兼容和必填字段校验。</p>
     */
    private final SyncTemplateFieldMappingSupport fieldMappingSupport;

    /**
     * 执行同步模板智能校验。
     *
     * @param template 待校验模板，已经由主服务确认存在。
     * @param source 源数据源配置，已经由主服务确认存在。
     * @param target 目标数据源配置，已经由主服务确认存在。
     * @param actorId 操作人 ID，用于权限上下文和元数据发现审计。
     * @param actorRole 操作人角色，用于角色能力判断和资源权限判断。
     * @param actorTenantId 操作人租户，用于后续租户隔离和审计语义。
     * @return 稳定的校验结果 Map，包含阻断错误、风险告警、字段映射摘要和对象摘要。
     */
    public Map<String, Object> validate(SyncTemplate template,
                                        DataSourceConfig source,
                                        DataSourceConfig target,
                                        Long actorId,
                                        String actorRole,
                                        Long actorTenantId) {
        assertTemplateManagePermission(template, actorId, actorRole, actorTenantId);
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());
        List<String> errors = new java.util.ArrayList<>();
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, Object> result = buildBaseResult(template, writeStrategy, actorRole);

        boolean sourceAvailable = isDatasourceAvailable(source);
        boolean targetAvailable = isDatasourceAvailable(target);
        result.put("templateEnabled", Boolean.TRUE.equals(template.getEnabled()));
        result.put("sourceDatasourceAvailable", sourceAvailable);
        result.put("targetDatasourceAvailable", targetAvailable);
        result.put("sourceDatasourceStatus", source.getStatus());
        result.put("targetDatasourceStatus", target.getStatus());
        collectAvailabilityErrors(template, sourceAvailable, targetAvailable, errors);

        TableMetadataSummary sourceTable = sourceAvailable
                ? findTemplateTable(source, template.getSourceSchemaName(), template.getSourceObjectName(), actorId, actorRole, actorTenantId)
                : null;
        TableMetadataSummary targetTable = targetAvailable
                ? findTemplateTable(target, template.getTargetSchemaName(), template.getTargetObjectName(), actorId, actorRole, actorTenantId)
                : null;
        collectObjectExistenceErrors(template, sourceTable, targetTable, errors);
        if (sourceTable != null) {
            result.put("sourceObjectSummary", buildObjectSummary(sourceTable));
        }
        if (targetTable != null) {
            result.put("targetObjectSummary", buildObjectSummary(targetTable));
        }

        Map<String, Object> mappingSummary = fieldMappingSupport.defaultMappingSummary();
        if (sourceTable != null && targetTable != null) {
            validateModeRequirements(template, sourceTable, errors, warnings);
            validatePrimaryKey(template, sourceTable, targetTable, errors, warnings);
            validateWriteStrategy(template, sourceTable, targetTable, errors, warnings);
            mappingSummary = fieldMappingSupport.validateFieldMappings(template, sourceTable, targetTable, errors, warnings);
            validateStructureRisks(template, targetTable, warnings);
        }

        boolean passed = errors.isEmpty();
        result.put("passed", passed);
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("mappingSummary", mappingSummary);
        result.put("validatedAt", LocalDateTime.now());
        result.put("summary", passed
                ? "模板通过智能校验，可以进入后续任务配置和调度流程"
                : "模板未通过智能校验，请优先修复 errors 中的阻断问题，再处理 warnings 中的结构风险");
        return result;
    }

    private void assertTemplateManagePermission(SyncTemplate template, Long actorId, String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(actorRole, SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canManageTemplates()) {
            throw new IllegalStateException("当前角色无模板管理或智能校验权限: " + role.name());
        }
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(template.getTenantId())
                        .resourceCreatedBy(template.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE);
    }

    private Map<String, Object> buildBaseResult(SyncTemplate template, SyncWriteStrategy writeStrategy, String actorRole) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", template.getId());
        result.put("templateName", template.getName());
        result.put("syncMode", template.getSyncMode());
        result.put("writeStrategy", writeStrategy.name());
        result.put("validatedBy", actorRole);
        result.put("validationDimensions", List.of(
                "OBJECT_EXISTENCE",
                "MODE_REQUIREMENTS",
                "WRITE_STRATEGY",
                "FIELD_MAPPING",
                "REQUIRED_FIELDS",
                "STRUCTURE_RISK"
        ));
        return result;
    }

    private void collectAvailabilityErrors(SyncTemplate template, boolean sourceAvailable, boolean targetAvailable,
                                           List<String> errors) {
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            errors.add("模板当前未启用，不能作为可执行模板进入任务层");
        }
        if (!sourceAvailable) {
            errors.add("源数据源当前不可用");
        }
        if (!targetAvailable) {
            errors.add("目标数据源当前不可用");
        }
    }

    private void collectObjectExistenceErrors(SyncTemplate template, TableMetadataSummary sourceTable,
                                              TableMetadataSummary targetTable, List<String> errors) {
        if (sourceTable == null) {
            errors.add("源端对象不存在或当前不可发现: " + template.getSourceObjectName());
        }
        if (targetTable == null) {
            errors.add("目标端对象不存在或当前不可发现: " + template.getTargetObjectName());
        }
    }

    private TableMetadataSummary findTemplateTable(DataSourceConfig datasource,
                                                   String schemaName,
                                                   String objectName,
                                                   Long actorId,
                                                   String actorRole,
                                                   Long actorTenantId) {
        MetadataDiscoveryRequest request = new MetadataDiscoveryRequest();
        request.setActorId(actorId);
        request.setActorRole(actorRole);
        request.setActorTenantId(actorTenantId);
        request.setSchemaPattern(schemaName);
        request.setTableNamePattern(objectName);
        request.setMaxTables(50);
        request.setMaxColumnsPerTable(500);
        request.setIncludeColumns(true);
        request.setIncludePrimaryKeys(true);
        request.setIncludeIndexes(true);
        request.setIncludeViews(true);
        request.setIncludeSampleRows(false);
        DataSourceMetadataDiscoveryResult discoveryResult = dataSourceManagementService.discoverMetadata(datasource.getId(), request);
        if (discoveryResult == null || discoveryResult.getTables() == null) {
            return null;
        }
        String normalizedSchema = fieldMappingSupport.normalize(schemaName);
        String normalizedObject = fieldMappingSupport.normalize(objectName);
        return discoveryResult.getTables().stream()
                .filter(table -> fieldMappingSupport.normalize(table.getTableName()).equals(normalizedObject))
                .filter(table -> fieldMappingSupport.isBlank(schemaName)
                        || fieldMappingSupport.normalize(table.getSchemaName()).equals(normalizedSchema))
                .findFirst()
                .orElse(null);
    }

    private void validateModeRequirements(SyncTemplate template, TableMetadataSummary sourceTable,
                                          List<String> errors, List<String> warnings) {
        SyncMode syncMode = SyncMode.fromValue(template.getSyncMode());
        if (syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID
                || syncMode == SyncMode.CDC || syncMode == SyncMode.REPLAY || syncMode == SyncMode.BACKFILL) {
            if (fieldMappingSupport.isBlank(template.getIncrementalField())) {
                errors.add("当前同步模式需要配置 incrementalField，才能保存检查点、重放或补数边界");
                return;
            }
            Set<String> sourceColumns = fieldMappingSupport.extractColumnNames(sourceTable);
            if (!sourceColumns.contains(fieldMappingSupport.normalize(template.getIncrementalField()))) {
                errors.add("incrementalField 在源端对象中不存在: " + template.getIncrementalField());
                return;
            }
            validateIncrementalFieldSuitability(template, syncMode, warnings);
        }
        if (syncMode == SyncMode.STREAMING || syncMode == SyncMode.CDC) {
            warnings.add("当前模式属于准实时/CDC 场景，后续需要补充连接器位点、事件顺序、幂等写入和断点恢复能力");
        }
    }

    private void validateIncrementalFieldSuitability(SyncTemplate template, SyncMode syncMode, List<String> warnings) {
        String field = template.getIncrementalField().toLowerCase(java.util.Locale.ROOT);
        if (syncMode == SyncMode.INCREMENTAL_TIME && !(field.contains("time") || field.contains("date") || field.endsWith("_at"))) {
            warnings.add("INCREMENTAL_TIME 模式建议使用时间类字段作为 incrementalField，当前字段可能不是稳定时间水位: "
                    + template.getIncrementalField());
        }
        if (syncMode == SyncMode.INCREMENTAL_ID && !(field.endsWith("id") || field.contains("_id"))) {
            warnings.add("INCREMENTAL_ID 模式建议使用单调递增 ID 字段，当前字段名无法明显体现递增语义: "
                    + template.getIncrementalField());
        }
    }

    private void validatePrimaryKey(SyncTemplate template, TableMetadataSummary sourceTable, TableMetadataSummary targetTable,
                                    List<String> errors, List<String> warnings) {
        if (fieldMappingSupport.isBlank(template.getPrimaryKeyField())) {
            warnings.add("模板未显式配置 primaryKeyField，后续 UPSERT、重试幂等和回放去重能力会受限");
            return;
        }
        String normalizedPrimaryKey = fieldMappingSupport.normalize(template.getPrimaryKeyField());
        if (!fieldMappingSupport.extractColumnNames(sourceTable).contains(normalizedPrimaryKey)) {
            errors.add("primaryKeyField 在源端对象中不存在: " + template.getPrimaryKeyField());
        }
        if (!fieldMappingSupport.extractColumnNames(targetTable).contains(normalizedPrimaryKey)
                && fieldMappingSupport.isBlank(fieldMappingSupport.mapSourceFieldToTargetField(
                template.getFieldMappingConfig(), template.getPrimaryKeyField()))) {
            warnings.add("primaryKeyField 未在目标端同名出现，也没有在 fieldMappingConfig 中找到明确目标映射，写入冲突判断可能失败");
        }
    }

    private void validateWriteStrategy(SyncTemplate template, TableMetadataSummary sourceTable, TableMetadataSummary targetTable,
                                       List<String> errors, List<String> warnings) {
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());
        if (writeStrategy.requiresTargetUniqueConstraint()) {
            String conflictField = resolveTargetConflictField(template, sourceTable, targetTable);
            if (fieldMappingSupport.isBlank(conflictField)) {
                errors.add(writeStrategy.name() + " 策略需要可推导的目标端冲突键，请配置 primaryKeyField 或字段映射");
            } else if (!hasSingleFieldUniqueConstraint(targetTable, conflictField)) {
                errors.add(writeStrategy.name() + " 策略要求目标端字段具备主键或单字段唯一索引: " + conflictField);
            }
        }
        if (writeStrategy.isDestructiveRewrite()) {
            warnings.add("OVERWRITE 属于覆盖式写入策略，生产环境建议补充审批、备份、影响行数预估和回滚方案");
        }
        if (writeStrategy == SyncWriteStrategy.APPEND && hasAnyUniqueConstraint(targetTable)) {
            warnings.add("目标端存在主键或唯一索引，APPEND 在重复写入时可能触发唯一约束冲突");
        }
    }

    private void validateStructureRisks(SyncTemplate template, TableMetadataSummary targetTable, List<String> warnings) {
        if (Boolean.TRUE.equals(targetTable.getColumnsTruncated())) {
            warnings.add("目标端字段列表被截断，当前校验可能未覆盖全部字段，建议提高 maxColumnsPerTable 或使用离线元数据快照");
        }
        if (SyncWriteStrategy.fromValue(template.getWriteStrategy()) == SyncWriteStrategy.APPEND
                && !fieldMappingSupport.isBlank(template.getPrimaryKeyField())
                && hasCompositeUniqueConstraintContainingField(targetTable, template.getPrimaryKeyField())) {
            warnings.add("目标端存在包含 primaryKeyField 的复合唯一约束，单字段 APPEND 校验不足，后续应补充复合键映射校验");
        }
    }

    private Map<String, Object> buildObjectSummary(TableMetadataSummary table) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("catalog", table.getCatalog());
        summary.put("schemaName", table.getSchemaName());
        summary.put("tableName", table.getTableName());
        summary.put("tableType", table.getTableType());
        summary.put("columnCount", table.getColumnCount());
        summary.put("totalColumnCount", table.getTotalColumnCount());
        summary.put("columnsTruncated", table.getColumnsTruncated());
        summary.put("primaryKeys", table.getPrimaryKeys());
        summary.put("indexCount", table.getIndexes() == null ? 0 : table.getIndexes().size());
        summary.put("uniqueIndexCount", countUniqueIndexes(table));
        return summary;
    }

    private String resolveTargetConflictField(SyncTemplate template, TableMetadataSummary sourceTable,
                                              TableMetadataSummary targetTable) {
        Map<String, ?> targetColumns = fieldMappingSupport.indexColumnsByName(targetTable);
        if (!fieldMappingSupport.isBlank(template.getPrimaryKeyField())) {
            if (targetColumns.containsKey(fieldMappingSupport.normalize(template.getPrimaryKeyField()))) {
                return template.getPrimaryKeyField();
            }
            return fieldMappingSupport.mapSourceFieldToTargetField(template.getFieldMappingConfig(), template.getPrimaryKeyField());
        }
        if (sourceTable.getPrimaryKeys() != null && sourceTable.getPrimaryKeys().size() == 1) {
            String sourcePrimaryKey = sourceTable.getPrimaryKeys().get(0);
            if (targetColumns.containsKey(fieldMappingSupport.normalize(sourcePrimaryKey))) {
                return sourcePrimaryKey;
            }
            return fieldMappingSupport.mapSourceFieldToTargetField(template.getFieldMappingConfig(), sourcePrimaryKey);
        }
        return null;
    }

    private boolean hasAnyUniqueConstraint(TableMetadataSummary table) {
        boolean hasPrimaryKeys = table.getPrimaryKeys() != null && !table.getPrimaryKeys().isEmpty();
        boolean hasUniqueIndex = table.getIndexes() != null && table.getIndexes().stream().anyMatch(IndexMetadataSummary::isUnique);
        return hasPrimaryKeys || hasUniqueIndex;
    }

    private boolean hasSingleFieldUniqueConstraint(TableMetadataSummary table, String fieldName) {
        String normalizedFieldName = fieldMappingSupport.normalize(fieldName);
        if (table.getPrimaryKeys() != null && table.getPrimaryKeys().size() == 1
                && fieldMappingSupport.normalize(table.getPrimaryKeys().get(0)).equals(normalizedFieldName)) {
            return true;
        }
        if (table.getIndexes() == null) {
            return false;
        }
        return table.getIndexes().stream()
                .filter(IndexMetadataSummary::isUnique)
                .filter(index -> index.getColumnNames() != null && index.getColumnNames().size() == 1)
                .map(index -> index.getColumnNames().get(0))
                .filter(columnName -> !fieldMappingSupport.isBlank(columnName))
                .anyMatch(columnName -> fieldMappingSupport.normalize(columnName).equals(normalizedFieldName));
    }

    private boolean hasCompositeUniqueConstraintContainingField(TableMetadataSummary table, String fieldName) {
        String normalizedFieldName = fieldMappingSupport.normalize(fieldName);
        if (table.getPrimaryKeys() != null && table.getPrimaryKeys().size() > 1
                && table.getPrimaryKeys().stream().map(fieldMappingSupport::normalize).anyMatch(normalizedFieldName::equals)) {
            return true;
        }
        if (table.getIndexes() == null) {
            return false;
        }
        return table.getIndexes().stream()
                .filter(IndexMetadataSummary::isUnique)
                .filter(index -> index.getColumnNames() != null && index.getColumnNames().size() > 1)
                .anyMatch(index -> index.getColumnNames().stream()
                        .filter(columnName -> !fieldMappingSupport.isBlank(columnName))
                        .map(fieldMappingSupport::normalize)
                        .anyMatch(normalizedFieldName::equals));
    }

    private long countUniqueIndexes(TableMetadataSummary table) {
        if (table.getIndexes() == null) {
            return 0;
        }
        return table.getIndexes().stream().filter(IndexMetadataSummary::isUnique).count();
    }

    private boolean isDatasourceAvailable(DataSourceConfig datasource) {
        return datasource != null
                && DataSourceStatus.ACTIVE.equals(datasource.getStatus())
                && !DataSourceStatus.DELETED.equals(datasource.getStatus());
    }
}
