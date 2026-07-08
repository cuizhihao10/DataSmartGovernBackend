/**
 * @Author : Cui
 * @Date: 2026/07/08 22:38
 * @Description DataSmart Govern Backend - SyncTaskCreateWizardDraftSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.support.SyncApprovalState;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 同步任务创建向导草稿保存支撑组件。
 *
 * <p>该组件承接用户提出的一个核心产品体验：从第二步开始任务就应该被保存，状态为“编辑中”，
 * 即使关闭弹窗也能在任务列表里继续编辑。为了实现这个体验，后端不能再等到第四步才一次性创建模板和任务，
 * 而是要在第一步进入第二步时就创建 {@link SyncTemplate} + {@link SyncTask}，并把任务主状态固定为
 * {@link SyncTaskState#DRAFT}。</p>
 *
 * <p>为什么不复用 {@code createTemplate + createTask}：</p>
 * <p>1. {@code createTemplate} 面向完整模板，会执行较严格的模板校验；草稿阶段对象映射、字段映射、SQL 可能尚未完整；</p>
 * <p>2. {@code createTask} 会执行预检查并把任务推进到 CONFIGURED/SCHEDULED，它不适合半成品配置；</p>
 * <p>3. 创建向导需要多次保存同一条任务，如果沿用原创建接口，容易每点一次下一步就创建重复任务。</p>
 *
 * <p>本组件的安全边界非常明确：</p>
 * <p>- 可以保存低敏配置；</p>
 * <p>- 可以校验租户、项目、源/目标数据源方向、用户可选同步模式和写入策略；</p>
 * <p>- 不运行 SQL、不读取源端数据、不写目标表、不创建 execution、不启用 scheduleEnabled；</p>
 * <p>- 目标 schema/table 是否存在、字段映射是否正确、字段数量是否匹配、目标约束是否满足，留给第四步预检查。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskCreateWizardDraftSupport {

    private static final String DEFAULT_PRIORITY = "MEDIUM";

    private final SyncTemplateMapper templateMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncQuerySupport querySupport;
    private final SyncTemplateConnectorFactResolver connectorFactResolver;
    private final SyncTaskGroupOperationSupport taskGroupOperationSupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 保存创建向导草稿。
     *
     * <p>方法根据 {@code request.taskId} 自动判断创建或更新：</p>
     * <p>1. taskId 为空：创建模板草稿和任务草稿；</p>
     * <p>2. taskId 不为空：校验任务存在、当前状态仍是 DRAFT、当前用户可读写，然后更新模板与任务定义。</p>
     *
     * @param request 创建向导当前步骤表单快照
     * @param actorContext 当前操作者上下文
     * @return 保存后的任务/模板 ID 和下一步建议
     */
    public SyncTaskCreateWizardDraftSaveResponse saveDraft(SyncTaskCreateWizardDraftSaveRequest request,
                                                           SyncActorContext actorContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "创建向导草稿保存请求不能为空");
        }
        if (request.getTaskId() == null) {
            return createDraft(request, actorContext);
        }
        return updateDraft(request, actorContext);
    }

    /**
     * 创建新的草稿模板与草稿任务。
     *
     * <p>这里会做最小必要校验：任务名、源端、目标端、传输模式、写入策略、数据范围和连接器能力方向。
     * 不要求对象映射或字段映射完整，是因为用户刚进入第二步时本来就还没完成这些配置。</p>
     */
    private SyncTaskCreateWizardDraftSaveResponse createDraft(SyncTaskCreateWizardDraftSaveRequest request,
                                                              SyncActorContext actorContext) {
        DraftBasics basics = resolveBasics(request, actorContext);
        SyncTemplate template = buildTemplate(null, request, basics, actorContext);
        connectorFactResolver.resolveConnectorFacts(template, actorContext);
        templateMapper.insert(template);

        SyncTask task = buildTask(null, template, request, basics, actorContext);
        taskMapper.insert(task);

        auditSupport.saveTemplateAudit(template, SyncAuditActionType.CREATE_TEMPLATE, actorContext,
                "createWizardDraft=true,stepCode=" + normalizeStepCode(request.getStepCode()));
        auditSupport.saveAudit(task.getTenantId(), task.getId(), null, SyncAuditActionType.CREATE_TASK,
                actorContext, "createWizardDraft=true,templateId=" + template.getId()
                        + ",stepCode=" + normalizeStepCode(request.getStepCode())
                        + ",state=" + task.getCurrentState());
        return response(task, template, true, request.getStepCode());
    }

    /**
     * 更新已有草稿。
     *
     * <p>只有 DRAFT 任务允许通过创建向导更新。发布后的任务需要走“任务编辑 + 发布”流程，
     * 因为它已经可能关联调度器、execution、checkpoint 和审计事实，不能被向导静默覆盖。</p>
     */
    private SyncTaskCreateWizardDraftSaveResponse updateDraft(SyncTaskCreateWizardDraftSaveRequest request,
                                                              SyncActorContext actorContext) {
        SyncTask existingTask = taskMapper.selectById(request.getTaskId());
        if (existingTask == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务草稿不存在，taskId=" + request.getTaskId());
        }
        if (!SyncTaskState.DRAFT.name().equals(existingTask.getCurrentState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有编辑中(DRAFT)任务可以通过创建向导继续保存，taskId=" + existingTask.getId()
                            + ", currentState=" + existingTask.getCurrentState());
        }
        dataScopeSupport.validateOwnedReadable(existingTask.getTenantId(), existingTask.getProjectId(),
                existingTask.getOwnerId(), actorContext, "同步任务草稿");

        SyncTemplate existingTemplate = templateMapper.selectById(existingTask.getTemplateId());
        if (existingTemplate == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务草稿绑定的模板不存在，taskId=" + existingTask.getId()
                            + ", templateId=" + existingTask.getTemplateId());
        }

        DraftBasics basics = resolveBasics(request, actorContext);
        assertScopeConsistent(existingTask, existingTemplate, basics);

        SyncTemplate template = buildTemplate(existingTemplate, request, basics, actorContext);
        connectorFactResolver.resolveConnectorFacts(template, actorContext);
        updateTemplate(template);

        SyncTask task = buildTask(existingTask, template, request, basics, actorContext);
        taskMapper.updateTaskDefinition(
                task.getId(),
                task.getName(),
                task.getDescription(),
                task.getPriority(),
                task.getOwnerId(),
                task.getGroupCode(),
                task.getGroupName(),
                task.getScheduleConfig(),
                task.getScheduleEnabled(),
                task.getNextFireTime(),
                task.getRunMode(),
                task.getTriggerType(),
                task.getCurrentState(),
                task.getApprovalState(),
                task.getAttentionRequired(),
                task.getAttentionReason());

        auditSupport.saveTemplateAudit(template, SyncAuditActionType.CREATE_TEMPLATE, actorContext,
                "updateWizardDraft=true,stepCode=" + normalizeStepCode(request.getStepCode()));
        auditSupport.saveAudit(task.getTenantId(), task.getId(), null, SyncAuditActionType.UPDATE_TASK,
                actorContext, "updateWizardDraft=true,templateId=" + template.getId()
                        + ",stepCode=" + normalizeStepCode(request.getStepCode())
                        + ",state=" + task.getCurrentState());
        return response(task, template, false, request.getStepCode());
    }

    /**
     * 解析草稿保存所需的基础事实。
     *
     * <p>这里故意把“上下文推导”和“用户输入校验”放在一起，方便阅读创建草稿的最小门槛：</p>
     * <p>- 租户和项目来自可信上下文或兼容字段；</p>
     * <p>- workspace 统一收敛为 null；</p>
     * <p>- 同步模式必须是用户可选的五类；</p>
     * <p>- 写入策略必须是 INSERT/UPDATE，旧 APPEND/UPSERT 兼容折叠。</p>
     */
    private DraftBasics resolveBasics(SyncTaskCreateWizardDraftSaveRequest request,
                                      SyncActorContext actorContext) {
        Long tenantId = dataScopeSupport.resolveTenantForCreate(request.getTenantId(), actorContext);
        Long projectId = dataScopeSupport.resolveProjectForCreate(request.getProjectId(), actorContext);
        Long workspaceId = dataScopeSupport.resolveWorkspaceForCreate(request.getWorkspaceId(), actorContext);
        dataScopeSupport.validateProjectWritable(tenantId, projectId, workspaceId, actorContext, "同步任务草稿");

        String taskName = querySupport.trimToNull(
                request.getTaskName() == null ? request.getName() : request.getTaskName());
        if (taskName == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务名称不能为空；请在第一步填写任务名称后再进入对象映射");
        }
        if (request.getSourceDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "必须选择源端数据源");
        }
        if (request.getTargetDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "必须选择目标端数据源");
        }
        if (request.getSourceDatasourceId().equals(request.getTargetDatasourceId())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "源端和目标端数据源不能相同");
        }

        SyncMode syncMode = resolveSyncMode(request.getSyncMode());
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(request.getWriteStrategy(), syncMode);
        return new DraftBasics(tenantId, projectId, workspaceId, taskName, syncMode, writeStrategy);
    }

    /**
     * 构造模板草稿。
     *
     * <p>模板承载“数据移动配置”。在草稿阶段，我们允许对象映射、字段映射、SQL 配置为空或不完整；
     * 因为第二步和第三步正是让用户逐步补齐这些信息。最终能不能执行，仍由预检查判断。</p>
     */
    private SyncTemplate buildTemplate(SyncTemplate existing,
                                       SyncTaskCreateWizardDraftSaveRequest request,
                                       DraftBasics basics,
                                       SyncActorContext actorContext) {
        LocalDateTime now = LocalDateTime.now();
        SyncTemplate template = existing == null ? new SyncTemplate() : existing;
        template.setTenantId(basics.tenantId());
        template.setProjectId(basics.projectId());
        template.setWorkspaceId(basics.workspaceId());
        template.setName(querySupport.truncate(basics.taskName() + " 模板", 160));
        template.setDescription(trim(request.getTaskDescription(), request.getDescription()));
        template.setSourceDatasourceId(request.getSourceDatasourceId());
        template.setTargetDatasourceId(request.getTargetDatasourceId());
        template.setSourceSchemaName(querySupport.trimToNull(request.getSourceSchemaName()));
        template.setSourceObjectName(querySupport.trimToNull(request.getSourceObjectName()));
        template.setTargetSchemaName(querySupport.trimToNull(request.getTargetSchemaName()));
        template.setTargetObjectName(querySupport.trimToNull(request.getTargetObjectName()));
        template.setSourceConnectorType(querySupport.normalizeCode(request.getSourceConnectorType()));
        template.setTargetConnectorType(querySupport.normalizeCode(request.getTargetConnectorType()));
        template.setSyncMode(basics.syncMode().name());
        template.setSyncScopeType(resolveSyncScopeType(request, basics.syncMode()));
        template.setWriteStrategy(basics.writeStrategy().name());
        template.setPrimaryKeyField(null);
        template.setIncrementalField(null);
        template.setFieldMappingConfig(querySupport.trimToNull(request.getFieldMappingConfig()));
        template.setObjectMappingConfig(querySupport.trimToNull(request.getObjectMappingConfig()));
        template.setFilterConfig(querySupport.trimToNull(request.getFilterConfig()));
        template.setCustomSqlConfig(querySupport.trimToNull(request.getCustomSqlConfig()));
        template.setPartitionConfig(querySupport.trimToNull(request.getPartitionConfig()));
        template.setRetryPolicy(querySupport.trimToNull(request.getRetryPolicy()));
        template.setTimeoutPolicy(querySupport.trimToNull(request.getTimeoutPolicy()));
        template.setEnabled(true);
        if (existing == null) {
            template.setCreatedBy(querySupport.actorId(actorContext));
            template.setCreateTime(now);
        }
        template.setUpdatedBy(querySupport.actorId(actorContext));
        template.setUpdateTime(now);
        return template;
    }

    /**
     * 构造任务草稿。
     *
     * <p>DRAFT 任务的状态字段有几个固定原则：</p>
     * <p>1. currentState 固定为 DRAFT，表示编辑中；</p>
     * <p>2. approvalState 固定为 NOT_REQUIRED，创建向导不承载审批事实；</p>
     * <p>3. scheduleEnabled 固定为 false，定期任务在发布前不能被后台调度；</p>
     * <p>4. nextFireTime 固定为空，发布时间再根据 scheduleConfig 计算。</p>
     */
    private SyncTask buildTask(SyncTask existing,
                               SyncTemplate template,
                               SyncTaskCreateWizardDraftSaveRequest request,
                               DraftBasics basics,
                               SyncActorContext actorContext) {
        LocalDateTime now = LocalDateTime.now();
        SyncTask task = existing == null ? new SyncTask() : existing;
        task.setTenantId(basics.tenantId());
        task.setProjectId(basics.projectId());
        task.setWorkspaceId(basics.workspaceId());
        task.setTemplateId(template.getId());

        SyncTaskGroupOperationSupport.TaskGroupAssignment groupAssignment =
                taskGroupOperationSupport.resolveAssignmentForTask(
                        basics.tenantId(),
                        basics.projectId(),
                        basics.workspaceId(),
                        request.getGroupCode(),
                        request.getGroupName(),
                        actorContext);
        task.setGroupCode(groupAssignment.groupCode());
        task.setGroupName(groupAssignment.groupName());
        task.setName(querySupport.truncate(basics.taskName(), 160));
        task.setDescription(trim(request.getTaskDescription(), request.getDescription()));
        task.setCurrentState(SyncTaskState.DRAFT.name());
        task.setApprovalState(SyncApprovalState.NOT_REQUIRED.name());
        task.setPriority(querySupport.defaultText(request.getPriority(), DEFAULT_PRIORITY).toUpperCase(Locale.ROOT));
        task.setScheduleConfig(querySupport.trimToNull(request.getScheduleConfig()));
        task.setScheduleEnabled(false);
        task.setNextFireTime(null);
        task.setRunMode(basics.syncMode().requiresTaskScheduleConfig() ? "SCHEDULED" : "MANUAL");
        task.setTriggerType(basics.syncMode().requiresTaskScheduleConfig()
                ? SyncTriggerType.SCHEDULED.name()
                : SyncTriggerType.MANUAL.name());
        task.setOwnerId(request.getOwnerId() == null ? querySupport.actorId(actorContext) : request.getOwnerId());
        task.setAttentionRequired(false);
        task.setAttentionReason(null);
        if (existing == null) {
            task.setLastFireTime(null);
            task.setScheduleMisfireCount(0);
            task.setScheduleDispatchCount(0L);
            task.setScheduleVersion(0L);
            task.setLastExecutionId(null);
            task.setCreateTime(now);
        }
        task.setUpdateTime(now);
        return task;
    }

    /**
     * 显式更新模板字段。
     *
     * <p>不能直接依赖 MyBatis-Plus 默认 {@code updateById}，因为创建向导允许用户把某些映射字段重新清空；
     * 默认更新策略通常会跳过 null，导致前端以为已经清掉目标表或过滤条件，数据库却仍保留旧值。</p>
     */
    private void updateTemplate(SyncTemplate template) {
        templateMapper.update(null, new LambdaUpdateWrapper<SyncTemplate>()
                .eq(SyncTemplate::getId, template.getId())
                .set(SyncTemplate::getTenantId, template.getTenantId())
                .set(SyncTemplate::getProjectId, template.getProjectId())
                .set(SyncTemplate::getWorkspaceId, template.getWorkspaceId())
                .set(SyncTemplate::getName, template.getName())
                .set(SyncTemplate::getDescription, template.getDescription())
                .set(SyncTemplate::getSourceDatasourceId, template.getSourceDatasourceId())
                .set(SyncTemplate::getTargetDatasourceId, template.getTargetDatasourceId())
                .set(SyncTemplate::getSourceSchemaName, template.getSourceSchemaName())
                .set(SyncTemplate::getSourceObjectName, template.getSourceObjectName())
                .set(SyncTemplate::getTargetSchemaName, template.getTargetSchemaName())
                .set(SyncTemplate::getTargetObjectName, template.getTargetObjectName())
                .set(SyncTemplate::getSourceConnectorType, template.getSourceConnectorType())
                .set(SyncTemplate::getTargetConnectorType, template.getTargetConnectorType())
                .set(SyncTemplate::getSyncMode, template.getSyncMode())
                .set(SyncTemplate::getSyncScopeType, template.getSyncScopeType())
                .set(SyncTemplate::getWriteStrategy, template.getWriteStrategy())
                .set(SyncTemplate::getPrimaryKeyField, template.getPrimaryKeyField())
                .set(SyncTemplate::getIncrementalField, template.getIncrementalField())
                .set(SyncTemplate::getFieldMappingConfig, template.getFieldMappingConfig())
                .set(SyncTemplate::getObjectMappingConfig, template.getObjectMappingConfig())
                .set(SyncTemplate::getFilterConfig, template.getFilterConfig())
                .set(SyncTemplate::getCustomSqlConfig, template.getCustomSqlConfig())
                .set(SyncTemplate::getPartitionConfig, template.getPartitionConfig())
                .set(SyncTemplate::getRetryPolicy, template.getRetryPolicy())
                .set(SyncTemplate::getTimeoutPolicy, template.getTimeoutPolicy())
                .set(SyncTemplate::getEnabled, template.getEnabled())
                .set(SyncTemplate::getUpdatedBy, template.getUpdatedBy())
                .set(SyncTemplate::getUpdateTime, template.getUpdateTime()));
    }

    private void assertScopeConsistent(SyncTask task, SyncTemplate template, DraftBasics basics) {
        if (!basics.tenantId().equals(task.getTenantId()) || !basics.tenantId().equals(template.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "草稿租户不能在编辑过程中变更，taskTenantId=" + task.getTenantId()
                            + ", templateTenantId=" + template.getTenantId()
                            + ", requestedTenantId=" + basics.tenantId());
        }
        if (task.getProjectId() != null && !task.getProjectId().equals(basics.projectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "草稿项目不能在编辑过程中变更，taskProjectId=" + task.getProjectId()
                            + ", requestedProjectId=" + basics.projectId());
        }
    }

    private SyncMode resolveSyncMode(String syncMode) {
        String normalized = querySupport.normalizeCode(syncMode);
        if (normalized == null || normalized.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "必须选择同步模式");
        }
        try {
            SyncMode mode = SyncMode.valueOf(normalized);
            if (!mode.isUserSelectableTransferMode()) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "新建同步任务只允许选择 FULL、SCHEDULED_BATCH、SCHEDULED_FULL、CUSTOM_SQL_QUERY、CDC_STREAMING");
            }
            return mode;
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "不支持的同步模式: " + syncMode);
        }
    }

    private SyncWriteStrategy resolveWriteStrategy(String writeStrategy, SyncMode syncMode) {
        try {
            SyncWriteStrategy strategy = SyncWriteStrategy.fromValueForMode(
                    writeStrategy, syncMode == null ? null : syncMode.name());
            if (syncMode == SyncMode.CDC_STREAMING && strategy.insertLike()) {
                /*
                 * 创建向导草稿在第二步前就会保存任务；如果这里允许 CDC + INSERT 落库，
                 * 后续预检查虽然能阻断，但任务列表里会保留一个先天不合法的草稿。
                 * 因此草稿入口直接把实时模式收敛为“省略 writeStrategy 或显式 UPDATE/UPSERT”，
                 * 前端隐藏写入策略时只需要不提交该字段即可。
                 */
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "实时同步模式不需要选择写入策略，后端默认 UPDATE/merge；请不要提交 INSERT 或 APPEND");
            }
            if (strategy == SyncWriteStrategy.APPEND) {
                return SyncWriteStrategy.INSERT;
            }
            if (strategy == SyncWriteStrategy.UPSERT) {
                return SyncWriteStrategy.UPDATE;
            }
            if (!strategy.isUserFacingStrategy()) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "创建向导写入策略只允许 INSERT 或 UPDATE");
            }
            return strategy;
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, exception.getMessage());
        }
    }

    private String resolveSyncScopeType(SyncTaskCreateWizardDraftSaveRequest request, SyncMode mode) {
        String explicit = querySupport.normalizeCode(request.getSyncScopeType());
        if (explicit != null) {
            return explicit;
        }
        if (mode == SyncMode.CUSTOM_SQL_QUERY) {
            return "CUSTOM_SQL_QUERY";
        }
        return querySupport.trimToNull(request.getObjectMappingConfig()) == null ? "SINGLE_OBJECT" : "OBJECT_LIST";
    }

    private SyncTaskCreateWizardDraftSaveResponse response(SyncTask task,
                                                           SyncTemplate template,
                                                           boolean created,
                                                           String stepCode) {
        SyncTask refreshedTask = taskMapper.selectById(task.getId());
        SyncTemplate refreshedTemplate = templateMapper.selectById(template.getId());
        SyncTaskCreateWizardDraftSaveResponse response = new SyncTaskCreateWizardDraftSaveResponse();
        response.setTaskId(refreshedTask.getId());
        response.setTemplateId(refreshedTemplate.getId());
        response.setCreated(created);
        response.setCurrentState(refreshedTask.getCurrentState());
        response.setScheduleEnabled(refreshedTask.getScheduleEnabled());
        response.setNextFireTime(refreshedTask.getNextFireTime());
        response.setGroupCode(refreshedTask.getGroupCode());
        response.setGroupName(refreshedTask.getGroupName());
        response.setNextActions(nextActions(stepCode));
        response.setTask(refreshedTask);
        response.setTemplate(refreshedTemplate);
        return response;
    }

    private List<String> nextActions(String stepCode) {
        return switch (normalizeStepCode(stepCode)) {
            case "SOURCE_TARGET" -> List.of(
                    "草稿任务已保存为 DRAFT，任务列表可看到编辑中记录",
                    "进入对象映射步骤后自动拉取源端和目标端元数据",
                    "目标 schema/table 可以由用户自定义填写，是否存在由预检查判断");
            case "OBJECT_MAPPING" -> List.of(
                    "按每一组源端对象 -> 目标对象单独配置字段映射",
                    "字段映射以源端和目标端真实元数据为准，同名字段只做默认预填",
                    "where 条件可以按对象单独编辑，也可以批量套用");
            case "FIELD_SQL" -> List.of(
                    "进入预检查步骤后自动运行模板预检查",
                    "预检查会判断对象存在性、字段兼容性、目标约束、SQL 安全和调度配置");
            case "PRECHECK" -> List.of("预检查通过后可发布任务；草稿阶段不会自动执行或启用调度");
            default -> List.of("草稿保存成功，请继续完善创建向导配置");
        };
    }

    private String normalizeStepCode(String stepCode) {
        return stepCode == null || stepCode.isBlank()
                ? "SOURCE_TARGET"
                : stepCode.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String preferred, String fallback) {
        String text = querySupport.trimToNull(preferred);
        return text == null ? querySupport.trimToNull(fallback) : text;
    }

    private record DraftBasics(Long tenantId,
                               Long projectId,
                               Long workspaceId,
                               String taskName,
                               SyncMode syncMode,
                               SyncWriteStrategy writeStrategy) {
    }
}
