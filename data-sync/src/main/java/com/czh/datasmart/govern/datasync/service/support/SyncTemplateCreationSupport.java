/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - SyncTemplateCreationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 同步模板创建支撑组件。
 *
 * <p>这个类把 DataSyncServiceImpl 中原本较长的 createTemplate 流程拆出来。它不是新的领域服务边界，
 * 而是主服务内部的“创建工作流执行器”：负责把请求 DTO 转成实体、做项目写入校验、补全连接器事实、
 * 调用模板校验、写库并记录审计。</p>
 *
 * <p>拆分的设计意义：</p>
 * <p>1. 主 Service 保持入口编排职责，不再因为每个新能力都增长几十行而越过 500 行；</p>
 * <p>2. 模板创建的业务流程可以单独测试和阅读，适合作为学习“一个商业化后端创建流程如何分层”的样例；</p>
 * <p>3. 后续如果增加模板预览、审批草稿、字段映射校验或写入策略校验，可以继续在本组件内拆小方法，
 * 不需要改动任务运行、执行回调、checkpoint 查询等不相关逻辑。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTemplateCreationSupport {

    private final SyncTemplateMapper templateMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncQuerySupport querySupport;
    private final SyncTemplateConnectorFactResolver connectorFactResolver;
    private final SyncTemplateValidationSupport templateValidationSupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 创建同步模板。
     *
     * @param request 创建请求，来自 Controller 层，仍保留原始用户输入。
     * @param actorContext 调用者上下文，包含租户、操作者、角色、traceId 和数据范围。
     * @return 已落库的同步模板实体。
     */
    public SyncTemplate createTemplate(CreateSyncTemplateRequest request, SyncActorContext actorContext) {
        SyncTemplate template = buildTemplate(request, actorContext);
        /*
         * 写入类动作必须先校验项目范围，再做远程能力快照调用。
         * 这样可以防止未授权用户通过构造未授权 projectId 触发 datasource-management 查询，
         * 即使快照接口本身也有低敏和权限保护，data-sync 入口仍应该先完成自己的写入边界判断。
         */
        dataScopeSupport.validateProjectWritable(
                template.getTenantId(), template.getProjectId(), template.getWorkspaceId(), actorContext, "同步模板");
        /*
         * connector fact 补全放在模板业务校验之前。
         * 原因是 SyncTemplateValidationSupport 的连接器兼容性检查依赖 sourceConnectorType/targetConnectorType；
         * 如果用户没传，而 datasource-management 已经有可信低敏快照，就应该先补全再校验。
         */
        connectorFactResolver.resolveConnectorFacts(template, actorContext);
        templateValidationSupport.validateTemplate(template);
        templateMapper.insert(template);
        auditSupport.saveTemplateAudit(template, SyncAuditActionType.CREATE_TEMPLATE,
                actorContext, auditSummary(template));
        return template;
    }

    /**
     * 将创建请求转换为 SyncTemplate 实体。
     *
     * <p>这个步骤只做字段清洗和默认值填充，不做跨服务调用，也不写数据库。把它拆出来可以清楚区分：
     * “请求如何变成领域对象”和“领域对象是否允许被保存”是两个不同层次。</p>
     */
    private SyncTemplate buildTemplate(CreateSyncTemplateRequest request, SyncActorContext actorContext) {
        SyncTemplate template = new SyncTemplate();
        template.setTenantId(dataScopeSupport.resolveTenantForCreate(request.getTenantId(), actorContext));
        template.setProjectId(request.getProjectId());
        template.setWorkspaceId(request.getWorkspaceId());
        template.setName(request.getName().trim());
        template.setDescription(querySupport.trimToNull(request.getDescription()));
        template.setSourceDatasourceId(request.getSourceDatasourceId());
        template.setTargetDatasourceId(request.getTargetDatasourceId());
        template.setSourceSchemaName(querySupport.trimToNull(request.getSourceSchemaName()));
        template.setSourceObjectName(querySupport.trimToNull(request.getSourceObjectName()));
        template.setTargetSchemaName(querySupport.trimToNull(request.getTargetSchemaName()));
        template.setTargetObjectName(querySupport.trimToNull(request.getTargetObjectName()));
        template.setSourceConnectorType(querySupport.normalizeCode(request.getSourceConnectorType()));
        template.setTargetConnectorType(querySupport.normalizeCode(request.getTargetConnectorType()));
        template.setSyncMode(querySupport.normalizeCode(request.getSyncMode()));
        template.setSyncScopeType(querySupport.normalizeCode(request.getSyncScopeType()));
        template.setWriteStrategy(querySupport.normalizeCode(request.getWriteStrategy()));
        template.setPrimaryKeyField(querySupport.trimToNull(request.getPrimaryKeyField()));
        template.setIncrementalField(querySupport.trimToNull(request.getIncrementalField()));
        template.setFieldMappingConfig(querySupport.trimToNull(request.getFieldMappingConfig()));
        template.setObjectMappingConfig(querySupport.trimToNull(request.getObjectMappingConfig()));
        template.setFilterConfig(querySupport.trimToNull(request.getFilterConfig()));
        template.setCustomSqlConfig(querySupport.trimToNull(request.getCustomSqlConfig()));
        template.setPartitionConfig(querySupport.trimToNull(request.getPartitionConfig()));
        template.setRetryPolicy(querySupport.trimToNull(request.getRetryPolicy()));
        template.setTimeoutPolicy(querySupport.trimToNull(request.getTimeoutPolicy()));
        template.setEnabled(true);
        template.setCreatedBy(querySupport.actorId(actorContext));
        template.setUpdatedBy(querySupport.actorId(actorContext));
        LocalDateTime now = LocalDateTime.now();
        template.setCreateTime(now);
        template.setUpdateTime(now);
        return template;
    }

    /**
     * 生成低敏审计摘要。
     *
     * <p>审计摘要只记录模板 ID、syncMode、连接器枚举、写入策略和关键配置是否声明，不记录 datasource 连接详情、
     * 源/目标对象名称、字段映射 JSON、过滤条件、SQL、样本、凭据或内部 endpoint。对象名和字段名虽然不是凭据，
     * 但在真实客户环境中也可能透露业务域，因此普通审计只保存布尔摘要，详细配置仍留在受权限保护的模板详情中。</p>
     */
    private String auditSummary(SyncTemplate template) {
        return "templateId=" + template.getId()
                + ",syncMode=" + template.getSyncMode()
                + ",syncScopeType=" + template.getSyncScopeType()
                + ",sourceConnectorType=" + template.getSourceConnectorType()
                + ",targetConnectorType=" + template.getTargetConnectorType()
                + ",writeStrategy=" + template.getWriteStrategy()
                + ",sourceObjectDeclared=" + hasText(template.getSourceObjectName())
                + ",targetObjectDeclared=" + hasText(template.getTargetObjectName())
                + ",objectMappingDeclared=" + hasText(template.getObjectMappingConfig())
                + ",customSqlDeclared=" + hasText(template.getCustomSqlConfig())
                + ",primaryKeyDeclared=" + hasText(template.getPrimaryKeyField())
                + ",incrementalFieldDeclared=" + hasText(template.getIncrementalField());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
