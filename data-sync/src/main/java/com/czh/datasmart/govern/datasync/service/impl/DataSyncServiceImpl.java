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
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplatePlanningPreviewResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataScopeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataVisibility;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncObjectExecutionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineJobPlanSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskLifecycleOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskRecoveryOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskStateMachineSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateExecutionPrecheckSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplatePlanningPreviewSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateValidationSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncApprovalState;
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

    private final SyncTemplateMapper templateMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
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
    private final SyncTaskLifecycleOperationSupport taskLifecycleOperationSupport;
    private final SyncTaskRecoveryOperationSupport taskRecoveryOperationSupport;
    private final SyncTemplateCreationSupport templateCreationSupport;
    private final SyncTemplatePlanningPreviewSupport templatePlanningPreviewSupport;
    private final SyncTemplateExecutionPrecheckSupport templateExecutionPrecheckSupport;
    private final SyncOfflineJobPlanSupport offlineJobPlanSupport;
    private final SyncObjectExecutionOperationSupport objectExecutionOperationSupport;

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
        return templateExecutionPrecheckSupport.precheck(template);
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
        Long tenantId = dataScopeSupport.resolveTenantForCreate(request.getTenantId(), actorContext);
        if (!tenantId.equals(template.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步任务租户必须与模板租户一致，templateTenantId=" + template.getTenantId() + ", taskTenantId=" + tenantId);
        }

        SyncTask task = new SyncTask();
        task.setTenantId(tenantId);
        task.setProjectId(resolveScopeValue("projectId", request.getProjectId(), template.getProjectId()));
        task.setWorkspaceId(resolveScopeValue("workspaceId", request.getWorkspaceId(), template.getWorkspaceId()));
        task.setTemplateId(template.getId());
        task.setName(querySupport.defaultText(request.getName(), template.getName()));
        task.setDescription(querySupport.defaultText(request.getDescription(), template.getDescription()));
        task.setCurrentState(SyncTaskState.CONFIGURED.name());
        task.setApprovalState(SyncApprovalState.NOT_REQUIRED.name());
        task.setPriority(querySupport.defaultText(request.getPriority(), "MEDIUM").toUpperCase(java.util.Locale.ROOT));
        task.setScheduleConfig(querySupport.trimToNull(request.getScheduleConfig()));
        task.setRunMode(querySupport.defaultText(request.getRunMode(), "TEMPLATE").toUpperCase(java.util.Locale.ROOT));
        task.setTriggerType(SyncTriggerType.MANUAL.name());
        task.setOwnerId(request.getOwnerId() == null ? querySupport.actorId(actorContext) : request.getOwnerId());
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), null, SyncAuditActionType.CREATE_TASK,
                actorContext, "taskId=" + task.getId() + ",templateId=" + task.getTemplateId());
        return task;
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
        querySupport.eqIfPresent(wrapper, SyncTask::getCurrentState, querySupport.normalizeCode(criteria.currentState()));
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
        dataScopeSupport.validateOwnedReadable(task.getTenantId(), task.getProjectId(),
                task.getOwnerId(), actorContext, "同步任务");
        return task;
    }

    @Override
    @Transactional
    public SyncTaskOperationResult runTask(Long id, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        SyncTemplate template = getTemplateForTask(task);
        SyncTemplateExecutionPrecheckResponse precheck = templateExecutionPrecheckSupport.precheck(template);
        if (!precheck.canStartExecution()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务执行前预检查未通过，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes()
                            + "，recommendedActions=" + precheck.recommendedActions());
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

    private boolean sameNullable(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 解析任务应继承的项目/空间值。
     *
     * <p>任务来自模板，因此项目和空间默认继承模板。允许请求显式传入，是为了前端表单可以做“确认所属项目/空间”的显式表达；
     * 但如果传入值与模板不一致，说明调用方试图把同一个模板挂到另一个项目/空间下，这会破坏 PROJECT 数据范围和后续项目级统计。
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
