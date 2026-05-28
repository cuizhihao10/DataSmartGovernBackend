/**
 * @Author : Cui
 * @Date: 2026/05/05 23:30
 * @Description DataSmart Govern Backend - SyncTaskLifecycleSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncApprovalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncScheduleRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasource.support.ApprovalState;
import com.czh.datasmart.govern.datasource.support.PriorityLevel;
import com.czh.datasmart.govern.datasource.support.RunMode;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import com.czh.datasmart.govern.datasource.support.TriggerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * 同步任务生命周期支撑组件。
 *
 * <p>该组件承载同步任务从创建、更新、提交审批、审批、排期、入队到归档的基础生命周期。
 * 它从 `SyncTaskServiceImpl` 拆出后，主服务可以更专注于执行期动作，例如人工运行、暂停、重试、完成、失败回调等。
 *
 * <p>为什么生命周期要独立：
 * 1. 创建/更新阶段关注配置正确性、模板可用性、名称唯一性和初始状态；
 * 2. 审批阶段关注治理控制，防止高风险同步任务绕过审核直接调度；
 * 3. 排期/入队阶段关注任务是否启用、审批是否就绪、队列容量是否允许；
 * 4. 归档阶段关注历史保留和审计，而不是物理删除。
 *
 * <p>后续如果要做任务模板化、批量启停、审批流、任务版本发布、租户级目录、任务复制、任务导入导出，
 * 应优先扩展这里，而不是让主服务重新长回胖服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncTaskLifecycleSupport {

    /**
     * 同步任务 Mapper。
     */
    private final SyncTaskMapper syncTaskMapper;

    /**
     * 同步模板 Mapper。
     */
    private final SyncTemplateMapper syncTemplateMapper;

    /**
     * 队列容量组件。
     * 入队前必须检查全局和租户容量，避免配置页或接口误操作把队列打爆。
     */
    private final SyncQueueCapacitySupport syncQueueCapacitySupport;

    /**
     * 权限组件。
     * 生命周期动作涉及创建、修改、审批、调度、归档等不同权限边界。
     */
    private final SyncTaskPermissionSupport syncTaskPermissionSupport;

    /**
     * 审计组件。
     * 所有生命周期动作都需要可追踪，便于后续审计、复盘和运营排障。
     */
    private final SyncAuditSupport syncAuditSupport;

    public SyncTask createTask(CreateSyncTaskRequest request) {
        syncTaskPermissionSupport.assertTaskCreationPermission(request);
        SyncTemplate template = getRequiredEnabledTemplate(request.getTemplateId());
        ensureTemplateTenantConsistent(request, template);
        ensureTaskNameUnique(request.getTenantId(), template.getProjectId(), request.getName(), null);
        PriorityLevel priority = PriorityLevel.fromValue(request.getPriority());
        RunMode runMode = RunMode.fromValue(request.getRunMode());
        TriggerType triggerType = TriggerType.fromValue(request.getTriggerType());

        SyncTask task = new SyncTask();
        task.setTenantId(request.getTenantId());
        task.setProjectId(template.getProjectId());
        task.setWorkspaceId(template.getWorkspaceId());
        task.setTemplateId(template.getId());
        task.setName(request.getName());
        task.setDescription(request.getDescription());
        task.setCurrentState(SyncTaskState.CONFIGURED.name());
        task.setApprovalState(Boolean.TRUE.equals(request.getApprovalRequired())
                ? ApprovalState.PENDING.name()
                : ApprovalState.NOT_REQUIRED.name());
        task.setPriority(priority.name());
        task.setRunMode(runMode.name());
        task.setTriggerType(triggerType.name());
        task.setScheduleConfig(request.getScheduleConfig());
        task.setOwnerId(request.getOwnerId());
        task.setEnabled(request.getEnabled() == null || request.getEnabled());
        task.setOperatorAttentionRequired(Boolean.TRUE.equals(request.getOperatorAttentionRequired()));
        task.setTimeoutSeconds(request.getTimeoutSeconds());
        task.setMaxRetryCount(request.getMaxRetryCount());
        task.setRetryCount(0);
        task.setQueueAttemptCount(0);
        task.setCurrentExecutorId(null);
        task.setDispatchLeaseExpireAt(null);
        task.setQueuedAt(null);
        task.setIncidentNote(syncAuditSupport.truncate(request.getIncidentNote()));
        task.setCreatedBy(request.getCreatedBy());
        task.setUpdatedBy(request.getCreatedBy());
        syncTaskMapper.insert(task);
        syncAuditSupport.recordAudit(task, null, SyncAuditAction.CREATE_TASK, request.getCreatedBy(), request.getActorRole(),
                syncAuditSupport.buildPayload(
                        "taskId", task.getId(),
                        "templateId", task.getTemplateId(),
                        "projectId", task.getProjectId(),
                        "workspaceId", task.getWorkspaceId(),
                        "approvalState", task.getApprovalState(),
                        "priority", task.getPriority(),
                        "runMode", task.getRunMode()
                ));
        return task;
    }

    public SyncTask updateTask(Long id, UpdateSyncTaskRequest request) {
        SyncTask task = getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskUpdatePermission(task, request);
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.PENDING_APPROVAL,
                SyncTaskState.SCHEDULED, SyncTaskState.FAILED, SyncTaskState.CANCELLED);
        ensureTaskNameUnique(task.getTenantId(), task.getProjectId(), request.getName(), id);

        task.setName(request.getName());
        task.setDescription(request.getDescription());
        task.setPriority(PriorityLevel.fromValue(request.getPriority()).name());
        task.setRunMode(RunMode.fromValue(request.getRunMode()).name());
        task.setTriggerType(TriggerType.fromValue(request.getTriggerType()).name());
        task.setScheduleConfig(request.getScheduleConfig());
        task.setOwnerId(request.getOwnerId());
        task.setEnabled(request.getEnabled());
        task.setOperatorAttentionRequired(request.getOperatorAttentionRequired());
        task.setTimeoutSeconds(request.getTimeoutSeconds());
        task.setMaxRetryCount(request.getMaxRetryCount());
        task.setIncidentNote(syncAuditSupport.truncate(request.getIncidentNote()));
        task.setUpdatedBy(request.getUpdatedBy());
        syncTaskMapper.updateById(task);
        syncAuditSupport.recordAudit(task, null, SyncAuditAction.UPDATE_TASK, request.getUpdatedBy(), request.getActorRole(),
                syncAuditSupport.buildPayload("taskId", task.getId(), "name", task.getName(), "priority", task.getPriority()));
        return task;
    }

    public SyncTask submitForApproval(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED);
        if (!ApprovalState.PENDING.name().equals(task.getApprovalState())) {
            throw new IllegalStateException("当前任务不需要进入审批流");
        }
        task.setCurrentState(SyncTaskState.PENDING_APPROVAL.name());
        task.setIncidentNote(syncAuditSupport.truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        syncAuditSupport.recordAudit(task, null, SyncAuditAction.SUBMIT_APPROVAL, request.getActorId(), request.getActorRole(),
                syncAuditSupport.buildPayload("taskId", task.getId(), "note", request.getNote()));
        return task;
    }

    public SyncTask approve(Long id, SyncApprovalRequest request) {
        SyncTask task = getRequiredTask(id);
        assertStateIn(task, SyncTaskState.PENDING_APPROVAL);
        syncTaskPermissionSupport.assertApprovalPermission(request, task);
        if (Boolean.TRUE.equals(request.getApproved())) {
            task.setApprovalState(ApprovalState.APPROVED.name());
            task.setCurrentState(SyncTaskState.CONFIGURED.name());
            syncAuditSupport.recordAudit(task, null, SyncAuditAction.APPROVE_TASK, request.getActorId(), request.getActorRole(),
                    syncAuditSupport.buildPayload("taskId", task.getId(), "comment", request.getComment()));
        } else {
            task.setApprovalState(ApprovalState.REJECTED.name());
            task.setCurrentState(SyncTaskState.CONFIGURED.name());
            task.setOperatorAttentionRequired(true);
            syncAuditSupport.recordAudit(task, null, SyncAuditAction.REJECT_TASK, request.getActorId(), request.getActorRole(),
                    syncAuditSupport.buildPayload("taskId", task.getId(), "comment", request.getComment()));
        }
        task.setIncidentNote(syncAuditSupport.truncate(request.getComment()));
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        return task;
    }

    public SyncTask schedule(Long id, SyncScheduleRequest request) {
        SyncTask task = getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.SCHEDULED);
        ensureApprovalReady(task);
        ensureTaskEnabled(task);
        task.setScheduleConfig(request.getScheduleConfig());
        task.setNextRunAt(request.getNextRunAt());
        task.setCurrentState(SyncTaskState.SCHEDULED.name());
        task.setIncidentNote(syncAuditSupport.truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        syncAuditSupport.recordAudit(task, null, SyncAuditAction.SCHEDULE_TASK, request.getActorId(), request.getActorRole(),
                syncAuditSupport.buildPayload(
                        "taskId", task.getId(),
                        "nextRunAt", request.getNextRunAt(),
                        "scheduleConfig", request.getScheduleConfig()
                ));
        return task;
    }

    public SyncTask enqueue(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.SCHEDULED, SyncTaskState.RETRYING, SyncTaskState.PAUSED);
        ensureApprovalReady(task);
        ensureTaskEnabled(task);
        getRequiredEnabledTemplate(task.getTemplateId());
        syncQueueCapacitySupport.ensureQueueCapacity(task);
        markTaskQueued(task, request.getActorId(), request.getNote());
        syncTaskMapper.updateById(task);
        syncAuditSupport.recordAudit(task, null, SyncAuditAction.QUEUE_TASK, request.getActorId(), request.getActorRole(),
                syncAuditSupport.buildPayload("taskId", task.getId(), "queueAttemptCount", task.getQueueAttemptCount(), "note", request.getNote()));
        return task;
    }

    public SyncTask archive(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        syncTaskPermissionSupport.assertAdminRole(request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.SUCCEEDED, SyncTaskState.FAILED,
                SyncTaskState.CANCELLED, SyncTaskState.PARTIALLY_SUCCEEDED);
        task.setCurrentState(SyncTaskState.ARCHIVED.name());
        clearDispatchAssignment(task);
        task.setIncidentNote(syncAuditSupport.truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        syncAuditSupport.recordAudit(task, task.getLastExecutionId(), SyncAuditAction.ARCHIVE_TASK, request.getActorId(), request.getActorRole(),
                syncAuditSupport.buildPayload("note", request.getNote()));
        return task;
    }

    public SyncTask getRequiredTask(Long id) {
        SyncTask task = syncTaskMapper.selectById(id);
        if (task == null) {
            throw new NoSuchElementException("同步任务不存在: " + id);
        }
        return task;
    }

    public SyncTemplate getRequiredEnabledTemplate(Long templateId) {
        SyncTemplate template = syncTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new NoSuchElementException("同步模板不存在: " + templateId);
        }
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new IllegalStateException("同步模板当前未启用: " + templateId);
        }
        return template;
    }

    /**
     * 校验任务创建请求与模板租户是否一致。
     *
     * <p>任务的项目和工作空间会继承模板，因此租户也必须与模板保持一致。
     * 如果请求体传入的 tenantId 与模板不一致，说明调用方试图把其他租户的模板实例化成本租户任务，
     * 这会破坏租户隔离、项目过滤和审计归属，必须在任务落库前直接拒绝。</p>
     */
    private void ensureTemplateTenantConsistent(CreateSyncTaskRequest request, SyncTemplate template) {
        if (!template.getTenantId().equals(request.getTenantId())) {
            throw new IllegalArgumentException("任务租户必须与同步模板租户一致，requestTenantId="
                    + request.getTenantId() + ", templateTenantId=" + template.getTenantId());
        }
    }

    public void ensureApprovalReady(SyncTask task) {
        if (ApprovalState.PENDING.name().equals(task.getApprovalState())) {
            throw new IllegalStateException("当前任务尚未审批通过，不能进入调度或执行");
        }
        if (ApprovalState.REJECTED.name().equals(task.getApprovalState())) {
            throw new IllegalStateException("当前任务审批已被驳回，请先调整配置后重新提交审批");
        }
    }

    public void ensureTaskEnabled(SyncTask task) {
        if (!Boolean.TRUE.equals(task.getEnabled())) {
            throw new IllegalStateException("当前任务未启用，不允许执行");
        }
    }

    public void assertStateIn(SyncTask task, SyncTaskState... allowedStates) {
        SyncTaskState currentState = SyncTaskState.fromValue(task.getCurrentState());
        boolean matched = Arrays.stream(allowedStates).anyMatch(item -> item == currentState);
        if (!matched) {
            throw new IllegalStateException("任务当前状态不允许此操作，currentState=" + currentState.name());
        }
    }

    public void markTaskQueued(SyncTask task, Long actorId, String note) {
        task.setCurrentState(SyncTaskState.QUEUED.name());
        task.setQueuedAt(LocalDateTime.now());
        task.setQueueAttemptCount((task.getQueueAttemptCount() == null ? 0 : task.getQueueAttemptCount()) + 1);
        clearDispatchAssignment(task);
        task.setIncidentNote(syncAuditSupport.truncate(note));
        task.setUpdatedBy(actorId);
    }

    public void clearDispatchAssignment(SyncTask task) {
        task.setCurrentExecutorId(null);
        task.setDispatchLeaseExpireAt(null);
    }

    private void ensureTaskNameUnique(Long tenantId, Long projectId, String name, Long currentId) {
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTenantId, tenantId)
                .eq(SyncTask::getProjectId, projectId)
                .eq(SyncTask::getName, name)
                .ne(currentId != null, SyncTask::getId, currentId)
                .ne(SyncTask::getCurrentState, SyncTaskState.ARCHIVED.name());
        if (syncTaskMapper.selectCount(wrapper) > 0) {
            throw new IllegalArgumentException("同步任务名称已存在: " + name);
        }
    }
}
