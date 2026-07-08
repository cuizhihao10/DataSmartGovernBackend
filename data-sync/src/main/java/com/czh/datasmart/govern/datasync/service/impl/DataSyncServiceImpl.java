/**
 * @Author : Cui
 * @Date: 2026/05/07 21:31
 * @Description DataSmart Govern Backend - DataSyncServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionLogQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCloneRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskExportFile;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupCreateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupTreeNode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportOptions;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskPublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplatePlanningPreviewResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionLog;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskGroup;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionLogMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataScopeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataVisibility;
import com.czh.datasmart.govern.datasync.service.support.SyncDirtyRecordReplaySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncObjectExecutionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineJobPlanSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskBatchOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskCreateWizardDraftSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionExchangeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskLifecycleOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskGroupOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskManagementOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskMetadataConfigurationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskRecoveryOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskScheduleConfigSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskStateMachineSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateExecutionPrecheckSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplatePlanningPreviewSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateValidationSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncApprovalState;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 数据同步服务实现。
 *
 * <p>当前实现刻意保持“定义面优先”：
 * 1. 模板负责保存可复用配置；
 * 2. 任务负责保存可运营状态；
 * 3. runTask 只把任务推进到 QUEUED，真正执行器、checkpoint 和吞吐控制后续独立实现。
 *
 * <p>这样做能避免一开始就把连接器读写、任务状态、审计、checkpoint 全部耦合在一个大 Impl 里。
 */
@Service
@RequiredArgsConstructor
public class DataSyncServiceImpl implements DataSyncService {

    private static final int APPROVAL_FACT_ID_MAX_LENGTH = 160;

    private final SyncTemplateMapper templateMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncExecutionLogMapper executionLogMapper;
    private final SyncCheckpointMapper checkpointMapper;
    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncAuditRecordMapper auditRecordMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncQuerySupport querySupport;
    private final SyncTemplateValidationSupport templateValidationSupport;
    private final SyncTaskStateMachineSupport stateMachineSupport;
    private final SyncAuditSupport auditSupport;
    private final SyncExecutionLifecycleSupport executionLifecycleSupport;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncTaskBatchOperationSupport taskBatchOperationSupport;
    private final SyncTaskDefinitionOperationSupport taskDefinitionOperationSupport;
    private final SyncTaskDefinitionExchangeSupport taskDefinitionExchangeSupport;
    private final SyncTaskLifecycleOperationSupport taskLifecycleOperationSupport;
    private final SyncTaskGroupOperationSupport taskGroupOperationSupport;
    private final SyncTaskManagementOperationSupport taskManagementOperationSupport;
    private final SyncTaskMetadataConfigurationSupport taskMetadataConfigurationSupport;
    private final SyncTaskRecoveryOperationSupport taskRecoveryOperationSupport;
    private final SyncTemplateCreationSupport templateCreationSupport;
    private final SyncTemplatePlanningPreviewSupport templatePlanningPreviewSupport;
    private final SyncTemplateExecutionPrecheckSupport templateExecutionPrecheckSupport;
    private final SyncOfflineJobPlanSupport offlineJobPlanSupport;
    private final SyncObjectExecutionOperationSupport objectExecutionOperationSupport;
    private final SyncDirtyRecordReplaySupport dirtyRecordReplaySupport;
    private final SyncTaskScheduleConfigSupport scheduleConfigSupport;
    private final SyncTaskCreateWizardDraftSupport createWizardDraftSupport;

