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
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
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
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskStateMachineSupport;
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

    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 200L;

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

    @Override
    @Transactional
    public SyncTemplate createTemplate(CreateSyncTemplateRequest request, SyncActorContext actorContext) {
        SyncTemplate template = new SyncTemplate();
        template.setTenantId(dataScopeSupport.resolveTenantForCreate(request.getTenantId(), actorContext));
        template.setProjectId(request.getProjectId());
        template.setWorkspaceId(request.getWorkspaceId());
        template.setName(request.getName().trim());
        template.setDescription(querySupport.trimToNull(request.getDescription()));
        template.setSourceDatasourceId(request.getSourceDatasourceId());
        template.setTargetDatasourceId(request.getTargetDatasourceId());
        template.setSyncMode(querySupport.normalizeCode(request.getSyncMode()));
        template.setFieldMappingConfig(querySupport.trimToNull(request.getFieldMappingConfig()));
        template.setFilterConfig(querySupport.trimToNull(request.getFilterConfig()));
        template.setPartitionConfig(querySupport.trimToNull(request.getPartitionConfig()));
        template.setRetryPolicy(querySupport.trimToNull(request.getRetryPolicy()));
        template.setTimeoutPolicy(querySupport.trimToNull(request.getTimeoutPolicy()));
        template.setEnabled(true);
        template.setCreatedBy(actorContext == null ? null : actorContext.actorId());
        template.setUpdatedBy(actorContext == null ? null : actorContext.actorId());
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        /*
         * 写入类动作必须在入库前完成项目范围校验。
         *
         * 列表查询可以在 SQL 上追加 project_id IN (...)，详情查询可以读出对象后校验 projectId；
         * 但创建模板如果不提前校验，就会先把未授权项目的数据写进库里，后续再靠“看不到”补救已经太晚。
         * 因此这里显式调用 validateProjectWritable，把 PROJECT 范围下的越权写入阻断在 insert 之前。
         */
        dataScopeSupport.validateProjectWritable(
                template.getTenantId(), template.getProjectId(), template.getWorkspaceId(), actorContext, "同步模板");
        templateValidationSupport.validateTemplate(template);
        templateMapper.insert(template);
        auditSupport.saveTemplateAudit(template, SyncAuditActionType.CREATE_TEMPLATE,
                actorContext, "templateId=" + template.getId() + ",syncMode=" + template.getSyncMode());
        return template;
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
        task.setName(defaultText(request.getName(), template.getName()));
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

    private <T> Page<T> page(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
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

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultText(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private Long actorId(SyncActorContext actorContext) {
        return actorContext == null ? null : actorContext.actorId();
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
