/**
 * @Author : Cui
 * @Date: 2026/05/05 23:25
 * @Description DataSmart Govern Backend - SyncTemplateServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasource.service.SyncTemplateService;
import com.czh.datasmart.govern.datasource.service.support.SyncTemplateAuditSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncTemplateValidationSupport;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncMode;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 同步模板服务实现。
 *
 * <p>同步模板是 datasource-management 中连接“数据源配置”和“同步任务”的可复用配置资产。
 * 它描述了从哪个源对象同步到哪个目标对象、使用什么同步模式、采用什么写入策略、
 * 主键/增量字段如何选择，以及字段映射、过滤、分区、重试、超时等执行参数。</p>
 *
 * <p>本轮重构后，该类只保留应用服务层应该承担的职责：
 * 1. 接收接口请求并声明事务边界；
 * 2. 校验模板名称唯一性、数据源存在性和启用状态；
 * 3. 组装模板实体并调用 MyBatis-Plus 持久化；
 * 4. 把智能校验和审计记录委托给独立 support 组件。</p>
 *
 * <p>这种拆法的核心目的不是“为了拆而拆”，而是让模板主服务避免继续膨胀为上千行：
 * 字段映射校验、元数据发现、写入策略风险、审计 payload 都是可独立演进的产品能力线。
 * 后续新增 PostgreSQL/MySQL 方言差异、CDC 模板、文件/API 模板、审批发布、模板版本化时，
 * 可以在对应 support 或新策略组件中扩展，而不是反复改动主服务。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncTemplateServiceImpl extends ServiceImpl<SyncTemplateMapper, SyncTemplate>
        implements SyncTemplateService {

    /**
     * 数据源配置 Mapper。
     *
     * <p>模板创建和更新时需要确认源数据源、目标数据源都真实存在且处于 ACTIVE 状态。
     * 这里仍使用本地 Mapper 是因为这是模板生命周期的前置校验，不涉及外部连接或元数据扫描。</p>
     */
    private final DataSourceConfigMapper dataSourceConfigMapper;

    /**
     * 权限评估器。
     *
     * <p>创建和更新模板会改变可复用同步配置，因此必须校验模板管理权限和租户边界。</p>
     */
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    /**
     * 模板智能校验组件。
     *
     * <p>复杂的执行前风险评估被下沉到该组件，主服务只负责加载模板和数据源并转发上下文。</p>
     */
    private final SyncTemplateValidationSupport syncTemplateValidationSupport;

    /**
     * 模板审计组件。
     *
     * <p>模板创建、更新、校验都会影响后续任务执行语义，需要留下可追溯记录。</p>
     */
    private final SyncTemplateAuditSupport syncTemplateAuditSupport;

    /**
     * 创建同步模板。
     *
     * @param request 创建请求，包含模板基础信息、源/目标对象、同步模式、写入策略和执行参数。
     * @return 已持久化的同步模板实体。
     */
    @Override
    @Transactional
    public SyncTemplate createTemplate(CreateSyncTemplateRequest request) {
        assertTemplateManagePermission(request.getCreatedBy(), request.getActorRole(),
                request.getActorTenantId(), request.getTenantId(), null);
        ensureTemplateNameUnique(request.getTenantId(), request.getProjectId(), request.getName(), null);
        validateDatasourcePair(request.getSourceDatasourceId(), request.getTargetDatasourceId());
        SyncMode syncMode = SyncMode.fromValue(request.getSyncMode());
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(request.getWriteStrategy());

        SyncTemplate template = new SyncTemplate();
        template.setTenantId(request.getTenantId());
        template.setProjectId(request.getProjectId());
        template.setWorkspaceId(request.getWorkspaceId());
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

        recordTemplateAudit(template, SyncAuditAction.CREATE_TEMPLATE, request.getCreatedBy(), request.getActorRole());
        return template;
    }

    /**
     * 更新同步模板。
     *
     * <p>模板被多个同步任务复用时，更新模板会影响未来任务创建和校验语义。
     * 当前阶段直接更新主模板；商业化版本建议继续补充模板版本、发布审批、灰度启用和历史任务快照隔离。</p>
     */
    @Override
    @Transactional
    public SyncTemplate updateTemplate(Long id, UpdateSyncTemplateRequest request) {
        SyncTemplate template = getRequiredTemplate(id);
        assertTemplateManagePermission(request.getUpdatedBy(), request.getActorRole(),
                request.getActorTenantId(), template.getTenantId(), template.getCreatedBy());
        ensureTemplateNameUnique(template.getTenantId(), template.getProjectId(), request.getName(), id);
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

        recordTemplateAudit(template, SyncAuditAction.UPDATE_TEMPLATE, request.getUpdatedBy(), request.getActorRole());
        return template;
    }

    /**
     * 智能校验模板。
     *
     * <p>主服务只负责加载模板和数据源，并把复杂校验委托给 `SyncTemplateValidationSupport`。
     * 校验结果会包含 errors、warnings、字段映射摘要和对象摘要：errors 用于阻断执行，
     * warnings 用于提示生产风险，例如覆盖写入、字段截断、CDC 位点缺失等。</p>
     */
    @Override
    @Transactional
    public Map<String, Object> validateTemplate(Long id, Long actorId, String actorRole, Long actorTenantId) {
        SyncTemplate template = getRequiredTemplate(id);
        DataSourceConfig source = getRequiredDatasource(template.getSourceDatasourceId());
        DataSourceConfig target = getRequiredDatasource(template.getTargetDatasourceId());
        Map<String, Object> result = syncTemplateValidationSupport.validate(
                template, source, target, actorId, actorRole, actorTenantId);

        List<?> errors = result.get("errors") instanceof List<?> list ? list : List.of();
        List<?> warnings = result.get("warnings") instanceof List<?> list ? list : List.of();
        syncTemplateAuditSupport.recordTemplateAudit(
                template.getTenantId(),
                SyncAuditAction.VALIDATE_TEMPLATE,
                actorId,
                actorRole,
                syncTemplateAuditSupport.buildPayload(
                        "templateId", template.getId(),
                        "passed", Boolean.TRUE.equals(result.get("passed")),
                        "writeStrategy", SyncWriteStrategy.fromValue(template.getWriteStrategy()).name(),
                        "errorCount", errors.size(),
                        "warningCount", warnings.size()
                )
        );
        return result;
    }

    /**
     * 预览同步模板。
     *
     * <p>预览接口不做元数据探查，只把模板配置和数据源基础展示信息合并返回。
     * 它适合模板详情页快速展示；如果需要判断模板能否执行，应调用智能校验接口。</p>
     */
    @Override
    public Map<String, Object> previewTemplate(Long id) {
        SyncTemplate template = getRequiredTemplate(id);
        DataSourceConfig source = getRequiredDatasource(template.getSourceDatasourceId());
        DataSourceConfig target = getRequiredDatasource(template.getTargetDatasourceId());

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("templateId", template.getId());
        preview.put("templateName", template.getName());
        preview.put("tenantId", template.getTenantId());
        preview.put("projectId", template.getProjectId());
        preview.put("workspaceId", template.getWorkspaceId());
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

    private void assertTemplateManagePermission(Long actorId, String actorRole, Long actorTenantId,
                                                Long resourceTenantId, Long resourceCreatedBy) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(resourceTenantId)
                        .resourceCreatedBy(resourceCreatedBy)
                        .build(),
                SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE);
    }

    private void recordTemplateAudit(SyncTemplate template, SyncAuditAction action, Long actorId, String actorRole) {
        syncTemplateAuditSupport.recordTemplateAudit(
                template.getTenantId(),
                action,
                actorId,
                actorRole,
                syncTemplateAuditSupport.buildPayload(
                        "templateId", template.getId(),
                        "templateName", template.getName(),
                        "projectId", template.getProjectId(),
                        "workspaceId", template.getWorkspaceId(),
                        "sourceObjectName", template.getSourceObjectName(),
                        "targetObjectName", template.getTargetObjectName(),
                        "writeStrategy", template.getWriteStrategy()
                )
        );
    }

    private SyncTemplate getRequiredTemplate(Long id) {
        SyncTemplate template = getById(id);
        if (template == null) {
            throw new NoSuchElementException("同步模板不存在: " + id);
        }
        return template;
    }

    private void ensureTemplateNameUnique(Long tenantId, Long projectId, String name, Long currentId) {
        LambdaQueryWrapper<SyncTemplate> wrapper = new LambdaQueryWrapper<SyncTemplate>()
                .eq(SyncTemplate::getTenantId, tenantId)
                .eq(SyncTemplate::getProjectId, projectId)
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
}