    @Override
    @Transactional
    public SyncTemplate createTemplate(CreateSyncTemplateRequest request, SyncActorContext actorContext) {
        return templateCreationSupport.createTemplate(request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncTemplate> pageTemplates(SyncTemplateQueryCriteria criteria,
                                                            SyncActorContext actorContext) {
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                criteria.tenantId(), criteria.projectId(), criteria.workspaceId(), actorContext);
        LambdaQueryWrapper<SyncTemplate> wrapper = new LambdaQueryWrapper<SyncTemplate>()
                .orderByDesc(SyncTemplate::getUpdateTime)
                .orderByDesc(SyncTemplate::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncTemplate::getTenantId, visibility.tenantId());
        }
        querySupport.eqIfPresent(wrapper, SyncTemplate::getProjectId, visibility.projectId());
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncTemplate::getProjectId, visibility);
        querySupport.eqIfPresent(wrapper, SyncTemplate::getWorkspaceId, visibility.workspaceId());
        if (visibility.selfOnly()) {
            wrapper.eq(SyncTemplate::getCreatedBy, querySupport.actorId(actorContext));
        }
        querySupport.eqIfPresent(wrapper, SyncTemplate::getSourceDatasourceId, criteria.sourceDatasourceId());
        querySupport.eqIfPresent(wrapper, SyncTemplate::getTargetDatasourceId, criteria.targetDatasourceId());
        querySupport.eqIfPresent(wrapper, SyncTemplate::getSyncMode, querySupport.normalizeCode(criteria.syncMode()));
        if (criteria.enabled() != null) {
            wrapper.eq(SyncTemplate::getEnabled, criteria.enabled());
        }
        Page<SyncTemplate> page = templateMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public SyncTemplate getTemplate(Long id, SyncActorContext actorContext) {
        SyncTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步模板不存在: " + id);
        }
        dataScopeSupport.validateOwnedReadable(template.getTenantId(), template.getProjectId(),
                template.getCreatedBy(), actorContext, "同步模板");
        return template;
    }

    @Override
    public SyncTaskOperationResult validateTemplate(Long id, SyncActorContext actorContext) {
        SyncTemplate template = getTemplate(id, actorContext);
        templateValidationSupport.validateTemplate(template);
        auditSupport.saveTemplateAudit(template, SyncAuditActionType.VALIDATE_TEMPLATE,
                actorContext, "templateId=" + template.getId());
        return new SyncTaskOperationResult(null, "VALIDATED", "同步模板校验通过，后续可创建同步任务或执行预览");
    }

    @Override
    public SyncTemplatePlanningPreviewResponse previewTemplate(Long id, SyncActorContext actorContext) {
        SyncTemplate template = getTemplate(id, actorContext);
        return templatePlanningPreviewSupport.preview(template);
    }

    @Override
    public SyncTemplateExecutionPrecheckResponse precheckTemplate(Long id, SyncActorContext actorContext) {
        SyncTemplate template = getTemplate(id, actorContext);
        return templateExecutionPrecheckSupport.precheck(template, actorContext);
    }

    /**
     * 生成同步模板的离线作业计划。
     *
     * <p>主 Service 只负责复用 {@link #getTemplate(Long, SyncActorContext)} 做租户、项目、SELF 数据范围校验，
     * 然后把 Reader/Writer、调度语义、checkpoint、审批和 fail-closed 细节委托给
     * {@link SyncOfflineJobPlanSupport}。这样可以避免 DataSyncServiceImpl 再次堆积大段规则判断。</p>
     */
    @Override
    public SyncOfflineJobPlanResponse buildOfflineJobPlan(Long id, SyncActorContext actorContext) {
        SyncTemplate template = getTemplate(id, actorContext);
        return offlineJobPlanSupport.buildPlan(template);
    }

    @Override
    @Transactional
    public SyncTask createTask(CreateSyncTaskRequest request, SyncActorContext actorContext) {
        SyncTemplate template = getTemplate(request.getTemplateId(), actorContext);
        templateValidationSupport.validateTemplate(template);
        SyncTemplateExecutionPrecheckResponse precheck = templateExecutionPrecheckSupport.precheck(template, actorContext);
        if (!precheck.canCreateTaskDraft()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务创建前预检查未通过，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes()
                            + "，recommendedActions=" + precheck.recommendedActions());
        }
        Long tenantId = dataScopeSupport.resolveTenantForCreate(request.getTenantId(), actorContext);
        if (!tenantId.equals(template.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步任务租户必须与模板租户一致，templateTenantId=" + template.getTenantId() + ", taskTenantId=" + tenantId);
        }
        /*
         * 新建任务向导不再承载“审批确认”。
         *
         * 用户创建同步任务本质是配置数据搬运工具：选择源端、目标端、对象映射、字段映射、过滤条件并运行预检查。
         * 高风险审批、回放、补数、导出等治理动作应放在任务详情、执行恢复或运营控制台的专用流程中，不能让普通创建页面出现
         * approvalConfirmed / approvalFactId 这类让用户难以理解的字段。
         *
         * 因此创建阶段统一写入 NOT_REQUIRED；如果后续预检查发现需要高风险确认，应由发布、执行或恢复动作再进入审批策略。
         */
        String approvalState = SyncApprovalState.NOT_REQUIRED.name();
        boolean scheduledTask = isScheduledTask(template, request);
        LocalDateTime firstFireTime = scheduledTask
                ? scheduleConfigSupport.initialNextFireTime(request.getScheduleConfig(), LocalDateTime.now())
                : null;
        /*
         * 运行模式不再由用户手填。
         *
         * - SCHEDULED_FULL / SCHEDULED_BATCH 且 scheduleConfig 合法时，任务主状态进入 SCHEDULED，等待调度器到点创建 execution；
         * - FULL / CUSTOM_SQL_QUERY / CDC_STREAMING 等非定时模式在创建后保持 CONFIGURED，由“立即执行/手工调度/发布”动作触发。
         */
        String runMode = scheduledTask ? "SCHEDULED" : "MANUAL";

        SyncTask task = new SyncTask();
        task.setTenantId(tenantId);
        task.setProjectId(resolveScopeValue("projectId", request.getProjectId(), template.getProjectId()));
        /*
         * 工作空间已经从用户侧产品层级中退场，新建任务页面不会再展示或提交 workspaceId。
         *
         * 这里保留“继承历史模板 workspaceId”的兼容行为，是为了避免老模板、老执行记录、审计记录在迁移期突然出现
         * 任务和模板归属不一致；但我们不再读取 request.getWorkspaceId()，这样旧前端、脚本或网关 Header 即使仍携带
         * workspaceId，也不会把新任务写入用户不可见的工作空间。新建模板的 workspaceId 已经由 SyncDataScopeSupport
         * 收敛为 null，因此新链路天然就是项目级归属。
         */
        task.setWorkspaceId(template.getWorkspaceId());
        task.setTemplateId(template.getId());
        SyncTaskGroupOperationSupport.TaskGroupAssignment groupAssignment =
                taskGroupOperationSupport.resolveAssignmentForTask(
                        tenantId,
                        task.getProjectId(),
                        task.getWorkspaceId(),
                        request.getGroupCode(),
                        request.getGroupName(),
                        actorContext);
        task.setGroupCode(groupAssignment.groupCode());
        task.setGroupName(groupAssignment.groupName());
        task.setName(querySupport.defaultText(request.getName(), template.getName()));
        task.setDescription(querySupport.defaultText(request.getDescription(), template.getDescription()));
        /*
         * 审批状态与任务主状态保持解耦：
         * - approvalState 只回答“这类高风险同步是否已经批准”；
         * - currentState 只回答“任务生命周期当前停在哪里”。
         *
         * 因此需要审批但尚未确认的任务进入 PENDING_APPROVAL，避免 runTask 绕过审批；
         * 已确认或无需审批的任务保持 CONFIGURED，后续才能进入 QUEUED。
         */
        task.setCurrentState(resolveInitialTaskState(approvalState, scheduledTask));
        task.setApprovalState(approvalState);
        task.setPriority(querySupport.defaultText(request.getPriority(), "MEDIUM").toUpperCase(java.util.Locale.ROOT));
        task.setScheduleConfig(querySupport.trimToNull(request.getScheduleConfig()));
        task.setScheduleEnabled(scheduledTask);
        task.setNextFireTime(scheduledTask ? firstFireTime : null);
        task.setLastFireTime(null);
        task.setScheduleMisfireCount(0);
        task.setScheduleDispatchCount(0L);
        task.setScheduleVersion(0L);
        task.setRunMode(runMode);
        task.setTriggerType(scheduledTask ? SyncTriggerType.SCHEDULED.name() : SyncTriggerType.MANUAL.name());
        task.setOwnerId(request.getOwnerId() == null ? querySupport.actorId(actorContext) : request.getOwnerId());
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), null, SyncAuditActionType.CREATE_TASK,
                actorContext, buildCreateTaskAuditPayload(task, precheck, request));
        return task;
    }

    /**
     * 保存创建向导草稿。
     *
     * <p>主 Service 只负责声明事务边界并委托给 {@link SyncTaskCreateWizardDraftSupport}。
     * 这样“创建/更新 DRAFT 模板与任务”的规则不会继续塞进已经很重的 ServiceImpl，也方便后续单独补草稿恢复、草稿发布、
     * 草稿超期清理等能力。</p>
     */
    @Override
    @Transactional
    public SyncTaskCreateWizardDraftSaveResponse saveCreateWizardDraft(SyncTaskCreateWizardDraftSaveRequest request,
                                                                       SyncActorContext actorContext) {
        return createWizardDraftSupport.saveDraft(request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncTask> pageTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                criteria.tenantId(), criteria.projectId(), criteria.workspaceId(), actorContext);
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .orderByDesc(SyncTask::getUpdateTime)
                .orderByDesc(SyncTask::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncTask::getTenantId, visibility.tenantId());
        }
        querySupport.eqIfPresent(wrapper, SyncTask::getProjectId, visibility.projectId());
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncTask::getProjectId, visibility);
        querySupport.eqIfPresent(wrapper, SyncTask::getWorkspaceId, visibility.workspaceId());
        if (visibility.selfOnly()) {
            wrapper.eq(SyncTask::getOwnerId, querySupport.actorId(actorContext));
        }
        querySupport.eqIfPresent(wrapper, SyncTask::getTemplateId, criteria.templateId());
        querySupport.eqIfPresent(wrapper, SyncTask::getOwnerId, criteria.ownerId());
        applyTaskGroupFilter(wrapper, criteria.groupCode());
        String requestedState = querySupport.normalizeCode(criteria.currentState());
        if (requestedState == null) {
            /*
             * 普通任务列表默认不展示回收站和已彻底删除任务。
             * 回收站本身仍可通过 currentState=RECYCLED 显式查询，便于前端单独做“回收站”视图；
             * DELETED 则只保留给审计、历史执行和后续数据保留策略，不应出现在日常运营列表中。
             */
            wrapper.notIn(SyncTask::getCurrentState, SyncTaskState.RECYCLED.name(), SyncTaskState.DELETED.name());
        } else {
            querySupport.eqIfPresent(wrapper, SyncTask::getCurrentState, requestedState);
        }
        querySupport.eqIfPresent(wrapper, SyncTask::getApprovalState, querySupport.normalizeCode(criteria.approvalState()));
        querySupport.eqIfPresent(wrapper, SyncTask::getTriggerType, querySupport.normalizeCode(criteria.triggerType()));
        Page<SyncTask> page = taskMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public SyncTask getTask(Long id, SyncActorContext actorContext) {
        SyncTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + id);
        }
        if (SyncTaskState.DELETED.name().equals(task.getCurrentState())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务已彻底删除: " + id);
        }
        dataScopeSupport.validateOwnedReadable(task.getTenantId(), task.getProjectId(),
                task.getOwnerId(), actorContext, "同步任务");
        return task;
    }

    /**
     * 查询回收站任务。
     *
     * <p>普通 pageTasks 在 currentState 为空时会主动排除 RECYCLED/DELETED。
     * 这里构造一个强制 currentState=RECYCLED 的查询条件，让前端和 Agent 拥有清晰的回收站入口，
     * 不需要知道“普通列表传 currentState=RECYCLED 也能查到”这种内部兼容细节。</p>
     */
    @Override
    public PlatformPageResponse<SyncTask> pageRecycledTasks(SyncTaskQueryCriteria criteria,
                                                            SyncActorContext actorContext) {
        SyncTaskQueryCriteria safeCriteria = criteria == null
                ? new SyncTaskQueryCriteria(null, null, null, null, null, null,
                SyncTaskState.RECYCLED.name(), null, null, null, null)
                : new SyncTaskQueryCriteria(
                criteria.tenantId(),
                criteria.projectId(),
                criteria.workspaceId(),
                criteria.templateId(),
                criteria.ownerId(),
                criteria.groupCode(),
                SyncTaskState.RECYCLED.name(),
                criteria.approvalState(),
                criteria.triggerType(),
                criteria.current(),
                criteria.size());
        return pageTasks(safeCriteria, actorContext);
    }

    /**
     * 编辑任务定义。
     *
     * <p>主 Service 只负责复用 getTask(...) 与 getTemplateForTask(...) 完成入口校验。
     * 具体“哪些状态可编辑、调度字段如何退回草稿、审计如何低敏记录”交给
     * {@link SyncTaskDefinitionOperationSupport}，避免 Impl 继续堆积任务定义细节。</p>
     */
    @Override
    @Transactional
    public SyncTask updateTask(Long id, SyncTaskUpdateRequest request, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        SyncTemplate template = getTemplateForTask(task);
        return taskDefinitionOperationSupport.updateTaskDefinition(task, template, request, actorContext);
    }

    /**
     * 发布任务定义。
     *
     * <p>发布不是运行任务，而是把任务重新推进到 CONFIGURED/SCHEDULED/PENDING_APPROVAL。
     * 真正创建 execution 仍然由 run/manual-dispatch/scheduler 完成，这样任务定义状态和执行历史能保持清晰分离。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult publishTask(Long id,
                                               SyncTaskPublishRequest request,
                                               SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        SyncTemplate template = getTemplateForTask(task);
        return taskDefinitionOperationSupport.publishTaskDefinition(task, template, request, actorContext);
    }

    /**
     * 导出任务定义文件。
     *
     * <p>Service 继续保持“入口编排”职责：Controller 负责 HTTP 文件响应，ExchangeSupport 负责文件编码和数据范围过滤。
     * 这样后续如果 Agent 工具也要导出任务定义，可以直接调用同一个 support，而不是复制 Controller 逻辑。</p>
     */
    @Override
    public SyncTaskExportFile exportTasks(SyncTaskQueryCriteria criteria,
                                          String format,
                                          SyncActorContext actorContext) {
        return taskDefinitionExchangeSupport.exportTasks(criteria, format, actorContext);
    }

    /**
     * 按选中的任务 ID 批量导出任务定义。
     *
     * <p>主 Service 仍然只做委托，具体的 ID 去重、可见性校验、低敏文件编码和审计写入都在
     * {@link SyncTaskDefinitionExchangeSupport} 内部完成，避免 Controller 或 ServiceImpl 直接拼导出规则。</p>
     */
    @Override
    public SyncTaskExportFile exportTasksByIds(List<Long> taskIds,
                                               String format,
                                               SyncActorContext actorContext) {
        return taskDefinitionExchangeSupport.exportTasksByIds(taskIds, format, actorContext);
    }

    /**
     * 导入任务定义文件。
     *
     * <p>导入可能批量创建任务，或在 runImmediately=true 时创建 execution，因此必须处于事务中。
     * 如果写入阶段出现异常，已插入的任务和执行记录会回滚，避免半批导入成功。</p>
     */
    @Override
    @Transactional
    public SyncTaskImportResult importTasks(byte[] content,
                                            SyncTaskImportOptions options,
                                            SyncActorContext actorContext) {
        return taskDefinitionExchangeSupport.importTasks(content, options, actorContext);
    }

    /**
     * 批量手工调度同步任务。
     *
     * <p>批量动作不在这里直接循环调用单任务方法，而是交给 {@link SyncTaskBatchOperationSupport} 使用逐条事务执行。
     * 这样某一条失败时只回滚该任务，已成功的任务仍能保留并返回清晰明细。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchManualDispatchTasks(SyncTaskBatchOperationRequest request,
                                                                 SyncActorContext actorContext) {
        return taskBatchOperationSupport.manualDispatchTasks(request, actorContext);
    }

    /**
     * 查询任务分组汇总。
     *
     * <p>主 Service 不直接写聚合 SQL，而是委托给 {@link SyncTaskGroupOperationSupport}：
     * 分组能力后续会继续扩展到批量移组、组级导出、组级手工调度和 Agent 查询工具，把规则集中在 support
     * 里更容易保持编码规范、SELF 数据范围和审计口径一致。</p>
     */
    @Override
    public List<SyncTaskGroupSummary> listTaskGroups(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
        return taskGroupOperationSupport.listTaskGroups(criteria, actorContext);
    }

    /**
     * 查询可渲染为树形菜单的同步任务分组。
     *
     * <p>该方法面向前端“左侧导航栏 + 内容页中间分组菜单栏”的双菜单场景：
     * Service 层只负责暴露稳定契约，真正的默认分组兜底、历史分组合并、父子关系构建和任务数量聚合都由
     * {@link SyncTaskGroupOperationSupport} 统一处理，避免列表页、创建页、导入页各自解释一套分组规则。</p>
     */
    @Override
    public List<SyncTaskGroupTreeNode> listTaskGroupTree(SyncTaskQueryCriteria criteria,
                                                         SyncActorContext actorContext) {
        return taskGroupOperationSupport.listTaskGroupTree(criteria, actorContext);
    }

    /**
     * 创建同步任务分组资源。
     *
     * <p>新增分组会立即参与任务创建、任务编辑、克隆和导入校验；这意味着后端不能只把它当作 UI 菜单项，
     * 而要把它作为可审计、可删除、可迁移任务归属的业务资源落库。</p>
     */
    @Override
    @Transactional
    public SyncTaskGroup createTaskGroup(SyncTaskGroupCreateRequest request,
                                         SyncActorContext actorContext) {
        return taskGroupOperationSupport.createTaskGroup(request, actorContext);
    }

    /**
     * 删除同步任务分组，并把受影响任务迁回默认分组。
     *
     * <p>删除分组属于高影响控制面动作：它不会删除任务，也不会停止执行中的任务，只改变运营归属。
     * 因此这里保留事务边界，保证“归档分组”和“任务迁回 DEFAULT”要么同时成功，要么同时回滚。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult deleteTaskGroup(String groupCode,
                                                   Long tenantId,
                                                   Long projectId,
                                                   Long workspaceId,
                                                   String reason,
                                                   SyncActorContext actorContext) {
        return taskGroupOperationSupport.deleteTaskGroup(groupCode, tenantId, projectId, workspaceId, reason, actorContext);
    }

    /**
     * 自动发现创建同步任务时可选的 schema/table/field 元数据。
     *
     * <p>data-sync 不直接连接源库或目标库，而是通过 datasource-management 的低敏元数据接口读取结构信息；
     * 这样数据源凭据、连接池和连接诊断仍然留在 datasource-management 模块内，data-sync 只负责同步配置语义。</p>
     */
    @Override
    public SyncTaskMetadataDiscoveryResponse discoverTaskMetadata(SyncTaskMetadataDiscoveryRequest request,
                                                                  SyncActorContext actorContext) {
        return taskMetadataConfigurationSupport.discoverTaskMetadata(request, actorContext);
    }

    /**
     * 根据源表和目标表生成字段映射建议。
     *
     * <p>字段映射建议只给出“默认是否同步”的保守判断，前端和 Agent 仍然需要允许用户最终确认。
     * 这样既能减少手工配置成本，又不会因为自动映射过于激进而把不兼容字段直接写入生产任务。</p>
     */
    @Override
    public SyncTaskFieldMappingSuggestionResponse suggestFieldMappings(SyncTaskFieldMappingSuggestionRequest request,
                                                                       SyncActorContext actorContext) {
        return taskMetadataConfigurationSupport.suggestFieldMappings(request, actorContext);
    }

    /**
     * 调整任务所属分组。
     *
     * <p>入口仍然先调用 getTask(...)，保证租户、项目和 SELF 范围校验一致；
     * 真正的分组编码规范化、持久化和审计由分组 support 负责。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult updateTaskGroup(Long id,
                                                   SyncTaskGroupUpdateRequest request,
                                                   SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskGroupOperationSupport.updateTaskGroup(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult runTask(Long id, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        SyncTemplate template = getTemplateForTask(task);
        SyncTemplateExecutionPrecheckResponse precheck = templateExecutionPrecheckSupport.precheck(template);
        if (!canRunAfterPrecheck(precheck, task)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务执行前预检查未通过，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes()
                            + "，recommendedActions=" + precheck.recommendedActions());
        }
        if (SyncTaskState.PENDING_APPROVAL.name().equals(task.getCurrentState())
                && SyncApprovalState.APPROVED.name().equals(task.getApprovalState())) {
            /*
             * 外部正式审批流接入后，审批中心可能只把 approvalState 写成 APPROVED。
             * runTask 在这里把“已批准但主状态仍停留在 PENDING_APPROVAL”的任务安全推进回 CONFIGURED，
             * 再交给统一状态机判断，避免审批状态和生命周期状态短暂不一致导致合法运行被误挡。
             */
            task.setCurrentState(SyncTaskState.CONFIGURED.name());
        }
        stateMachineSupport.assertCanQueue(task.getCurrentState());
        SyncExecution execution = executionCreationSupport.createQueuedExecution(task, actorContext);
        task.setCurrentState(SyncTaskState.QUEUED.name());
        task.setTriggerType(SyncTriggerType.MANUAL.name());
        task.setLastExecutionId(execution.getId());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "taskId=" + task.getId() + ",executionId=" + execution.getId());
        return new SyncTaskOperationResult(task.getId(), task.getCurrentState(),
                "同步任务已进入待执行队列，执行记录 ID=" + execution.getId() + "；后续将接入执行器、checkpoint 和任务中心协议");
    }

    /**
     * 手工调度同步任务。
     *
     * <p>这里继续复用 getTask(...) 作为入口级数据范围校验，然后把“预检、状态机、execution 创建、审计”委托给
     * {@link SyncTaskManagementOperationSupport}。这样 runTask 的历史兼容语义不被破坏，同时新路由可以拥有更准确的
     * MANUAL_DISPATCH_TASK 审计动作。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult manualDispatchTask(Long id, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskManagementOperationSupport.manualDispatchTask(task, actorContext);
    }

    /**
     * 批量下线同步任务。
     *
     * <p>批量下线是批量删除前的正式治理入口：它会关闭自动调度，避免周期任务继续被 scheduler 扫描。
     * 逐条准入规则仍由单任务下线动作维护，例如活跃执行中的任务不能直接下线。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchOfflineTasks(SyncTaskBatchOperationRequest request,
                                                          SyncActorContext actorContext) {
        return taskBatchOperationSupport.offlineTasks(request, actorContext);
    }

    /**
     * 判断新任务是否应进入“自动调度”生命周期。
     *
     * <p>当前收敛阶段只把两类离线场景纳入自动调度：</p>
     * <p>1. {@code SCHEDULED_FULL + scheduleConfig}：定期全量，例如每天凌晨全表重刷；</p>
     * <p>2. {@code SCHEDULED_BATCH + scheduleConfig}：定期批量，例如每 15 分钟同步一个有界业务窗口。</p>
     *
     * <p>为什么不把所有 syncMode 都直接允许调度：</p>
     * <p>自定义 SQL、全库迁移、回放、补数、离线导入导出等模式虽然未来也可能需要调度，
     * 但它们的审批、容量评估、对象发现和目标覆盖风险更高。当前用户要求优先补齐定期全量和定期批量，
     * 因此先把自动调度范围收敛在这两类，避免为了“看起来泛化”把高风险动作变成后台自动执行。</p>
     */
    private boolean isScheduledTask(SyncTemplate template, CreateSyncTaskRequest request) {
        String syncMode = normalizeCode(template.getSyncMode());
        boolean hasScheduleConfig = scheduleConfigSupport.hasScheduleConfig(request.getScheduleConfig());
        boolean scheduledFull = SyncMode.SCHEDULED_FULL.name().equals(syncMode);
        boolean scheduledBatch = SyncMode.SCHEDULED_BATCH.name().equals(syncMode);

        /*
         * 是否定时调度已经由第一步选择的传输模式决定，不再允许前端通过 runMode 手工覆盖。
         * 这样可以避免“普通全量 + runMode=SCHEDULED”和“定期全量”形成两套表达，也能让表单必填校验更清晰：
         * 只有 SCHEDULED_FULL / SCHEDULED_BATCH 才必须填写 scheduleConfig。
         */
        if (scheduledFull || scheduledBatch) {
            if (!hasScheduleConfig) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "定时全量或定时批量任务必须提供 scheduleConfig");
            }
        }
        if (hasScheduleConfig && !SyncMode.SCHEDULED_FULL.name().equals(syncMode)
                && !SyncMode.SCHEDULED_BATCH.name().equals(syncMode)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "当前自动调度仅支持 SCHEDULED_FULL 定期全量和 SCHEDULED_BATCH 定期批量，syncMode=" + syncMode);
        }
        if (hasScheduleConfig) {
            /*
             * 主动解析一次，让非法 cron、非法时区、intervalSeconds<=0 等问题在创建任务阶段暴露，
             * 而不是等到后台调度器反复跳过历史任务。
             */
            scheduleConfigSupport.parseRequired(request.getScheduleConfig());
        }
        return scheduledBatch || scheduledFull;
    }

    /**
     * 解析新任务的初始生命周期状态。
     *
     * <p>审批状态和调度状态必须解耦：需要审批的任务即使提供了 scheduleConfig，也不能立即进入 SCHEDULED，
     * 否则后台调度器可能绕过人工审批自动生成 execution。只有审批已通过或无需审批时，定时任务才会进入
     * SCHEDULED 并写入 nextFireTime。</p>
     */
    private String resolveInitialTaskState(String approvalState, boolean scheduledTask) {
        if (SyncApprovalState.PENDING.name().equals(approvalState)) {
            return SyncTaskState.PENDING_APPROVAL.name();
        }
        return scheduledTask ? SyncTaskState.SCHEDULED.name() : SyncTaskState.CONFIGURED.name();
    }

    /**
     * 根据预检查结果和请求中的低敏审批事实决定新任务审批状态。
     *
     * <p>这里没有把 approvalFactId 存进任务表，是有意的阶段性收敛：
     * 1. data-sync 当前只需要知道任务是否允许入队；
     * 2. 正式审批事实应由 permission-admin/审批中心持久化，data-sync 保存引用或审计摘要即可；
     * 3. 审计摘要必须低敏，不能包含 SQL、表名、字段映射、连接串、token、密码或业务样本。</p>
     */
    private String resolveApprovalStateForNewTask(SyncTemplateExecutionPrecheckResponse precheck,
                                                  CreateSyncTaskRequest request,
                                                  SyncActorContext actorContext) {
        if (!precheck.approvalRequired()) {
            if (Boolean.TRUE.equals(request.getApprovalConfirmed()) || hasText(request.getApprovalFactId())) {
                /*
                 * 非高风险任务不需要提交审批事实。这里不直接失败，是为了兼容本地 E2E 或上游审批流的统一表单；
                 * 但最终状态仍以 NOT_REQUIRED 为准，避免把普通任务误标成“已审批”。
                 */
                assertTrustedApprovalSubmitter(actorContext, "SUBMIT_UNUSED_APPROVAL_FACT");
                validateApprovalFactId(request.getApprovalFactId(), false);
            }
            return SyncApprovalState.NOT_REQUIRED.name();
        }
        if (Boolean.TRUE.equals(request.getApprovalConfirmed())) {
            assertTrustedApprovalSubmitter(actorContext, "CONFIRM_SYNC_TASK_APPROVAL");
            validateApprovalFactId(request.getApprovalFactId(), true);
            return SyncApprovalState.APPROVED.name();
        }
        if (hasText(request.getApprovalFactId())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "已提供审批事实 ID，但 approvalConfirmed 未显式为 true；高风险同步任务不能只凭事实引用入队");
        }
        return SyncApprovalState.PENDING.name();
    }

    /**
     * 判断任务是否允许越过 REQUIRES_APPROVAL 预检查状态进入队列。
     *
     * <p>预检查返回 REQUIRES_APPROVAL 时，说明模板配置本身可执行，但属于高风险动作，例如自定义 SQL、
     * 全库/全 schema 迁移或覆盖式写入。它不能像 READY_TO_EXECUTE 一样直接入队，必须看到任务上的
     * approvalState=APPROVED。这样能让 API、worker 和 E2E 都共享同一条审批闸门。</p>
     */
    private boolean canRunAfterPrecheck(SyncTemplateExecutionPrecheckResponse precheck, SyncTask task) {
        if (precheck.canStartExecution()) {
            return true;
        }
        return SyncTemplateExecutionPrecheckSupport.REQUIRES_APPROVAL.equals(precheck.precheckStatus())
                && SyncApprovalState.APPROVED.name().equals(task.getApprovalState());
    }

    /**
     * 校验谁可以向 data-sync 提交“审批已完成”这一低敏事实。
     *
     * <p>当前仓库还没有把正式 approval service 远程接入 data-sync，所以先使用角色白名单做本地兜底。
     * 普通用户、项目成员或只读角色不能通过请求体里的布尔值绕过审批；只有平台管理员、租户管理员、
     * 运营人员和受控服务账号可以提交审批事实。后续接入 permission-admin 后，这里应替换为策略引擎调用。</p>
     */
    private void assertTrustedApprovalSubmitter(SyncActorContext actorContext, String operation) {
        String role = normalizeRole(actorContext);
        if (!"PLATFORM_ADMINISTRATOR".equals(role)
                && !"TENANT_ADMINISTRATOR".equals(role)
                && !"OPERATOR".equals(role)
                && !"SERVICE_ACCOUNT".equals(role)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前角色无权提交 data-sync 审批确认事实，operation=" + operation + ", role=" + role);
        }
    }

    /**
     * 校验审批事实 ID 是否保持低敏。
     *
     * <p>审批事实 ID 应该像 {@code approval:sync-local-e2e-001} 这类引用，而不是审批正文、SQL、对象清单或字段映射。
     * 因此这里只允许短的字母、数字、冒号、下划线、短横线、点和斜杠；如果未来审批中心使用 UUID、ULID、工单号或
     * trace-like 编码，都可以落在这个安全字符集内。</p>
     */
    private void validateApprovalFactId(String approvalFactId, boolean required) {
        String factId = querySupport.trimToNull(approvalFactId);
        if (factId == null) {
            if (required) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "高风险同步任务确认审批时必须提供低敏 approvalFactId");
            }
            return;
        }
        if (factId.length() > APPROVAL_FACT_ID_MAX_LENGTH
                || !factId.matches("[A-Za-z0-9:_./-]+")) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "approvalFactId 只能是低敏短引用，允许字母、数字、冒号、下划线、短横线、点和斜杠，且长度不超过 "
                            + APPROVAL_FACT_ID_MAX_LENGTH);
        }
    }

    private String buildCreateTaskAuditPayload(SyncTask task,
                                               SyncTemplateExecutionPrecheckResponse precheck,
                                               CreateSyncTaskRequest request) {
        /*
         * 创建任务审计只记录低敏配置事实，不再记录 request.approvalFactId。
         * 审批事实应属于后续“发布/执行/恢复/高风险运营动作”的专用流程；如果仍把它放在创建审计里，前端和用户会误以为
         * 新建同步任务本身需要审批，这与当前产品口径不一致。
         */
        return "taskId=" + task.getId()
                + ",templateId=" + task.getTemplateId()
                + ",precheckStatus=" + precheck.precheckStatus()
                + ",approvalRequired=" + precheck.approvalRequired()
                + ",creationApprovalPolicy=NOT_USED_IN_CREATE_WIZARD"
                + ",approvalState=" + task.getApprovalState()
                + ",scheduleEnabled=" + task.getScheduleEnabled()
                + ",nextFireTime=" + task.getNextFireTime()
                + ",groupCode=" + task.getGroupCode()
                + ",runMode=" + task.getRunMode();
    }

    @Override
    @Transactional
    public SyncTaskOperationResult pauseTask(Long id,
                                             SyncTaskLifecycleOperationRequest request,
                                             SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskLifecycleOperationSupport.pauseTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult resumeTask(Long id,
                                              SyncTaskLifecycleOperationRequest request,
                                              SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskLifecycleOperationSupport.resumeTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult retryTask(Long id,
                                             SyncTaskLifecycleOperationRequest request,
                                             SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskLifecycleOperationSupport.retryTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult cancelTask(Long id,
                                              SyncTaskLifecycleOperationRequest request,
                                              SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskLifecycleOperationSupport.cancelTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult manualTerminateTask(Long id,
                                                       SyncTaskLifecycleOperationRequest request,
                                                       SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskManagementOperationSupport.manualTerminateTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult offlineTask(Long id,
                                               SyncTaskLifecycleOperationRequest request,
                                               SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskManagementOperationSupport.offlineTask(task, request, actorContext);
    }

    /**
     * 批量删除同步任务到回收站。
     *
     * <p>该入口不会自动把任务下线后再删除，因为“下线”和“删除到回收站”在审计、告警和用户确认上是两件事。
     * 如果任务尚未 OFFLINE，单条结果会失败并提示调用方先执行批量下线。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchRecycleTasks(SyncTaskBatchOperationRequest request,
                                                          SyncActorContext actorContext) {
        return taskBatchOperationSupport.recycleTasks(request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult recycleTask(Long id,
                                               SyncTaskLifecycleOperationRequest request,
                                               SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskManagementOperationSupport.recycleTask(task, request, actorContext);
    }

    /**
     * 批量彻底删除回收站任务。
     *
     * <p>彻底删除仍采用逻辑 DELETED，且只允许 RECYCLED 任务进入该状态。
     * 这样可以兼顾“普通列表不可见”和“历史执行/审计证据可追溯”。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchHardDeleteTasks(SyncTaskBatchOperationRequest request,
                                                             SyncActorContext actorContext) {
        return taskBatchOperationSupport.hardDeleteTasks(request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult hardDeleteTask(Long id,
                                                  SyncTaskLifecycleOperationRequest request,
                                                  SyncActorContext actorContext) {
        /*
         * hard-delete 必须能读取 RECYCLED 任务，但不能读取 DELETED 任务。
         * getTask(...) 已经会对 DELETED 返回 NOT_FOUND；RECYCLED 仍可通过数据范围校验后进入这里。
         */
        SyncTask task = getTask(id, actorContext);
        return taskManagementOperationSupport.hardDeleteTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult cloneTask(Long id,
                                             SyncTaskCloneRequest request,
                                             SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskManagementOperationSupport.cloneTask(task, request, actorContext);
    }

    /**
     * 发起同步回放。
     *
     * <p>主 Service 只负责复用 getTask(...) 做租户、项目、SELF 范围校验，然后把恢复语义委托给
     * SyncTaskRecoveryOperationSupport。这样权限边界集中在入口，恢复计划、checkpoint 解析和审计细节集中在领域组件。
     */
    @Override
    @Transactional
    public SyncTaskOperationResult replayTask(Long id,
                                              SyncTaskRecoveryOperationRequest request,
                                              SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskRecoveryOperationSupport.replayTask(task, request, actorContext);
    }

    /**
     * 发起同步补数。
     *
     * <p>补数属于高影响恢复动作，入口仍先读取任务并校验数据范围。
     * 真实窗口参数校验和恢复计划持久化由 support 负责，避免主 Service 继续膨胀。
     */
    @Override
    @Transactional
    public SyncTaskOperationResult backfillTask(Long id,
                                                SyncTaskRecoveryOperationRequest request,
                                                SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        return taskRecoveryOperationSupport.backfillTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncExecution startExecution(Long taskId,
                                        Long executionId,
                                        SyncExecutionStartRequest request,
                                        SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.startExecution(task, execution, request, actorContext);
    }

    @Override
    @Transactional
    public SyncCheckpoint writeCheckpoint(Long taskId,
                                          Long executionId,
                                          SyncExecutionCheckpointRequest request,
                                          SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.writeCheckpoint(task, execution, request, actorContext);
    }

    @Override
    @Transactional
    public SyncExecution completeExecution(Long taskId,
                                           Long executionId,
                                           SyncExecutionCompleteRequest request,
                                           SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.completeExecution(task, execution, request, actorContext);
    }

    @Override
    @Transactional
    public SyncErrorSample failExecution(Long taskId,
                                         Long executionId,
                                         SyncExecutionFailRequest request,
                                         SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.failExecution(task, execution, request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncExecution> pageExecutions(SyncExecutionQueryCriteria criteria,
                                                              SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncExecution> wrapper = new LambdaQueryWrapper<SyncExecution>()
                .orderByDesc(SyncExecution::getCreateTime)
                .orderByDesc(SyncExecution::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncExecution::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncExecution::getProjectId, visibility);
        eqIfPresent(wrapper, SyncExecution::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncExecution::getExecutionState, normalizeCode(criteria.executionState()));
        eqIfPresent(wrapper, SyncExecution::getTriggerType, normalizeCode(criteria.triggerType()));
        Page<SyncExecution> page = executionMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 查询某次执行的运行日志。
     *
     * <p>这里故意不直接按 executionId 查询，而是先执行两步校验：</p>
     * <p>1. {@link #getTask(Long, SyncActorContext)} 校验调用者是否能读取该任务；</p>
     * <p>2. {@link #getExecutionForTask(Long, SyncTask)} 校验 execution 是否确实属于该任务。</p>
     *
     * <p>完成这两步后，再从日志表查询。这样即使调用者猜到了别的 executionId，也无法绕过任务级数据范围。
     * 日志表虽然只保存低敏信息，但运行速度、对象顺序、失败阶段仍属于运营证据，不能变成无权限可枚举资源。</p>
     */
    @Override
    public PlatformPageResponse<SyncExecutionLog> pageExecutionLogs(SyncExecutionLogQueryCriteria criteria,
                                                                    SyncActorContext actorContext) {
        SyncTask task = getTask(criteria.syncTaskId(), actorContext);
        SyncExecution execution = getExecutionForTask(criteria.executionId(), task);
        LambdaQueryWrapper<SyncExecutionLog> wrapper = new LambdaQueryWrapper<SyncExecutionLog>()
                .eq(SyncExecutionLog::getSyncTaskId, task.getId())
                .eq(SyncExecutionLog::getExecutionId, execution.getId())
                .orderByAsc(SyncExecutionLog::getEventTime)
                .orderByAsc(SyncExecutionLog::getId);
        eqIfPresent(wrapper, SyncExecutionLog::getLogStage, normalizeCode(criteria.logStage()));
        eqIfPresent(wrapper, SyncExecutionLog::getLogLevel, normalizeCode(criteria.logLevel()));
        Page<SyncExecutionLog> page = executionLogMapper.selectPage(
                querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 查询父 execution 下的对象级执行明细。
     *
     * <p>Service 层在这里先复用 {@link #getTask(Long, SyncActorContext)} 和
     * {@link #getExecutionForTask(Long, SyncTask)} 完成数据范围与父子归属校验，再委托 support 查询对象账本。
     * 这样 Controller 不需要理解权限细节，support 也不需要重复读取任务做入口级授权。</p>
     */
    @Override
    public PlatformPageResponse<SyncObjectExecutionView> pageObjectExecutions(SyncObjectExecutionQueryCriteria criteria,
                                                                              SyncActorContext actorContext) {
        SyncTask task = getTask(criteria.syncTaskId(), actorContext);
        SyncExecution execution = getExecutionForTask(criteria.executionId(), task);
        return objectExecutionOperationSupport.pageObjectExecutions(task, execution, criteria);
    }

    /**
     * 发起对象级失败重试。
     *
     * <p>这不是普通整单 retry，而是 DataX-style “失败对象/分片重传”。因此入口先校验 task/execution 的可见性和归属，
     * 再由 {@link SyncObjectExecutionOperationSupport} 重置 FAILED 对象、重新排队父 execution 并写审计。</p>
     */
    @Override
    @Transactional
    public SyncObjectRetryResult retryObjectExecutions(Long taskId,
                                                       Long executionId,
                                                       SyncObjectRetryRequest request,
                                                       SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return objectExecutionOperationSupport.retryFailedObjects(task, execution, request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncCheckpoint> pageCheckpoints(SyncCheckpointQueryCriteria criteria,
                                                                SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncCheckpoint> wrapper = new LambdaQueryWrapper<SyncCheckpoint>()
                .orderByDesc(SyncCheckpoint::getCheckpointTime)
                .orderByDesc(SyncCheckpoint::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncCheckpoint::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncCheckpoint::getProjectId, visibility);
        eqIfPresent(wrapper, SyncCheckpoint::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncCheckpoint::getExecutionId, criteria.executionId());
        eqIfPresent(wrapper, SyncCheckpoint::getCheckpointType, normalizeCode(criteria.checkpointType()));
        Page<SyncCheckpoint> page = checkpointMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public PlatformPageResponse<SyncErrorSample> pageErrorSamples(SyncErrorSampleQueryCriteria criteria,
                                                                  SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncErrorSample> wrapper = new LambdaQueryWrapper<SyncErrorSample>()
                .orderByDesc(SyncErrorSample::getCreateTime)
                .orderByDesc(SyncErrorSample::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncErrorSample::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncErrorSample::getProjectId, visibility);
        eqIfPresent(wrapper, SyncErrorSample::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncErrorSample::getExecutionId, criteria.executionId());
        eqIfPresent(wrapper, SyncErrorSample::getErrorType, normalizeCode(criteria.errorType()));
        if (criteria.retryable() != null) {
            wrapper.eq(SyncErrorSample::getRetryable, criteria.retryable());
        }
        Page<SyncErrorSample> page = errorSampleMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 基于错误样本创建脏数据修复重放计划。
     *
     * <p>Service 仍然只做入口级任务读取和权限收敛：先通过 {@link #getTask(Long, SyncActorContext)}
     * 校验租户、项目、SELF 范围，再把“错误样本选择、retryable 校验、修复确认、恢复计划创建、审计”等复杂规则委托给
     * {@link SyncDirtyRecordReplaySupport}。这样可以避免 DataSyncServiceImpl 因为数据治理细节继续膨胀。</p>
     */
    @Override
    @Transactional
    public SyncDirtyRecordReplayResult replayDirtyRecords(Long taskId,
                                                          SyncDirtyRecordReplayRequest request,
                                                          SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        return dirtyRecordReplaySupport.replayDirtyRecords(task, request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncAuditRecord> pageAuditRecords(SyncAuditQueryCriteria criteria,
                                                                  SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncAuditRecord> wrapper = new LambdaQueryWrapper<SyncAuditRecord>()
                .orderByDesc(SyncAuditRecord::getCreateTime)
                .orderByDesc(SyncAuditRecord::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncAuditRecord::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncAuditRecord::getProjectId, visibility);
        eqIfPresent(wrapper, SyncAuditRecord::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncAuditRecord::getExecutionId, criteria.executionId());
        eqIfPresent(wrapper, SyncAuditRecord::getActionType, normalizeCode(criteria.actionType()));
        eqIfPresent(wrapper, SyncAuditRecord::getActorId, criteria.actorId());
        Page<SyncAuditRecord> page = auditRecordMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 解析执行追踪类列表的可见范围。
     *
     * <p>执行记录、checkpoint、错误样本和审计记录有两种查询形态：
     * 1. 从某个任务详情页进入，此时 criteria 中带 syncTaskId，必须先读取任务并复用 getTask(...) 做租户、项目和 SELF 校验；
     * 2. 从运营台横向检索，此时可能没有 syncTaskId，需要直接依赖这些子表冗余的 tenantId/projectId 做范围收口。
     *
     * <p>这也是为什么 execution/checkpoint/error/audit 表要冗余 projectId：
     * 生产环境下全局执行历史和错误样本列表不能每次都 join 任务表，否则在高吞吐同步场景下会放大查询成本。
     */
    private SyncDataVisibility resolveQueryVisibility(Long syncTaskId, SyncActorContext actorContext) {
        if (syncTaskId != null) {
            SyncTask task = getTask(syncTaskId, actorContext);
            return new SyncDataVisibility(task.getTenantId(), task.getProjectId(), List.of(), task.getWorkspaceId(),
                    false, false, "TASK_SCOPED", null, false);
        }
        return dataScopeSupport.resolveVisibility(null, null, null, actorContext);
    }

    private SyncExecution getExecutionForTask(Long executionId, SyncTask task) {
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步执行记录不存在: " + executionId);
        }
        if (!task.getId().equals(execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "执行记录不属于当前同步任务，taskId=" + task.getId() + ", executionId=" + executionId);
        }
        return execution;
    }

    /**
     * 按任务读取关联模板。
     *
     * <p>运行任务时，入口已经通过 {@link #getTask(Long, SyncActorContext)} 完成了任务维度的数据范围校验。
     * 模板的 createdBy 不一定等于任务 owner，如果这里复用 {@link #getTemplate(Long, SyncActorContext)}，
     * SELF 数据范围下可能会误拒绝“我有权运行这个任务，但模板最初由项目负责人创建”的合法场景。
     * 因此这里改为校验任务和模板的租户/项目/工作空间归属一致，避免越权引用其它模板。</p>
     */
    private SyncTemplate getTemplateForTask(SyncTask task) {
        SyncTemplate template = templateMapper.selectById(task.getTemplateId());
        if (template == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务关联的模板不存在，templateId=" + task.getTemplateId());
        }
        if (!task.getTenantId().equals(template.getTenantId())
                || !sameNullable(task.getProjectId(), template.getProjectId())
                || !sameNullable(task.getWorkspaceId(), template.getWorkspaceId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步任务与模板归属不一致，拒绝执行，taskId=" + task.getId() + ", templateId=" + template.getId());
        }
        return template;
    }

    private <T, V> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                    com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, V> column,
                                    V value) {
        if (value != null) {
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            wrapper.eq(column, value);
        }
    }

    /**
     * 给任务列表追加分组筛选条件。
     *
     * <p>这里不能把“默认分组”简单写成 {@code group_code = 'DEFAULT'}。原因是项目在持续演进过程中，
     * 历史草稿、导入任务、旧版未分组任务以及删除分组后迁回的任务，可能分别留下 {@code NULL}、空字符串
     * 或 {@code DEFAULT} 三种存储形态。分组树和分组汇总已经把它们聚合成一个“默认分组”节点；
     * 如果列表接口仍只精确匹配 {@code DEFAULT}，页面就会出现“左侧默认分组 22 条，点击后列表只有 2 条”
     * 这种前后端语义错位。</p>
     *
     * <p>因此当筛选值为默认分组时，列表、回收站和导出等任务明细入口必须统一采用
     * {@code DEFAULT OR NULL OR ''} 的等价条件。普通业务分组仍然使用精确匹配，避免不同业务分组互相串数据。</p>
     */
    private void applyTaskGroupFilter(LambdaQueryWrapper<SyncTask> wrapper, String rawGroupCode) {
        String groupCode = taskGroupOperationSupport.normalizeGroupCodeForFilter(rawGroupCode);
        if (!hasText(groupCode)) {
            return;
        }
        if (SyncTaskGroupOperationSupport.DEFAULT_GROUP_CODE.equals(groupCode)) {
            wrapper.and(groupWrapper -> groupWrapper
                    .eq(SyncTask::getGroupCode, SyncTaskGroupOperationSupport.DEFAULT_GROUP_CODE)
                    .or()
                    .isNull(SyncTask::getGroupCode)
                    .or()
                    .eq(SyncTask::getGroupCode, ""));
            return;
        }
        wrapper.eq(SyncTask::getGroupCode, groupCode);
    }

    private boolean sameNullable(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRole(SyncActorContext actorContext) {
        String role = actorContext == null ? null : actorContext.actorRole();
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 解析任务应继承的项目值。
     *
     * <p>任务来自模板，因此项目默认继承模板。允许请求显式传入 projectId，是为了兼容旧脚本或导入链路中
     * “确认所属项目”的显式表达；但如果传入值与模板不一致，说明调用方试图把同一个模板挂到另一个项目下，
     * 这会破坏 PROJECT 数据范围和后续项目级统计。
     *
     * <p>注意：workspaceId 已不再通过该方法解析。工作空间属于历史兼容字段，新建链路只允许从历史模板继承，
     * 不允许请求体重新指定。</p>
     */
    private Long resolveScopeValue(String fieldName, Long requestedValue, Long templateValue) {
        if (requestedValue == null) {
            return templateValue;
        }
        if (templateValue != null && !templateValue.equals(requestedValue)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步任务" + fieldName + "必须与模板一致，templateValue=" + templateValue + ", requestedValue=" + requestedValue);
        }
        return requestedValue;
    }
}
