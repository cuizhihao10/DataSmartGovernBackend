package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.entity.ColumnMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.IndexMetadataSummary;
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
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
 * 这一层除了负责模板的创建和更新，还负责把“模板配置是否真的可执行”尽量提前判断出来。
 *
 * 为什么要在模板层做更强的执行前检查，而不是等任务真正运行再失败：
 * 1. 模板往往会被多个任务复用，越早发现配置问题，返工成本越低。
 * 2. 很多问题在运行前就能识别，例如源表不存在、增量字段类型不合适、目标端缺唯一约束。
 * 3. 商用品不能只做到“保存成功”，还应该尽量告诉用户“按当前配置上线后最容易在哪些地方踩坑”。
 *
 * 这一轮的重点能力是把模板校验从“结构存在性检查”升级到“执行风险检查”，主要覆盖：
 * - 写入策略是否与目标端结构匹配；
 * - 增量字段是否真的适合对应同步模式；
 * - 字段映射是否会触发长度或精度截断风险；
 * - 目标端必填字段是否会因为映射不完整而导致执行失败；
 * - 是否存在可直接复用的自动映射建议，帮助后续补齐配置。
 */
@Service
@RequiredArgsConstructor
public class SyncTemplateServiceImpl extends ServiceImpl<SyncTemplateMapper, SyncTemplate>
        implements SyncTemplateService {

    private final DataSourceConfigMapper dataSourceConfigMapper;
    private final SyncAuditRecordMapper syncAuditRecordMapper;
    private final DataSourceManagementService dataSourceManagementService;
    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public SyncTemplate createTemplate(CreateSyncTemplateRequest request) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getCreatedBy())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(request.getTenantId())
                        .build(),
                SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE);
        ensureTemplateNameUnique(request.getTenantId(), request.getName(), null);
        validateDatasourcePair(request.getSourceDatasourceId(), request.getTargetDatasourceId());
        SyncMode syncMode = SyncMode.fromValue(request.getSyncMode());
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(request.getWriteStrategy());

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
        template.setWriteStrategy(writeStrategy.name());
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
                request.getActorRole(),
                buildPayload(
                        "templateId", template.getId(),
                        "templateName", template.getName(),
                        "sourceObjectName", template.getSourceObjectName(),
                        "targetObjectName", template.getTargetObjectName(),
                        "writeStrategy", template.getWriteStrategy()
                )
        );
        return template;
    }

    @Override
    @Transactional
    public SyncTemplate updateTemplate(Long id, UpdateSyncTemplateRequest request) {
        SyncTemplate template = getRequiredTemplate(id);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getUpdatedBy())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(template.getTenantId())
                        .resourceCreatedBy(template.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE);
        ensureTemplateNameUnique(template.getTenantId(), request.getName(), id);
        validateDatasourcePair(request.getSourceDatasourceId(), request.getTargetDatasourceId());
        SyncMode syncMode = SyncMode.fromValue(request.getSyncMode());
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(request.getWriteStrategy());

        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setSourceDatasourceId(request.getSourceDatasourceId());
        template.setSourceSchemaName(request.getSourceSchemaName());
        template.setSourceObjectName(request.getSourceObjectName());
        template.setTargetDatasourceId(request.getTargetDatasourceId());
        template.setTargetSchemaName(request.getTargetSchemaName());
        template.setTargetObjectName(request.getTargetObjectName());
        template.setSyncMode(syncMode.name());
        template.setWriteStrategy(writeStrategy.name());
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
                request.getActorRole(),
                buildPayload(
                        "templateId", template.getId(),
                        "templateName", template.getName(),
                        "sourceObjectName", template.getSourceObjectName(),
                        "targetObjectName", template.getTargetObjectName(),
                        "writeStrategy", template.getWriteStrategy()
                )
        );
        return template;
    }

    /**
     * 智能校验模板。
     * 当前版本会覆盖六类高价值检查：
     * 1. 源端和目标端对象是否真实存在。
     * 2. 增量字段和主键字段是否存在，以及是否与当前同步模式匹配。
     * 3. 写入策略是否与目标端主键或唯一索引结构匹配。
     * 4. 字段映射 JSON 是否合法，映射字段是否存在。
     * 5. 映射过程中是否存在明显的长度、精度和必填字段风险。
     * 6. 是否存在可供前端或运维直接使用的自动映射建议。
     */
    @Override
    @Transactional
    public Map<String, Object> validateTemplate(Long id, Long actorId, String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(actorRole,
                SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canManageTemplates()) {
            throw new IllegalStateException("当前角色无模板管理或智能校验权限: " + role.name());
        }

        SyncTemplate template = getRequiredTemplate(id);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(template.getTenantId())
                        .resourceCreatedBy(template.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE);
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());
        DataSourceConfig source = getRequiredDatasource(template.getSourceDatasourceId());
        DataSourceConfig target = getRequiredDatasource(template.getTargetDatasourceId());

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
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

        Map<String, Object> mappingSummary = defaultMappingSummary();
        if (sourceTable != null && targetTable != null) {
            validateModeRequirements(template, sourceTable, errors, warnings);
            validatePrimaryKey(template, sourceTable, targetTable, errors, warnings);
            validateWriteStrategy(template, sourceTable, targetTable, errors, warnings);
            mappingSummary = validateFieldMappings(template, sourceTable, targetTable, errors, warnings);
            validateStructureRisks(template, sourceTable, targetTable, warnings);
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

        recordAudit(
                template.getTenantId(),
                null,
                null,
                SyncAuditAction.VALIDATE_TEMPLATE,
                actorId,
                actorRole,
                buildPayload(
                        "templateId", template.getId(),
                        "passed", passed,
                        "writeStrategy", writeStrategy.name(),
                        "errorCount", errors.size(),
                        "warningCount", warnings.size()
                )
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
        preview.put("writeStrategy", SyncWriteStrategy.fromValue(template.getWriteStrategy()).name());
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

    /**
     * 根据模板里记录的 schema 和对象名去元数据发现结果里精确匹配目标对象。
     * 这里使用统一的数据源发现接口，而不是在模板服务里再写一套 JDBC 逻辑，
     * 是为了保证元数据口径一致，也避免职责再次下沉到模板层。
     */
    private TableMetadataSummary findTemplateTable(DataSourceConfig datasource, String schemaName, String objectName,
                                                   Long actorId, String actorRole) {
        MetadataDiscoveryRequest request = new MetadataDiscoveryRequest();
        request.setActorId(actorId);
        request.setActorRole(actorRole);
        request.setSchemaPattern(schemaName);
        request.setTableNamePattern(objectName);
        request.setMaxTables(5);
        request.setMaxColumnsPerTable(400);
        request.setIncludeColumns(true);
        request.setIncludeViews(true);
        request.setIncludePrimaryKeys(true);
        request.setIncludeIndexes(true);
        request.setIncludeSampleRows(false);

        DataSourceMetadataDiscoveryResult discoveryResult =
                dataSourceManagementService.discoverMetadata(datasource.getId(), request);

        return discoveryResult.getTables().stream()
                .filter(table -> table.getTableName() != null && table.getTableName().equalsIgnoreCase(objectName))
                .filter(table -> isBlank(schemaName)
                        || (table.getSchemaName() != null && table.getSchemaName().equalsIgnoreCase(schemaName)))
                .findFirst()
                .orElse(null);
    }

    /**
     * 校验同步模式与增量字段的匹配关系。
     * 这个方法解决的是“字段虽然存在，但未必真的适合当前同步模式”的问题。
     */
    private void validateModeRequirements(SyncTemplate template, TableMetadataSummary sourceTable,
                                          List<String> errors, List<String> warnings) {
        SyncMode syncMode = SyncMode.fromValue(template.getSyncMode());
        Map<String, ColumnMetadataSummary> sourceColumns = indexColumnsByName(sourceTable);
        ColumnMetadataSummary incrementalColumn = null;

        if (!isBlank(template.getIncrementalField())) {
            incrementalColumn = sourceColumns.get(normalize(template.getIncrementalField()));
            if (incrementalColumn == null) {
                errors.add("配置的增量字段在源端对象中不存在: " + template.getIncrementalField());
            }
        }

        if ((syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID)
                && isBlank(template.getIncrementalField())) {
            errors.add("当前同步模式要求配置 incrementalField，但模板中未填写");
        }

        if (incrementalColumn != null) {
            validateIncrementalFieldSuitability(syncMode, incrementalColumn, errors, warnings);
        }

        if (syncMode == SyncMode.FULL && !isBlank(template.getIncrementalField())) {
            warnings.add("当前模板是全量同步，但仍配置了 incrementalField，请确认是否真的需要保留该字段");
        }
        if ((syncMode == SyncMode.STREAMING || syncMode == SyncMode.CDC) && !isBlank(template.getIncrementalField())) {
            warnings.add("当前模板为流式或 CDC 语义，incrementalField 不一定参与实际消费位点推进，请确认该字段仅作为辅助配置");
        }
        if (Boolean.TRUE.equals(sourceTable.getColumnsTruncated())) {
            warnings.add("源端对象字段结果被截断，当前校验可能并未覆盖全部字段");
        }
    }

    /**
     * 检查增量字段是否适合当前同步模式。
     * 例如时间增量更适合 datetime 或时间戳，ID 增量更适合数值或稳定有序的字符串。
     */
    private void validateIncrementalFieldSuitability(SyncMode syncMode, ColumnMetadataSummary incrementalColumn,
                                                     List<String> errors, List<String> warnings) {
        String typeCategory = normalizeTypeCategory(incrementalColumn.getDataTypeName());
        if (syncMode == SyncMode.INCREMENTAL_TIME) {
            if ("datetime".equals(typeCategory)) {
                return;
            }
            if ("numeric".equals(typeCategory)) {
                warnings.add("增量字段 " + incrementalColumn.getColumnName()
                        + " 当前是数值型。若它表示时间戳，请在执行器层统一毫秒/秒单位，避免窗口错位");
                return;
            }
            errors.add("时间增量模式要求 incrementalField 使用时间类型或时间戳数值字段，当前字段类型为 "
                    + incrementalColumn.getDataTypeName());
            return;
        }

        if (syncMode == SyncMode.INCREMENTAL_ID) {
            if ("numeric".equals(typeCategory)) {
                return;
            }
            if ("string".equals(typeCategory)) {
                warnings.add("ID 增量字段 " + incrementalColumn.getColumnName()
                        + " 是字符串类型。只有在其排序规则稳定且业务上单调递增时才适合做增量游标");
                return;
            }
            errors.add("ID 增量模式更适合使用数值型或稳定有序的字符串字段，当前字段类型为 "
                    + incrementalColumn.getDataTypeName());
        }
    }

    /**
     * 主键字段检查负责解决两个问题：
     * 1. 模板声明的主键字段是否真的存在。
     * 2. 如果没声明，平台是否可以给出较合理的提示。
     */
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
            warnings.add("配置的主键字段在目标端对象中不存在同名字段，如依赖字段映射请确认目标字段具备唯一约束");
        }
    }

    /**
     * 写入策略检查解决的是“目标端能不能承受当前写法”。
     * 例如：
     * - UPSERT / INSERT_IGNORE / REPLACE 往往依赖主键或唯一索引；
     * - OVERWRITE 更适合全量或补数类任务，而不是流式或增量实时任务；
     * - APPEND 在回放、重试、补数场景下容易产生重复数据。
     */
    private void validateWriteStrategy(SyncTemplate template, TableMetadataSummary sourceTable, TableMetadataSummary targetTable,
                                       List<String> errors, List<String> warnings) {
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());
        SyncMode syncMode = SyncMode.fromValue(template.getSyncMode());
        String targetConflictField = resolveTargetConflictField(template, sourceTable, targetTable);

        if (writeStrategy == SyncWriteStrategy.OVERWRITE
                && syncMode != SyncMode.FULL
                && syncMode != SyncMode.OFFLINE_IMPORT
                && syncMode != SyncMode.BACKFILL) {
            errors.add("写入策略 OVERWRITE 更适合 FULL、OFFLINE_IMPORT 或 BACKFILL 场景，不建议与当前同步模式直接组合");
        }

        if (writeStrategy.requiresTargetUniqueConstraint()) {
            if (isBlank(targetConflictField)) {
                errors.add("当前写入策略 " + writeStrategy.name()
                        + " 需要能定位目标端冲突键，但模板尚未能推导出明确的目标端冲突字段");
                return;
            }

            if (hasSingleFieldUniqueConstraint(targetTable, targetConflictField)) {
                return;
            }

            if (hasCompositeUniqueConstraintContainingField(targetTable, targetConflictField)) {
                warnings.add("目标端存在包含字段 " + targetConflictField
                        + " 的组合唯一约束，但当前模板只声明了单字段冲突键。后续执行器若要稳定支持该场景，需要补组合键写入能力");
                return;
            }

            errors.add("当前写入策略 " + writeStrategy.name()
                    + " 需要目标端存在与字段 " + targetConflictField + " 对应的主键或单字段唯一索引");
            return;
        }

        if (writeStrategy == SyncWriteStrategy.APPEND
                && (syncMode == SyncMode.INCREMENTAL_TIME
                || syncMode == SyncMode.INCREMENTAL_ID
                || syncMode == SyncMode.REPLAY
                || syncMode == SyncMode.BACKFILL)) {
            warnings.add("当前模板使用 APPEND 写入策略，在增量、回放或补数场景下更容易产生重复数据，必要时可考虑 UPSERT 或 INSERT_IGNORE");
        }

        if (writeStrategy == SyncWriteStrategy.REPLACE) {
            warnings.add("REPLACE 通常意味着冲突后执行替换语义，可能带来额外写放大、触发器副作用或审计困难，请在高并发场景谨慎使用");
        }
        if (writeStrategy.isDestructiveRewrite()) {
            warnings.add("当前写入策略具备覆盖式风险，后续建议配合审批、审计和运行前确认机制使用");
        }
    }

    /**
     * 字段映射检查是当前模板智能校验中最接近真实执行风险的部分。
     * 除了检查字段是否存在，还会尽量识别：
     * - 类型大类是否明显不兼容；
     * - 长度和精度是否可能截断；
     * - 目标端非空且无默认值字段是否被遗漏；
     * - 是否存在同名字段，可供平台生成自动映射建议。
     */
    private Map<String, Object> validateFieldMappings(SyncTemplate template, TableMetadataSummary sourceTable,
                                                      TableMetadataSummary targetTable, List<String> errors,
                                                      List<String> warnings) {
        Map<String, Object> summary = defaultMappingSummary();
        Map<String, ColumnMetadataSummary> sourceColumns = indexColumnsByName(sourceTable);
        Map<String, ColumnMetadataSummary> targetColumns = indexColumnsByName(targetTable);

        if (isBlank(template.getFieldMappingConfig())) {
            List<String> unmappedRequiredTargetFields = findUnmappedRequiredTargetFields(targetTable, Set.of());
            List<String> autoMappingSuggestions = buildAutoMappingSuggestions(sourceTable, targetTable, Set.of());

            warnings.add("模板尚未配置字段映射，当前只能做结构级校验，无法做完整的字段级兼容性检查");
            if (!unmappedRequiredTargetFields.isEmpty()) {
                warnings.add("目标端存在非空且无默认值字段，当前未配置显式映射: "
                        + String.join(", ", unmappedRequiredTargetFields));
            }

            summary.put("unmappedRequiredTargetFields", unmappedRequiredTargetFields);
            summary.put("autoMappingSuggestions", autoMappingSuggestions);
            return summary;
        }

        ParsedFieldMappings parsedFieldMappings = parseFieldMappings(template.getFieldMappingConfig(), errors, true);
        if (!parsedFieldMappings.valid()) {
            return summary;
        }

        Set<String> mappedSourceFields = new HashSet<>();
        Set<String> mappedTargetFields = new HashSet<>();
        List<String> missingSourceFields = new ArrayList<>();
        List<String> missingTargetFields = new ArrayList<>();
        List<String> typeMismatchMappings = new ArrayList<>();
        List<String> truncationRiskMappings = new ArrayList<>();
        int mappingCount = 0;
        int validMappingCount = 0;

        for (FieldMappingPair mapping : parsedFieldMappings.mappings()) {
            mappingCount++;
            if (isBlank(mapping.sourceField()) || isBlank(mapping.targetField())) {
                errors.add("第 " + mappingCount + " 条字段映射缺少 sourceField 或 targetField");
                continue;
            }

            mappedSourceFields.add(normalize(mapping.sourceField()));
            mappedTargetFields.add(normalize(mapping.targetField()));

            ColumnMetadataSummary sourceColumn = sourceColumns.get(normalize(mapping.sourceField()));
            ColumnMetadataSummary targetColumn = targetColumns.get(normalize(mapping.targetField()));
            if (sourceColumn == null) {
                missingSourceFields.add(mapping.sourceField());
                continue;
            }
            if (targetColumn == null) {
                missingTargetFields.add(mapping.targetField());
                continue;
            }

            validMappingCount++;
            if (!isCompatibleType(sourceColumn.getDataTypeName(), targetColumn.getDataTypeName())) {
                typeMismatchMappings.add(mapping.sourceField() + " -> " + mapping.targetField()
                        + " (" + sourceColumn.getDataTypeName() + " -> " + targetColumn.getDataTypeName() + ")");
            }

            String truncationRisk = buildTruncationRisk(sourceColumn, targetColumn, mapping);
            if (truncationRisk != null) {
                truncationRiskMappings.add(truncationRisk);
            }
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
        if (!truncationRiskMappings.isEmpty()) {
            warnings.add("发现长度或精度截断风险: " + String.join("; ", truncationRiskMappings));
        }

        List<String> unmappedRequiredTargetFields = findUnmappedRequiredTargetFields(targetTable, mappedTargetFields);
        if (!unmappedRequiredTargetFields.isEmpty()) {
            errors.add("目标端存在必填且无默认值字段未被映射: " + String.join(", ", unmappedRequiredTargetFields));
        }

        List<String> autoMappingSuggestions = buildAutoMappingSuggestions(sourceTable, targetTable, mappedTargetFields);
        summary.put("mappingCount", mappingCount);
        summary.put("validMappingCount", validMappingCount);
        summary.put("missingSourceFields", missingSourceFields);
        summary.put("missingTargetFields", missingTargetFields);
        summary.put("typeMismatchMappings", typeMismatchMappings);
        summary.put("truncationRiskMappings", truncationRiskMappings);
        summary.put("unmappedRequiredTargetFields", unmappedRequiredTargetFields);
        summary.put("autoMappingSuggestions", autoMappingSuggestions);
        summary.put("mappedSourceFields", mappedSourceFields);
        summary.put("mappedTargetFields", mappedTargetFields);
        return summary;
    }

    /**
     * 结构风险提示更多偏向“现在能跑，但放量后可能出问题”的类型。
     * 这类信息不一定阻断保存，但非常适合提前告诉用户。
     */
    private void validateStructureRisks(SyncTemplate template, TableMetadataSummary sourceTable,
                                        TableMetadataSummary targetTable, List<String> warnings) {
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());

        if (Boolean.TRUE.equals(targetTable.getColumnsTruncated())) {
            warnings.add("目标端对象字段结果被截断，当前校验可能并未覆盖全部目标字段");
        }
        if ((targetTable.getIndexes() == null || targetTable.getIndexes().isEmpty())
                && (targetTable.getPrimaryKeys() == null || targetTable.getPrimaryKeys().isEmpty())) {
            warnings.add("目标端对象未发现主键或索引，后续 upsert、去重和高并发写入性能可能存在明显风险");
        }
        if (isBlank(template.getFieldMappingConfig())
                && sourceTable.getTotalColumnCount() != null
                && targetTable.getTotalColumnCount() != null
                && !sourceTable.getTotalColumnCount().equals(targetTable.getTotalColumnCount())) {
            warnings.add("源端与目标端字段数量不同，但模板未显式配置字段映射，后续执行可能出现字段错位或写入失败");
        }
        if (writeStrategy == SyncWriteStrategy.APPEND && hasAnyUniqueConstraint(targetTable)) {
            warnings.add("目标端存在主键或唯一索引，APPEND 在重复写入时可能直接触发唯一约束冲突");
        }
    }

    /**
     * 构建面向接口返回的对象摘要。
     * 这里刻意不直接把完整表结构塞进 validate 结果里，是为了让校验结果更聚焦在决策信息上。
     */
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

    /**
     * 按字段名建立索引，便于后续频繁做字段存在性和类型兼容判断。
     */
    private Map<String, ColumnMetadataSummary> indexColumnsByName(TableMetadataSummary table) {
        return table.getColumns() == null ? Map.of() : table.getColumns().stream()
                .filter(column -> !isBlank(column.getColumnName()))
                .collect(Collectors.toMap(
                        column -> normalize(column.getColumnName()),
                        column -> column,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Set<String> extractColumnNames(TableMetadataSummary table) {
        return table.getColumns() == null ? Set.of() : table.getColumns().stream()
                .map(ColumnMetadataSummary::getColumnName)
                .filter(columnName -> !isBlank(columnName))
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    private boolean isCompatibleType(String sourceType, String targetType) {
        String sourceCategory = normalizeTypeCategory(sourceType);
        String targetCategory = normalizeTypeCategory(targetType);
        return sourceCategory.equals(targetCategory)
                || "unknown".equals(sourceCategory)
                || "unknown".equals(targetCategory);
    }

    /**
     * 将数据库原生字段类型折叠为平台内部可理解的类型大类。
     * 这样做不是为了替代精确类型系统，而是为了先快速发现明显不合理的映射。
     */
    private String normalizeTypeCategory(String dataTypeName) {
        if (isBlank(dataTypeName)) {
            return "unknown";
        }
        String normalized = dataTypeName.toLowerCase(Locale.ROOT);
        if (normalized.contains("char") || normalized.contains("text") || normalized.contains("clob")
                || normalized.contains("json") || normalized.contains("xml")) {
            return "string";
        }
        if (normalized.contains("int") || normalized.contains("number") || normalized.contains("decimal")
                || normalized.contains("numeric") || normalized.contains("double") || normalized.contains("float")
                || normalized.contains("real")) {
            return "numeric";
        }
        if (normalized.contains("date") || normalized.contains("time") || normalized.contains("timestamp")) {
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

    /**
     * 根据模板里的主键字段和映射关系，尽量推导目标端用于冲突判定的字段。
     * 这个能力是执行前检查的关键，因为 UPSERT 类策略必须知道冲突键在哪里。
     */
    private String resolveTargetConflictField(SyncTemplate template, TableMetadataSummary sourceTable,
                                              TableMetadataSummary targetTable) {
        Map<String, ColumnMetadataSummary> targetColumns = indexColumnsByName(targetTable);
        if (!isBlank(template.getPrimaryKeyField())) {
            if (targetColumns.containsKey(normalize(template.getPrimaryKeyField()))) {
                return template.getPrimaryKeyField();
            }
            String mappedTargetField = mapSourceFieldToTargetField(template.getFieldMappingConfig(), template.getPrimaryKeyField());
            if (!isBlank(mappedTargetField)) {
                return mappedTargetField;
            }
        }

        if (sourceTable.getPrimaryKeys() != null && sourceTable.getPrimaryKeys().size() == 1) {
            String sourcePrimaryKey = sourceTable.getPrimaryKeys().get(0);
            if (targetColumns.containsKey(normalize(sourcePrimaryKey))) {
                return sourcePrimaryKey;
            }
            String mappedTargetField = mapSourceFieldToTargetField(template.getFieldMappingConfig(), sourcePrimaryKey);
            if (!isBlank(mappedTargetField)) {
                return mappedTargetField;
            }
        }
        return null;
    }

    private String mapSourceFieldToTargetField(String fieldMappingConfig, String sourceField) {
        ParsedFieldMappings parsedFieldMappings = parseFieldMappings(fieldMappingConfig, null, false);
        if (!parsedFieldMappings.valid()) {
            return null;
        }
        return parsedFieldMappings.mappings().stream()
                .filter(mapping -> !isBlank(mapping.sourceField()) && normalize(mapping.sourceField()).equals(normalize(sourceField)))
                .map(FieldMappingPair::targetField)
                .filter(targetField -> !isBlank(targetField))
                .findFirst()
                .orElse(null);
    }

    private ParsedFieldMappings parseFieldMappings(String fieldMappingConfig, List<String> errors, boolean reportErrors) {
        if (isBlank(fieldMappingConfig)) {
            return new ParsedFieldMappings(true, List.of());
        }
        try {
            JsonNode rootNode = objectMapper.readTree(fieldMappingConfig);
            JsonNode mappingsNode = rootNode.isArray() ? rootNode : rootNode.path("mappings");
            if (!mappingsNode.isArray()) {
                if (reportErrors && errors != null) {
                    errors.add("fieldMappingConfig 不是合法映射数组，建议使用数组或 {\"mappings\": [...]} 结构");
                }
                return new ParsedFieldMappings(false, List.of());
            }

            List<FieldMappingPair> mappings = new ArrayList<>();
            for (JsonNode mappingNode : mappingsNode) {
                mappings.add(new FieldMappingPair(
                        textOf(mappingNode, "sourceField"),
                        textOf(mappingNode, "targetField")
                ));
            }
            return new ParsedFieldMappings(true, mappings);
        } catch (Exception exception) {
            if (reportErrors && errors != null) {
                errors.add("fieldMappingConfig 不是合法 JSON: " + exception.getMessage());
            }
            return new ParsedFieldMappings(false, List.of());
        }
    }

    private List<String> buildAutoMappingSuggestions(TableMetadataSummary sourceTable, TableMetadataSummary targetTable,
                                                     Set<String> mappedTargetFields) {
        Map<String, ColumnMetadataSummary> sourceColumns = indexColumnsByName(sourceTable);
        if (targetTable.getColumns() == null) {
            return List.of();
        }

        List<String> suggestions = new ArrayList<>();
        for (ColumnMetadataSummary targetColumn : targetTable.getColumns()) {
            if (targetColumn == null || isBlank(targetColumn.getColumnName())) {
                continue;
            }
            String normalizedTargetField = normalize(targetColumn.getColumnName());
            if (mappedTargetFields.contains(normalizedTargetField)) {
                continue;
            }
            ColumnMetadataSummary sourceColumn = sourceColumns.get(normalizedTargetField);
            if (sourceColumn != null) {
                suggestions.add(sourceColumn.getColumnName() + " -> " + targetColumn.getColumnName()
                        + "（源端与目标端同名，可作为自动映射候选）");
            }
        }
        return suggestions.stream().limit(20).toList();
    }

    private List<String> findUnmappedRequiredTargetFields(TableMetadataSummary targetTable, Set<String> mappedTargetFields) {
        if (targetTable.getColumns() == null) {
            return List.of();
        }
        return targetTable.getColumns().stream()
                .filter(column -> column != null && !isBlank(column.getColumnName()))
                .filter(column -> !column.isNullable())
                .filter(column -> !column.isAutoIncrement())
                .filter(column -> isBlank(column.getDefaultValue()))
                .map(ColumnMetadataSummary::getColumnName)
                .filter(columnName -> !mappedTargetFields.contains(normalize(columnName)))
                .toList();
    }

    /**
     * 这里尝试识别两类高频风险：
     * 1. 字符串长度被压缩，导致内容截断。
     * 2. 数值精度或小数位被压缩，导致精度丢失。
     */
    private String buildTruncationRisk(ColumnMetadataSummary sourceColumn, ColumnMetadataSummary targetColumn,
                                       FieldMappingPair mapping) {
        String sourceCategory = normalizeTypeCategory(sourceColumn.getDataTypeName());
        String targetCategory = normalizeTypeCategory(targetColumn.getDataTypeName());
        if (!sourceCategory.equals(targetCategory)) {
            return null;
        }

        if ("string".equals(sourceCategory)
                && sourceColumn.getColumnSize() != null
                && targetColumn.getColumnSize() != null
                && sourceColumn.getColumnSize() > targetColumn.getColumnSize()) {
            return mapping.sourceField() + " -> " + mapping.targetField()
                    + " 可能发生长度截断（源长度=" + sourceColumn.getColumnSize()
                    + "，目标长度=" + targetColumn.getColumnSize() + "）";
        }

        if ("numeric".equals(sourceCategory)
                && sourceColumn.getColumnSize() != null
                && targetColumn.getColumnSize() != null
                && sourceColumn.getColumnSize() > targetColumn.getColumnSize()) {
            return mapping.sourceField() + " -> " + mapping.targetField()
                    + " 可能发生数值精度截断（源精度=" + sourceColumn.getColumnSize()
                    + "，目标精度=" + targetColumn.getColumnSize() + "）";
        }

        if ("numeric".equals(sourceCategory)
                && sourceColumn.getDecimalDigits() != null
                && targetColumn.getDecimalDigits() != null
                && sourceColumn.getDecimalDigits() > targetColumn.getDecimalDigits()) {
            return mapping.sourceField() + " -> " + mapping.targetField()
                    + " 可能发生小数位截断（源小数位=" + sourceColumn.getDecimalDigits()
                    + "，目标小数位=" + targetColumn.getDecimalDigits() + "）";
        }
        return null;
    }

    private boolean hasAnyUniqueConstraint(TableMetadataSummary table) {
        boolean hasPrimaryKeys = table.getPrimaryKeys() != null && !table.getPrimaryKeys().isEmpty();
        boolean hasUniqueIndex = table.getIndexes() != null && table.getIndexes().stream().anyMatch(IndexMetadataSummary::isUnique);
        return hasPrimaryKeys || hasUniqueIndex;
    }

    private boolean hasSingleFieldUniqueConstraint(TableMetadataSummary table, String fieldName) {
        String normalizedFieldName = normalize(fieldName);
        if (table.getPrimaryKeys() != null
                && table.getPrimaryKeys().size() == 1
                && normalize(table.getPrimaryKeys().get(0)).equals(normalizedFieldName)) {
            return true;
        }
        if (table.getIndexes() == null) {
            return false;
        }
        return table.getIndexes().stream()
                .filter(IndexMetadataSummary::isUnique)
                .filter(index -> index.getColumnNames() != null && index.getColumnNames().size() == 1)
                .map(index -> index.getColumnNames().get(0))
                .filter(columnName -> !isBlank(columnName))
                .anyMatch(columnName -> normalize(columnName).equals(normalizedFieldName));
    }

    private boolean hasCompositeUniqueConstraintContainingField(TableMetadataSummary table, String fieldName) {
        String normalizedFieldName = normalize(fieldName);
        if (table.getPrimaryKeys() != null
                && table.getPrimaryKeys().size() > 1
                && table.getPrimaryKeys().stream().map(this::normalize).anyMatch(normalizedFieldName::equals)) {
            return true;
        }
        if (table.getIndexes() == null) {
            return false;
        }
        return table.getIndexes().stream()
                .filter(IndexMetadataSummary::isUnique)
                .filter(index -> index.getColumnNames() != null && index.getColumnNames().size() > 1)
                .anyMatch(index -> index.getColumnNames().stream()
                        .filter(columnName -> !isBlank(columnName))
                        .map(this::normalize)
                        .anyMatch(normalizedFieldName::equals));
    }

    private long countUniqueIndexes(TableMetadataSummary table) {
        if (table.getIndexes() == null) {
            return 0;
        }
        return table.getIndexes().stream().filter(IndexMetadataSummary::isUnique).count();
    }

    private Map<String, Object> defaultMappingSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mappingCount", 0);
        summary.put("validMappingCount", 0);
        summary.put("missingSourceFields", List.of());
        summary.put("missingTargetFields", List.of());
        summary.put("typeMismatchMappings", List.of());
        summary.put("truncationRiskMappings", List.of());
        summary.put("unmappedRequiredTargetFields", List.of());
        summary.put("autoMappingSuggestions", List.of());
        return summary;
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
        return datasource != null
                && DataSourceStatus.ACTIVE.equals(datasource.getStatus())
                && !DataSourceStatus.DELETED.equals(datasource.getStatus());
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

    /**
     * 内部字段映射对。
     * 使用 record 可以让“源字段 -> 目标字段”这类结构在代码里表达得更直接。
     */
    private record FieldMappingPair(String sourceField, String targetField) {
    }

    /**
     * 字段映射 JSON 解析结果。
     * valid 表示 JSON 结构是否合法，mappings 表示提取出的映射对列表。
     */
    private record ParsedFieldMappings(boolean valid, List<FieldMappingPair> mappings) {
    }
}
