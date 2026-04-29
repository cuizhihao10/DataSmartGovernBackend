package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncApprovalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncCompleteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorHeartbeatRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncFailRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPriorityOverrideRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncProgressRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncQueueAgingScanResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncQueueHealthSnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncRunRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncScheduleRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncTimeoutOverrideRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasource.service.SyncGovernanceAlertService;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.ApprovalState;
import com.czh.datasmart.govern.datasource.support.PriorityLevel;
import com.czh.datasmart.govern.datasource.support.RunMode;
import com.czh.datasmart.govern.datasource.support.SyncAlertSeverity;
import com.czh.datasmart.govern.datasource.support.SyncAlertType;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import com.czh.datasmart.govern.datasource.support.TriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:05
 * @Description DataSmart Govern Backend - SyncTaskServiceImpl.java
 * @Version:1.0.0
 *
 * 同步任务服务实现。
 * 这一层是当前数据同步控制面的核心，因为它集中管理：
 * - 任务状态机；
 * - 审批流；
 * - 管理员强制控制；
 * - 执行记录生成；
 * - 检查点落库；
 * - 审计轨迹写入。
 *
 * 尽管当前仓库里还没有真正的异步调度器和执行器，这里仍然先把控制逻辑做全，
 * 原因是后续真正接入执行器时，最难返工的往往不是“调个接口”，而是任务状态模型和控制面契约本身。
 */
@Service
@RequiredArgsConstructor
public class SyncTaskServiceImpl extends ServiceImpl<SyncTaskMapper, SyncTask> implements SyncTaskService {

    private final SyncTemplateMapper syncTemplateMapper;
    private final SyncExecutionMapper syncExecutionMapper;
    private final SyncCheckpointMapper syncCheckpointMapper;
    private final SyncAuditRecordMapper syncAuditRecordMapper;
    private final SyncExecutorProperties syncExecutorProperties;
    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final SyncGovernanceAlertService syncGovernanceAlertService;

    @Override
    @Transactional
    public SyncTask createTask(CreateSyncTaskRequest request) {
        assertTaskCreationPermission(request);
        SyncTemplate template = getRequiredEnabledTemplate(request.getTemplateId());
        ensureTaskNameUnique(request.getTenantId(), request.getName(), null);

        PriorityLevel priority = PriorityLevel.fromValue(request.getPriority());
        RunMode runMode = RunMode.fromValue(request.getRunMode());
        TriggerType triggerType = TriggerType.fromValue(request.getTriggerType());

        SyncTask task = new SyncTask();
        task.setTenantId(request.getTenantId());
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
        task.setIncidentNote(truncate(request.getIncidentNote()));
        task.setCreatedBy(request.getCreatedBy());
        task.setUpdatedBy(request.getCreatedBy());
        save(task);

        recordAudit(task, null, SyncAuditAction.CREATE_TASK, request.getCreatedBy(), request.getActorRole(),
                buildPayload(
                        "taskId", task.getId(),
                        "templateId", task.getTemplateId(),
                        "approvalState", task.getApprovalState(),
                        "priority", task.getPriority(),
                        "runMode", task.getRunMode()
                ));
        return task;
    }

    @Override
    @Transactional
    public SyncTask updateTask(Long id, UpdateSyncTaskRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskUpdatePermission(task, request);
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.PENDING_APPROVAL,
                SyncTaskState.SCHEDULED, SyncTaskState.FAILED, SyncTaskState.CANCELLED);
        ensureTaskNameUnique(task.getTenantId(), request.getName(), id);

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
        task.setIncidentNote(truncate(request.getIncidentNote()));
        task.setUpdatedBy(request.getUpdatedBy());
        updateById(task);

        recordAudit(task, null, SyncAuditAction.UPDATE_TASK, request.getUpdatedBy(), request.getActorRole(),
                buildPayload("taskId", task.getId(), "name", task.getName(), "priority", task.getPriority()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask submitForApproval(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED);
        if (!ApprovalState.PENDING.name().equals(task.getApprovalState())) {
            throw new IllegalStateException("当前任务不需要进入审批流");
        }

        task.setCurrentState(SyncTaskState.PENDING_APPROVAL.name());
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, null, SyncAuditAction.SUBMIT_APPROVAL, request.getActorId(), request.getActorRole(),
                buildPayload("taskId", task.getId(), "note", request.getNote()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask approve(Long id, SyncApprovalRequest request) {
        SyncTask task = getRequiredTask(id);
        assertStateIn(task, SyncTaskState.PENDING_APPROVAL);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getActorId())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(task.getTenantId())
                        .resourceOwnerId(task.getOwnerId())
                        .resourceCreatedBy(task.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_APPROVAL, SyncPermissionAction.APPROVE);

        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        if (!actorRole.canApprove()) {
            throw new IllegalStateException("当前角色无审批权限: " + actorRole.name());
        }

        if (Boolean.TRUE.equals(request.getApproved())) {
            task.setApprovalState(ApprovalState.APPROVED.name());
            task.setCurrentState(SyncTaskState.CONFIGURED.name());
            recordAudit(task, null, SyncAuditAction.APPROVE_TASK, request.getActorId(), request.getActorRole(),
                    buildPayload("taskId", task.getId(), "comment", request.getComment()));
        } else {
            task.setApprovalState(ApprovalState.REJECTED.name());
            task.setCurrentState(SyncTaskState.CONFIGURED.name());
            task.setOperatorAttentionRequired(true);
            recordAudit(task, null, SyncAuditAction.REJECT_TASK, request.getActorId(), request.getActorRole(),
                    buildPayload("taskId", task.getId(), "comment", request.getComment()));
        }

        task.setIncidentNote(truncate(request.getComment()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);
        return task;
    }

    @Override
    @Transactional
    public SyncTask schedule(Long id, SyncScheduleRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.SCHEDULED);
        ensureApprovalReady(task);
        ensureTaskEnabled(task);

        task.setScheduleConfig(request.getScheduleConfig());
        task.setNextRunAt(request.getNextRunAt());
        task.setCurrentState(SyncTaskState.SCHEDULED.name());
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, null, SyncAuditAction.SCHEDULE_TASK, request.getActorId(), request.getActorRole(),
                buildPayload(
                        "taskId", task.getId(),
                        "nextRunAt", request.getNextRunAt(),
                        "scheduleConfig", request.getScheduleConfig()
                ));
        return task;
    }

    @Override
    @Transactional
    public SyncTask enqueue(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.SCHEDULED, SyncTaskState.RETRYING, SyncTaskState.PAUSED);
        ensureApprovalReady(task);
        ensureTaskEnabled(task);
        getRequiredEnabledTemplate(task.getTemplateId());
        ensureQueueCapacity(task);

        markTaskQueued(task, request.getActorId(), request.getNote());
        updateById(task);

        recordAudit(task, null, SyncAuditAction.QUEUE_TASK, request.getActorId(), request.getActorRole(),
                buildPayload("taskId", task.getId(), "queueAttemptCount", task.getQueueAttemptCount(), "note", request.getNote()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask run(Long id, SyncRunRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.SCHEDULED, SyncTaskState.QUEUED, SyncTaskState.RETRYING);
        ensureApprovalReady(task);
        ensureTaskEnabled(task);
        SyncTemplate template = getRequiredEnabledTemplate(task.getTemplateId());
        if (!Boolean.TRUE.equals(syncExecutorProperties.getAllowManualRunBypassQueue())
                && !SyncTaskState.QUEUED.name().equals(task.getCurrentState())) {
            throw new IllegalStateException("当前环境不允许人工直跑绕过队列，请先将任务 enqueue 后由执行器认领");
        }
        ensureConcurrencyCapacity(task, template);

        SyncExecution execution = createExecution(task, SyncTaskState.RUNNING, request.getActorId(), request.getTriggerReason());
        task.setCurrentState(SyncTaskState.RUNNING.name());
        task.setTriggerType(TriggerType.fromValue(request.getTriggerType()).name());
        task.setLastExecutionId(execution.getId());
        task.setQueuedAt(null);
        task.setCurrentExecutorId(null);
        task.setDispatchLeaseExpireAt(null);
        task.setLatestErrorSummary(null);
        task.setOperatorAttentionRequired(false);
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.RUN_TASK, request.getActorId(), request.getActorRole(),
                buildPayload(
                        "executionId", execution.getId(),
                        "triggerType", request.getTriggerType(),
                        "triggerReason", request.getTriggerReason()
                ));
        return task;
    }

    @Override
    @Transactional
    public SyncExecutorClaimResult claimNextQueuedTask(SyncExecutorClaimRequest request) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getActorId())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .build(),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.CLAIM);
        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        if (!actorRole.canClaimQueuedTasks()) {
            throw new IllegalStateException("当前角色无队列任务认领权限: " + actorRole.name());
        }

        if (Boolean.TRUE.equals(syncExecutorProperties.getAutoRecoverExpiredLeasesBeforeClaim())) {
            recoverExpiredLeasesInternal(
                    request.getActorId(),
                    request.getActorRole(),
                    "AUTO_RECOVER_BEFORE_CLAIM:" + request.getExecutorId(),
                    false
            );
        }

        ClaimCandidate candidate = selectNextClaimableTask(request);
        if (candidate == null) {
            return null;
        }

        SyncTask task = candidate.task();
        SyncTemplate template = candidate.template();
        SyncExecution execution = createExecution(task, SyncTaskState.RUNNING, request.getActorId(),
                "EXECUTOR_CLAIM:" + request.getExecutorId());
        LocalDateTime leaseExpireAt = computeLeaseExpireAt();

        execution.setExecutorId(request.getExecutorId());
        execution.setHeartbeatAt(LocalDateTime.now());
        execution.setLeaseExpireAt(leaseExpireAt);
        syncExecutionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.RUNNING.name());
        task.setLastExecutionId(execution.getId());
        task.setCurrentExecutorId(request.getExecutorId());
        task.setDispatchLeaseExpireAt(leaseExpireAt);
        task.setQueuedAt(null);
        task.setLatestErrorSummary(null);
        task.setOperatorAttentionRequired(false);
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.CLAIM_TASK, request.getActorId(), request.getActorRole(),
                buildPayload("executorId", request.getExecutorId(), "leaseExpireAt", leaseExpireAt, "executionId", execution.getId()));
        return buildClaimResult(task, template, execution, leaseExpireAt, request.getExecutorId());
    }

    @Override
    @Transactional
    public SyncTask heartbeatExecution(SyncExecutorHeartbeatRequest request) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getActorId())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .build(),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.HEARTBEAT);
        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        if (!actorRole.canReportExecutionHeartbeat()) {
            throw new IllegalStateException("当前角色无执行器心跳上报权限: " + actorRole.name());
        }

        SyncTask task = getRequiredTask(request.getTaskId());
        assertStateIn(task, SyncTaskState.RUNNING, SyncTaskState.RETRYING);
        SyncExecution execution = getRequiredExecution(task, request.getExecutionId());
        if (execution.getExecutorId() == null || !execution.getExecutorId().equals(request.getExecutorId())) {
            throw new IllegalStateException("当前执行记录不属于该执行器实例，禁止续租");
        }

        LocalDateTime leaseExpireAt = computeLeaseExpireAt();
        execution.setHeartbeatAt(LocalDateTime.now());
        execution.setLeaseExpireAt(leaseExpireAt);
        syncExecutionMapper.updateById(execution);

        task.setCurrentExecutorId(request.getExecutorId());
        task.setDispatchLeaseExpireAt(leaseExpireAt);
        updateById(task);
        return task;
    }

    /**
     * 过期租约恢复是执行器认领能力落地后必须补上的平台自愈能力。
     * 如果只有认领和心跳，没有恢复机制，那么执行器失联后任务会长期卡在 RUNNING/RETRYING，
     * 既误导运维判断，也会持续占住租户或数据源的并发槽位。
     */
    @Override
    @Transactional
    public SyncLeaseRecoveryResult recoverExpiredLeases(SyncActionRequest request) {
        return recoverExpiredLeasesInternal(request.getActorId(), request.getActorRole(), request.getNote(), true);
    }

    @Override
    @Transactional
    public SyncTask pause(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.RUNNING, SyncTaskState.RETRYING);
        SyncExecution execution = getLatestExecution(task);

        execution.setState(SyncTaskState.PAUSED.name());
        execution.setLeaseExpireAt(null);
        syncExecutionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.PAUSED.name());
        clearDispatchAssignment(task);
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.PAUSE_TASK, request.getActorId(), request.getActorRole(),
                buildPayload("executionId", execution.getId(), "note", request.getNote()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask resume(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.PAUSED);
        ensureQueueCapacity(task);
        markTaskQueued(task, request.getActorId(), request.getNote());
        updateById(task);

        recordAudit(task, task.getLastExecutionId(), SyncAuditAction.RESUME_TASK, request.getActorId(), request.getActorRole(),
                buildPayload("executionId", task.getLastExecutionId(), "note", request.getNote()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask retry(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.FAILED, SyncTaskState.PARTIALLY_SUCCEEDED);
        if (task.getRetryCount() >= task.getMaxRetryCount()) {
            throw new IllegalStateException("任务已达到最大重试次数上限");
        }
        return doRetry(task, request.getActorId(), request.getActorRole(), request.getNote(), SyncAuditAction.RETRY_TASK);
    }

    @Override
    @Transactional
    public SyncTask cancel(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.PENDING_APPROVAL, SyncTaskState.SCHEDULED,
                SyncTaskState.QUEUED, SyncTaskState.RUNNING, SyncTaskState.PAUSED,
                SyncTaskState.RETRYING, SyncTaskState.PARTIALLY_SUCCEEDED);

        SyncExecution execution = getLastExecutionIfPresent(task);
        if (execution != null
                && !SyncTaskState.SUCCEEDED.name().equals(execution.getState())
                && !SyncTaskState.FAILED.name().equals(execution.getState())
                && !SyncTaskState.CANCELLED.name().equals(execution.getState())) {
            execution.setState(SyncTaskState.CANCELLED.name());
            execution.setFinishedAt(LocalDateTime.now());
            execution.setLeaseExpireAt(null);
            syncExecutionMapper.updateById(execution);
        }

        task.setCurrentState(SyncTaskState.CANCELLED.name());
        clearDispatchAssignment(task);
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution == null ? null : execution.getId(), SyncAuditAction.CANCEL_TASK,
                request.getActorId(), request.getActorRole(),
                buildPayload("executionId", execution == null ? "" : execution.getId(), "note", request.getNote()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask forceRetry(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertAdminRole(request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.FAILED, SyncTaskState.CANCELLED, SyncTaskState.PARTIALLY_SUCCEEDED, SyncTaskState.PAUSED);
        return doRetry(task, request.getActorId(), request.getActorRole(), request.getNote(), SyncAuditAction.FORCE_RETRY_TASK);
    }

    @Override
    @Transactional
    public SyncTask forceCancel(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertAdminRole(request.getActorRole(), request.getActorTenantId());
        if (SyncTaskState.ARCHIVED.name().equals(task.getCurrentState())) {
            throw new IllegalStateException("归档任务不允许再执行强制取消");
        }

        SyncExecution execution = getLastExecutionIfPresent(task);
        if (execution != null
                && !SyncTaskState.SUCCEEDED.name().equals(execution.getState())
                && !SyncTaskState.FAILED.name().equals(execution.getState())
                && !SyncTaskState.CANCELLED.name().equals(execution.getState())) {
            execution.setState(SyncTaskState.CANCELLED.name());
            execution.setFinishedAt(LocalDateTime.now());
            execution.setLeaseExpireAt(null);
            syncExecutionMapper.updateById(execution);
        }

        task.setCurrentState(SyncTaskState.CANCELLED.name());
        clearDispatchAssignment(task);
        task.setOperatorAttentionRequired(true);
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution == null ? null : execution.getId(), SyncAuditAction.FORCE_CANCEL_TASK,
                request.getActorId(), request.getActorRole(),
                buildPayload("executionId", execution == null ? "" : execution.getId(), "note", request.getNote()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask overridePriority(Long id, SyncPriorityOverrideRequest request) {
        SyncTask task = getRequiredTask(id);
        assertAdminRole(request.getActorRole(), request.getActorTenantId());

        task.setPriority(PriorityLevel.fromValue(request.getPriority()).name());
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, null, SyncAuditAction.OVERRIDE_PRIORITY, request.getActorId(), request.getActorRole(),
                buildPayload("priority", task.getPriority(), "note", request.getNote()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask overrideTimeout(Long id, SyncTimeoutOverrideRequest request) {
        SyncTask task = getRequiredTask(id);
        assertAdminRole(request.getActorRole(), request.getActorTenantId());

        task.setTimeoutSeconds(request.getTimeoutSeconds());
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, null, SyncAuditAction.OVERRIDE_TIMEOUT, request.getActorId(), request.getActorRole(),
                buildPayload("timeoutSeconds", request.getTimeoutSeconds(), "note", request.getNote()));
        return task;
    }

    /**
     * 进度回写接口主要给未来的执行器或调度器使用。
     * 当前阶段虽然执行器还未真正落地，但先把回写协议和持久化逻辑做出来，
     * 后续接入异步执行器时就不需要再推翻控制面契约。
     */
    @Override
    @Transactional(readOnly = true)
    public SyncQueueHealthSnapshot inspectQueueHealth(SyncActionRequest request) {
        assertQueueHealthPermission(request.getActorRole(), request.getActorTenantId());

        LocalDateTime now = LocalDateTime.now();
        int agingThresholdSeconds = resolveQueuedTaskAgingThresholdSeconds();
        int queueAlertThresholdGlobal = resolveQueueAlertThresholdGlobal();
        int queueAlertThresholdPerTenant = resolveQueueAlertThresholdPerTenant();

        List<SyncTask> queuedTasks = list(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .orderByAsc(SyncTask::getQueuedAt)
                .orderByAsc(SyncTask::getCreateTime));

        long globalQueuedCount = queuedTasks.size();
        Map<Long, Long> tenantQueuedCountMap = summarizeTenantTaskCount(queuedTasks);

        Long highestBacklogTenantId = null;
        long highestTenantQueuedCount = 0L;
        for (Map.Entry<Long, Long> entry : tenantQueuedCountMap.entrySet()) {
            Long tenantBucket = entry.getKey();
            if (tenantBucket == null || tenantBucket < 0) {
                continue;
            }
            if (entry.getValue() > highestTenantQueuedCount) {
                highestBacklogTenantId = tenantBucket;
                highestTenantQueuedCount = entry.getValue();
            }
        }

        SyncTask oldestQueuedTask = queuedTasks.isEmpty() ? null : queuedTasks.get(0);
        long agedQueuedTaskCount = queuedTasks.stream()
                .filter(task -> isQueuedTaskAged(task, now, agingThresholdSeconds))
                .count();

        int maxQueuedTasksGlobal = syncExecutorProperties.getMaxQueuedTasksGlobal() == null
                ? 0
                : syncExecutorProperties.getMaxQueuedTasksGlobal();
        int maxQueuedTasksPerTenant = syncExecutorProperties.getMaxQueuedTasksPerTenant() == null
                ? 0
                : syncExecutorProperties.getMaxQueuedTasksPerTenant();

        boolean globalAlertTriggered = queueAlertThresholdGlobal > 0 && globalQueuedCount >= queueAlertThresholdGlobal;
        boolean tenantAlertTriggered = queueAlertThresholdPerTenant > 0
                && highestTenantQueuedCount >= queueAlertThresholdPerTenant;
        boolean globalSaturated = maxQueuedTasksGlobal > 0 && globalQueuedCount >= maxQueuedTasksGlobal;
        boolean tenantSaturated = maxQueuedTasksPerTenant > 0 && highestTenantQueuedCount >= maxQueuedTasksPerTenant;

        SyncQueueHealthSnapshot snapshot = new SyncQueueHealthSnapshot();
        snapshot.setGlobalQueuedCount(globalQueuedCount);
        snapshot.setMaxQueuedTasksGlobal(maxQueuedTasksGlobal);
        snapshot.setQueueAlertThresholdGlobal(queueAlertThresholdGlobal);
        snapshot.setHighestBacklogTenantId(highestBacklogTenantId);
        snapshot.setHighestBacklogTenantQueuedCount(highestTenantQueuedCount);
        snapshot.setMaxQueuedTasksPerTenant(maxQueuedTasksPerTenant);
        snapshot.setQueueAlertThresholdPerTenant(queueAlertThresholdPerTenant);
        snapshot.setAgedQueuedTaskCount(agedQueuedTaskCount);
        snapshot.setOldestQueuedTaskId(oldestQueuedTask == null ? null : oldestQueuedTask.getId());
        snapshot.setOldestQueuedAt(oldestQueuedTask == null ? null : oldestQueuedTask.getQueuedAt());
        snapshot.setOldestQueuedDurationSeconds(computeQueuedDurationSeconds(oldestQueuedTask, now));
        snapshot.setPressureLevel(resolveQueuePressureLevel(globalAlertTriggered, tenantAlertTriggered,
                globalSaturated, tenantSaturated, agedQueuedTaskCount));
        snapshot.setAttentionRequired(globalAlertTriggered || tenantAlertTriggered
                || globalSaturated || tenantSaturated || agedQueuedTaskCount > 0);
        snapshot.setRecommendation(buildQueueHealthRecommendation(globalAlertTriggered, tenantAlertTriggered,
                globalSaturated, tenantSaturated, agedQueuedTaskCount,
                snapshot.getOldestQueuedDurationSeconds(), highestBacklogTenantId));
        openQueueHealthAlerts(snapshot, request.getActorId(), request.getActorRole(), highestBacklogTenantId);

        SyncTask auditAnchor = oldestQueuedTask == null ? findMostRecentlyCreatedTask() : oldestQueuedTask;
        if (auditAnchor != null) {
            recordAudit(auditAnchor, auditAnchor.getLastExecutionId(), SyncAuditAction.INSPECT_QUEUE_HEALTH,
                    request.getActorId(), request.getActorRole(),
                    buildPayload(
                            "globalQueuedCount", globalQueuedCount,
                            "highestBacklogTenantId", highestBacklogTenantId,
                            "highestTenantQueuedCount", highestTenantQueuedCount,
                            "agedQueuedTaskCount", agedQueuedTaskCount,
                            "pressureLevel", snapshot.getPressureLevel()
                    ));
        }
        return snapshot;
    }

    @Override
    @Transactional
    public SyncQueueAgingScanResult scanQueuedTaskAging(SyncActionRequest request) {
        assertQueueAgingPermission(request.getActorRole(), request.getActorTenantId());

        LocalDateTime now = LocalDateTime.now();
        int thresholdSeconds = resolveQueuedTaskAgingThresholdSeconds();
        int scanLimit = resolveQueuedTaskAgingScanLimit();
        LocalDateTime agingDeadline = now.minusSeconds(thresholdSeconds);

        long queuedTaskCount = count(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name()));
        List<SyncTask> agedTasks = list(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .isNotNull(SyncTask::getQueuedAt)
                .lt(SyncTask::getQueuedAt, agingDeadline)
                .orderByAsc(SyncTask::getQueuedAt)
                .orderByAsc(SyncTask::getCreateTime)
                .last("LIMIT " + scanLimit));

        int markedAttentionTaskCount = 0;
        List<Long> taskIds = new ArrayList<>();
        for (SyncTask task : agedTasks) {
            boolean newlyMarked = !Boolean.TRUE.equals(task.getOperatorAttentionRequired());
            task.setOperatorAttentionRequired(true);
            task.setIncidentNote(buildQueueAgingIncidentNote(task, now, thresholdSeconds, request.getNote()));
            task.setUpdatedBy(request.getActorId());
            updateById(task);

            if (newlyMarked) {
                markedAttentionTaskCount++;
            }
            taskIds.add(task.getId());

            recordAudit(task, task.getLastExecutionId(), SyncAuditAction.SCAN_QUEUE_AGING,
                    request.getActorId(), request.getActorRole(),
                    buildPayload(
                            "queuedAt", task.getQueuedAt(),
                            "queuedDurationSeconds", computeQueuedDurationSeconds(task, now),
                            "thresholdSeconds", thresholdSeconds,
                            "newlyMarked", newlyMarked,
                            "note", request.getNote()
                    ));
        }

        SyncQueueAgingScanResult result = new SyncQueueAgingScanResult();
        result.setQueuedTaskCount(queuedTaskCount);
        result.setScanLimit(scanLimit);
        result.setAgedQueuedTaskCount(agedTasks.size());
        result.setMarkedAttentionTaskCount(markedAttentionTaskCount);
        result.setThresholdSeconds(thresholdSeconds);
        result.setOldestAgedQueuedAt(agedTasks.isEmpty() ? null : agedTasks.get(0).getQueuedAt());
        result.setTaskIds(taskIds);
        result.setAlertSuggested(agedTasks.size() >= Math.max(3, scanLimit / 5));
        for (SyncTask task : agedTasks) {
            openQueueAgingAlert(task, now, thresholdSeconds, request);
        }
        return result;
    }

    @Override
    @Transactional
    public SyncTask reportProgress(Long id, SyncProgressRequest request) {
        assertExecutionProgressPermission(request.getActorRole(), request.getActorId(), request.getActorTenantId(), getRequiredTask(id));
        SyncTask task = getRequiredTask(id);
        assertStateIn(task, SyncTaskState.RUNNING, SyncTaskState.RETRYING);
        SyncExecution execution = getRequiredExecution(task, request.getExecutionId());

        execution.setRecordsRead(request.getRecordsRead());
        execution.setRecordsWritten(request.getRecordsWritten());
        execution.setFailedRecordCount(request.getFailedRecordCount());
        execution.setErrorSummary(truncate(request.getErrorSummary()));

        if (request.getCheckpointValue() != null && !request.getCheckpointValue().isBlank()) {
            execution.setCheckpointRef(request.getCheckpointType() + ":" + truncate(request.getCheckpointValue()));
            upsertCheckpoint(execution.getId(), request.getCheckpointType(), request.getCheckpointValue(), request.getShardOrPartition());
            recordAudit(task, execution.getId(), SyncAuditAction.SAVE_CHECKPOINT, request.getActorId(), request.getActorRole(),
                    buildPayload("checkpointType", request.getCheckpointType(), "shardOrPartition", request.getShardOrPartition()));
        }
        syncExecutionMapper.updateById(execution);

        if (request.getFailedRecordCount() > 0) {
            task.setOperatorAttentionRequired(true);
        }
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.UPDATE_PROGRESS, request.getActorId(), request.getActorRole(),
                buildPayload(
                        "recordsRead", request.getRecordsRead(),
                        "recordsWritten", request.getRecordsWritten(),
                        "failedRecordCount", request.getFailedRecordCount()
                ));
        return task;
    }

    @Override
    @Transactional
    public SyncTask completeExecution(Long id, SyncCompleteRequest request) {
        assertExecutionResultPermission(request.getActorRole(), request.getActorId(), request.getActorTenantId(), getRequiredTask(id));
        SyncTask task = getRequiredTask(id);
        SyncExecution execution = getRequiredExecution(task, request.getExecutionId());

        execution.setRecordsRead(request.getRecordsRead());
        execution.setRecordsWritten(request.getRecordsWritten());
        execution.setFailedRecordCount(request.getFailedRecordCount());
        execution.setErrorSummary(truncate(request.getSummary()));
        execution.setFinishedAt(LocalDateTime.now());
        execution.setLeaseExpireAt(null);

        SyncTaskState finalState = request.getFailedRecordCount() > 0
                ? SyncTaskState.PARTIALLY_SUCCEEDED
                : SyncTaskState.SUCCEEDED;
        execution.setState(finalState.name());
        syncExecutionMapper.updateById(execution);

        task.setCurrentState(finalState.name());
        clearDispatchAssignment(task);
        task.setLatestErrorSummary(request.getFailedRecordCount() > 0 ? truncate(request.getSummary()) : null);
        task.setOperatorAttentionRequired(request.getFailedRecordCount() > 0);
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.COMPLETE_EXECUTION, request.getActorId(), request.getActorRole(),
                buildPayload("finalState", finalState.name(), "summary", request.getSummary()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask failExecution(Long id, SyncFailRequest request) {
        assertExecutionResultPermission(request.getActorRole(), request.getActorId(), request.getActorTenantId(), getRequiredTask(id));
        SyncTask task = getRequiredTask(id);
        SyncExecution execution = getRequiredExecution(task, request.getExecutionId());

        execution.setState(SyncTaskState.FAILED.name());
        execution.setFailedRecordCount(request.getFailedRecordCount());
        execution.setErrorSummary(truncate(request.getErrorSummary()));
        execution.setFinishedAt(LocalDateTime.now());
        execution.setLeaseExpireAt(null);
        syncExecutionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.FAILED.name());
        clearDispatchAssignment(task);
        task.setLatestErrorSummary(truncate(request.getErrorSummary()));
        task.setOperatorAttentionRequired(true);
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.FAIL_EXECUTION, request.getActorId(), request.getActorRole(),
                buildPayload("failedRecordCount", request.getFailedRecordCount(), "errorSummary", request.getErrorSummary()));
        return task;
    }

    @Override
    @Transactional
    public SyncTask archive(Long id, SyncActionRequest request) {
        SyncTask task = getRequiredTask(id);
        assertAdminRole(request.getActorRole(), request.getActorTenantId());
        assertStateIn(task, SyncTaskState.SUCCEEDED, SyncTaskState.FAILED,
                SyncTaskState.CANCELLED, SyncTaskState.PARTIALLY_SUCCEEDED);

        task.setCurrentState(SyncTaskState.ARCHIVED.name());
        clearDispatchAssignment(task);
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        updateById(task);

        recordAudit(task, task.getLastExecutionId(), SyncAuditAction.ARCHIVE_TASK, request.getActorId(), request.getActorRole(),
                buildPayload("note", request.getNote()));
        return task;
    }

    @Override
    public List<SyncExecution> listExecutions(Long taskId) {
        getRequiredTask(taskId);
        return syncExecutionMapper.selectList(new LambdaQueryWrapper<SyncExecution>()
                .eq(SyncExecution::getSyncTaskId, taskId)
                .orderByDesc(SyncExecution::getCreateTime));
    }

    @Override
    public List<SyncCheckpoint> listCheckpoints(Long taskId) {
        getRequiredTask(taskId);
        List<Long> executionIds = listExecutions(taskId).stream()
                .map(SyncExecution::getId)
                .toList();
        if (executionIds.isEmpty()) {
            return List.of();
        }
        return syncCheckpointMapper.selectList(new LambdaQueryWrapper<SyncCheckpoint>()
                .in(SyncCheckpoint::getExecutionId, executionIds)
                .orderByDesc(SyncCheckpoint::getUpdateTime));
    }

    @Override
    public List<SyncAuditRecord> listAuditRecords(Long taskId) {
        getRequiredTask(taskId);
        return syncAuditRecordMapper.selectList(new LambdaQueryWrapper<SyncAuditRecord>()
                .eq(SyncAuditRecord::getSyncTaskId, taskId)
                .orderByDesc(SyncAuditRecord::getCreateTime));
    }

    /**
     * 重试逻辑统一收口在这里，是为了让常规重试和管理员强制重试共用同一套执行记录生成逻辑。
     */
    private SyncTask doRetry(SyncTask task, Long actorId, String actorRole, String note, SyncAuditAction action) {
        ensureTaskEnabled(task);
        getRequiredEnabledTemplate(task.getTemplateId());

        task.setRetryCount(task.getRetryCount() + 1);
        task.setCurrentState(SyncTaskState.RETRYING.name());
        task.setLatestErrorSummary(null);
        task.setOperatorAttentionRequired(false);
        task.setIncidentNote(truncate(note));
        task.setUpdatedBy(actorId);

        SyncExecution execution = createExecution(task, SyncTaskState.RETRYING, actorId, note);
        task.setLastExecutionId(execution.getId());
        updateById(task);

        recordAudit(task, execution.getId(), action, actorId, actorRole,
                buildPayload("retryCount", task.getRetryCount(), "note", note));
        return task;
    }

    /**
     * 统一的入队逻辑。
     * 这里把队列相关字段集中维护，避免 enqueue、resume 等入口各写一套队列赋值代码。
     */
    private void markTaskQueued(SyncTask task, Long actorId, String note) {
        task.setCurrentState(SyncTaskState.QUEUED.name());
        task.setQueuedAt(LocalDateTime.now());
        task.setQueueAttemptCount((task.getQueueAttemptCount() == null ? 0 : task.getQueueAttemptCount()) + 1);
        clearDispatchAssignment(task);
        task.setIncidentNote(truncate(note));
        task.setUpdatedBy(actorId);
    }

    /**
     * 创建执行记录。
     * 当前阶段控制面直接创建执行记录，是为了让“任务已开始运行/重试”的事实具备可追踪落库记录。
     * 后续接入真实执行器后，这个方法仍可复用，只需替换调用方。
     */
    private SyncExecution createExecution(SyncTask task, SyncTaskState state, Long actorId, String triggerReason) {
        SyncExecution execution = new SyncExecution();
        execution.setSyncTaskId(task.getId());
        execution.setExecutionNo(nextExecutionNo(task.getId()));
        execution.setState(state.name());
        execution.setStartedAt(LocalDateTime.now());
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
        execution.setTriggeredBy(actorId);
        execution.setExecutorId(null);
        execution.setHeartbeatAt(null);
        execution.setLeaseExpireAt(null);
        execution.setTriggerReason(truncate(triggerReason));
        syncExecutionMapper.insert(execution);
        return execution;
    }

    private long nextExecutionNo(Long taskId) {
        Long count = syncExecutionMapper.selectCount(new LambdaQueryWrapper<SyncExecution>()
                .eq(SyncExecution::getSyncTaskId, taskId));
        return (count == null ? 0L : count) + 1L;
    }

    /**
     * 检查点采用按 executionId + shardOrPartition 的方式做简化 upsert。
     * 这样既能支持单分片任务，也能为后续并行分片恢复留出空间。
     */
    private void upsertCheckpoint(Long executionId, String checkpointType, String checkpointValue, String shardOrPartition) {
        LambdaQueryWrapper<SyncCheckpoint> wrapper = new LambdaQueryWrapper<SyncCheckpoint>()
                .eq(SyncCheckpoint::getExecutionId, executionId)
                .eq(shardOrPartition != null && !shardOrPartition.isBlank(), SyncCheckpoint::getShardOrPartition, shardOrPartition);
        SyncCheckpoint checkpoint = syncCheckpointMapper.selectOne(wrapper);
        if (checkpoint == null) {
            checkpoint = new SyncCheckpoint();
            checkpoint.setExecutionId(executionId);
            checkpoint.setCheckpointType(truncate(checkpointType));
            checkpoint.setCheckpointValue(truncate(checkpointValue));
            checkpoint.setShardOrPartition(truncate(shardOrPartition));
            syncCheckpointMapper.insert(checkpoint);
            return;
        }

        checkpoint.setCheckpointType(truncate(checkpointType));
        checkpoint.setCheckpointValue(truncate(checkpointValue));
        checkpoint.setShardOrPartition(truncate(shardOrPartition));
        checkpoint.setUpdateTime(LocalDateTime.now());
        syncCheckpointMapper.updateById(checkpoint);
    }

    /**
     * 选出最合适的待认领任务。
     * 当前先采用数据库粗过滤 + Java 侧优先级排序的基线实现：
     * - 数据库负责按状态、租户和启用状态缩小范围；
     * - Java 侧负责按优先级权重、入队时间、计划时间排序。
     *
     * 这种实现适合当前阶段快速落产品语义。
     * 如果后续任务量显著增长，就应继续演进成独立调度器或专门的任务队列表。
     */
    private ClaimCandidate selectNextClaimableTask(SyncExecutorClaimRequest request) {
        int scanLimit = syncExecutorProperties.getClaimScanLimit() == null
                ? 50
                : Math.max(1, syncExecutorProperties.getClaimScanLimit());

        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .eq(SyncTask::getEnabled, true)
                .eq(request.getTenantId() != null, SyncTask::getTenantId, request.getTenantId())
                .orderByAsc(SyncTask::getQueuedAt)
                .orderByAsc(SyncTask::getNextRunAt)
                .orderByAsc(SyncTask::getCreateTime)
                .last("LIMIT " + scanLimit);

        List<SyncTask> candidates = list(wrapper);
        List<SyncTask> activeTasks = listActiveCapacityTasks();
        Map<Long, SyncTemplate> activeTemplateMap = loadTemplateMap(activeTasks);
        List<ClaimCandidate> claimableCandidates = candidates.stream()
                .map(task -> new ClaimCandidate(task, getRequiredEnabledTemplate(task.getTemplateId())))
                .filter(candidate -> matchesClaimRequest(candidate, request))
                .filter(candidate -> !reachesTenantConcurrencyLimit(candidate.task(), activeTasks))
                .filter(candidate -> !reachesDatasourceConcurrencyLimit(candidate.task(), candidate.template(), activeTasks, activeTemplateMap))
                .toList();

        if (claimableCandidates.isEmpty()) {
            return null;
        }

        Map<Long, Long> tenantActiveCountMap = summarizeTenantTaskCount(activeTasks);
        Comparator<ClaimCandidate> claimComparator = buildClaimCandidateComparator(tenantActiveCountMap);
        if (Boolean.TRUE.equals(syncExecutorProperties.getEnableTenantQueueFairness())) {
            return pickTenantFairCandidate(claimableCandidates, claimComparator);
        }

        return claimableCandidates.stream()
                .sorted(claimComparator)
                .findFirst()
                .orElse(null);
    }

    private boolean matchesClaimRequest(ClaimCandidate candidate, SyncExecutorClaimRequest request) {
        if (request.getSupportedRunModes() != null && !request.getSupportedRunModes().isEmpty()) {
            boolean matchedRunMode = request.getSupportedRunModes().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .anyMatch(item -> item.equalsIgnoreCase(candidate.task().getRunMode()));
            if (!matchedRunMode) {
                return false;
            }
        }

        if (request.getSupportedSyncModes() != null && !request.getSupportedSyncModes().isEmpty()) {
            boolean matchedSyncMode = request.getSupportedSyncModes().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .anyMatch(item -> item.equalsIgnoreCase(candidate.template().getSyncMode()));
            if (!matchedSyncMode) {
                return false;
            }
        }
        return true;
    }

    private SyncExecutorClaimResult buildClaimResult(SyncTask task, SyncTemplate template, SyncExecution execution,
                                                     LocalDateTime leaseExpireAt, String executorId) {
        SyncExecutorClaimResult result = new SyncExecutorClaimResult();
        result.setTaskId(task.getId());
        result.setExecutionId(execution.getId());
        result.setTenantId(task.getTenantId());
        result.setTemplateId(template.getId());
        result.setExecutorId(executorId);
        result.setTaskName(task.getName());
        result.setTaskState(task.getCurrentState());
        result.setSyncMode(template.getSyncMode());
        result.setWriteStrategy(template.getWriteStrategy());
        result.setRunMode(task.getRunMode());
        result.setTriggerType(task.getTriggerType());
        result.setTimeoutSeconds(task.getTimeoutSeconds());
        result.setMaxRetryCount(task.getMaxRetryCount());
        result.setQueueAttemptCount(task.getQueueAttemptCount());
        result.setSourceDatasourceId(template.getSourceDatasourceId());
        result.setSourceSchemaName(template.getSourceSchemaName());
        result.setSourceObjectName(template.getSourceObjectName());
        result.setTargetDatasourceId(template.getTargetDatasourceId());
        result.setTargetSchemaName(template.getTargetSchemaName());
        result.setTargetObjectName(template.getTargetObjectName());
        result.setLeaseExpireAt(leaseExpireAt);
        return result;
    }

    /**
     * 统一的租约恢复内部实现。
     * 当前同时服务两类调用方：
     * 1. 管理员显式触发恢复；
     * 2. 执行器认领前的自动清理。
     *
     * 这样可以确保人工恢复和自动恢复遵循同一套状态流转、审计和错误摘要逻辑。
     */
    private SyncLeaseRecoveryResult recoverExpiredLeasesInternal(Long actorId, String actorRole, String note, boolean requireAdmin) {
        if (requireAdmin) {
            assertAdminRole(actorRole, null);
        }

        LocalDateTime now = LocalDateTime.now();
        int batchSize = syncExecutorProperties.getExpiredLeaseRecoveryBatchSize() == null
                ? 50
                : Math.max(1, syncExecutorProperties.getExpiredLeaseRecoveryBatchSize());

        List<SyncTask> expiredTasks = list(new LambdaQueryWrapper<SyncTask>()
                .in(SyncTask::getCurrentState, List.of(SyncTaskState.RUNNING.name(), SyncTaskState.RETRYING.name()))
                .isNotNull(SyncTask::getDispatchLeaseExpireAt)
                .lt(SyncTask::getDispatchLeaseExpireAt, now)
                .orderByAsc(SyncTask::getDispatchLeaseExpireAt)
                .last("LIMIT " + batchSize));

        SyncLeaseRecoveryResult result = new SyncLeaseRecoveryResult();
        result.setExpiredTaskCount(expiredTasks.size());
        result.setRecoveredTaskCount(0);
        result.setRequeuedTaskCount(0);
        result.setFailedTaskCount(0);
        result.setTaskIds(new ArrayList<>());
        result.setExecutionIds(new ArrayList<>());

        if (expiredTasks.isEmpty()) {
            return result;
        }

        boolean autoRequeue = Boolean.TRUE.equals(syncExecutorProperties.getAutoRequeueExpiredLeases());
        for (SyncTask task : expiredTasks) {
            String previousState = task.getCurrentState();
            String previousExecutorId = task.getCurrentExecutorId();
            String recoverySummary = truncate("租约已过期，系统判定执行器可能失联，已触发恢复处理。executorId=" + previousExecutorId);

            SyncExecution execution = getLastExecutionIfPresent(task);
            if (execution != null && execution.getFinishedAt() == null) {
                execution.setState(SyncTaskState.FAILED.name());
                execution.setFinishedAt(now);
                execution.setLeaseExpireAt(null);
                execution.setErrorSummary(recoverySummary);
                syncExecutionMapper.updateById(execution);
                result.getExecutionIds().add(execution.getId());
            }

            QueuePressureSnapshot queuePressure = inspectQueuePressure(task.getTenantId(), task.getId());
            boolean queueHasCapacity = !queuePressure.reachesGlobalLimit() && !queuePressure.reachesTenantLimit();

            if (autoRequeue && queueHasCapacity) {
                markTaskQueued(task, actorId, note == null || note.isBlank()
                        ? "系统自动恢复过期租约并重新入队"
                        : note);
                task.setLatestErrorSummary(recoverySummary);
                task.setOperatorAttentionRequired(true);
                result.setRequeuedTaskCount(result.getRequeuedTaskCount() + 1);
            } else {
                task.setCurrentState(SyncTaskState.FAILED.name());
                task.setQueuedAt(null);
                clearDispatchAssignment(task);
                task.setLatestErrorSummary(autoRequeue && !queueHasCapacity
                        ? truncate(recoverySummary + "；但当前待执行队列已达到保护上限，系统改为标记失败等待人工处理。")
                        : recoverySummary);
                task.setOperatorAttentionRequired(true);
                task.setIncidentNote(truncate(note));
                task.setUpdatedBy(actorId);
                result.setFailedTaskCount(result.getFailedTaskCount() + 1);
            }

            updateById(task);
            result.getTaskIds().add(task.getId());
            result.setRecoveredTaskCount(result.getRecoveredTaskCount() + 1);

            recordAudit(task, execution == null ? null : execution.getId(), SyncAuditAction.RECOVER_EXPIRED_LEASE,
                    actorId, actorRole,
                    buildPayload(
                            "previousState", previousState,
                            "nextState", task.getCurrentState(),
                            "executorId", previousExecutorId,
                            "autoRequeue", autoRequeue,
                            "queueGlobalCount", queuePressure.globalQueuedCount(),
                            "queueTenantCount", queuePressure.tenantQueuedCount(),
                            "note", note
                    ));
        }
        return result;
    }

    /**
     * 手动直跑和后续真正的调度器，都应该复用同一套并发槽位校验。
     * 这样可以避免某个入口绕过保护，导致某个租户或某个数据库被瞬时任务打满。
     */
    /**
     * 入队保护和运行中容量保护是两类不同的问题：
     * 1. 运行中容量关注“正在占资源的任务不能太多”；
     * 2. 入队保护关注“即使执行器异常或上游突发提交，待执行队列也不能无限膨胀”。
     */
    private void ensureQueueCapacity(SyncTask task) {
        QueuePressureSnapshot snapshot = inspectQueuePressure(task.getTenantId(), task.getId());
        if (snapshot.reachesGlobalLimit()) {
            throw new IllegalStateException("当前平台待执行队列已达到全局上限，globalQueuedCount="
                    + snapshot.globalQueuedCount() + ", limit=" + snapshot.maxQueuedTasksGlobal());
        }
        if (snapshot.reachesTenantLimit()) {
            throw new IllegalStateException("当前租户待执行队列已达到上限，tenantId=" + task.getTenantId()
                    + ", tenantQueuedCount=" + snapshot.tenantQueuedCount()
                    + ", limit=" + snapshot.maxQueuedTasksPerTenant());
        }
    }

    private void ensureConcurrencyCapacity(SyncTask task, SyncTemplate template) {
        List<SyncTask> activeTasks = listActiveCapacityTasks();
        if (reachesTenantConcurrencyLimit(task, activeTasks)) {
            int limit = syncExecutorProperties.getMaxRunningTasksPerTenant() == null
                    ? 0
                    : syncExecutorProperties.getMaxRunningTasksPerTenant();
            throw new IllegalStateException("当前租户活跃同步任务已达到并发上限，tenantId=" + task.getTenantId() + ", limit=" + limit);
        }

        Map<Long, SyncTemplate> activeTemplateMap = loadTemplateMap(activeTasks);
        Long saturatedDatasourceId = findSaturatedDatasourceId(task, template, activeTasks, activeTemplateMap);
        if (saturatedDatasourceId != null) {
            int limit = syncExecutorProperties.getMaxRunningTasksPerDatasource() == null
                    ? 0
                    : syncExecutorProperties.getMaxRunningTasksPerDatasource();
            throw new IllegalStateException("数据源活跃同步任务已达到并发上限，datasourceId="
                    + saturatedDatasourceId + ", limit=" + limit);
        }
    }

    /**
     * 当前版本先用“主状态 + 租约是否仍有效”来近似表达活跃资源占用。
     * 这样可以把已经过期但尚未清理的僵尸任务排除出去，避免它们长期卡死新的调度机会。
     */
    private List<SyncTask> listActiveCapacityTasks() {
        return list(new LambdaQueryWrapper<SyncTask>()
                .in(SyncTask::getCurrentState, List.of(SyncTaskState.RUNNING.name(), SyncTaskState.RETRYING.name()))
                .orderByAsc(SyncTask::getTenantId)
                .orderByAsc(SyncTask::getId))
                .stream()
                .filter(this::occupiesConcurrencySlot)
                .toList();
    }

    /**
     * 第一版公平调度不做复杂抢占，而是先给每个租户一个“代表候选”。
     * 这样在同一轮认领中，每个租户至少能有一个候选被看见，
     * 能明显降低扫描窗口里大租户海量任务把小租户全部淹没的风险。
     */
    private ClaimCandidate pickTenantFairCandidate(List<ClaimCandidate> claimableCandidates,
                                                   Comparator<ClaimCandidate> claimComparator) {
        Map<Long, ClaimCandidate> representativeMap = new HashMap<>();
        for (ClaimCandidate candidate : claimableCandidates) {
            Long tenantBucket = resolveTenantBucket(candidate.task());
            ClaimCandidate current = representativeMap.get(tenantBucket);
            if (current == null || claimComparator.compare(candidate, current) < 0) {
                representativeMap.put(tenantBucket, candidate);
            }
        }

        return representativeMap.values().stream()
                .sorted(claimComparator)
                .findFirst()
                .orElse(null);
    }

    /**
     * 认领排序继续保留“优先级优先”的产品语义，
     * 但在同优先级下，会优先让当前活跃任务更少的租户先拿到执行机会，
     * 再结合排队时间与计划时间做稳定排序。
     */
    private Comparator<ClaimCandidate> buildClaimCandidateComparator(Map<Long, Long> tenantActiveCountMap) {
        return Comparator
                .comparingInt((ClaimCandidate candidate) -> priorityWeight(candidate.task().getPriority())).reversed()
                .thenComparingLong(candidate -> tenantActiveCountMap.getOrDefault(resolveTenantBucket(candidate.task()), 0L))
                .thenComparing(candidate -> candidate.task().getQueuedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.task().getNextRunAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.task().getCreateTime(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.task().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private boolean reachesTenantConcurrencyLimit(SyncTask candidate, List<SyncTask> activeTasks) {
        Integer limit = syncExecutorProperties.getMaxRunningTasksPerTenant();
        if (limit == null || limit <= 0 || candidate.getTenantId() == null) {
            return false;
        }

        long activeCount = activeTasks.stream()
                .filter(task -> !task.getId().equals(candidate.getId()))
                .filter(task -> candidate.getTenantId().equals(task.getTenantId()))
                .count();
        return activeCount >= limit;
    }

    private QueuePressureSnapshot inspectQueuePressure(Long tenantId, Long excludedTaskId) {
        Integer maxQueuedTasksGlobal = syncExecutorProperties.getMaxQueuedTasksGlobal();
        Integer maxQueuedTasksPerTenant = syncExecutorProperties.getMaxQueuedTasksPerTenant();

        long globalQueuedCount = count(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .ne(excludedTaskId != null, SyncTask::getId, excludedTaskId));

        long tenantQueuedCount = tenantId == null ? 0L : count(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .eq(SyncTask::getTenantId, tenantId)
                .ne(excludedTaskId != null, SyncTask::getId, excludedTaskId));

        boolean reachesGlobalLimit = maxQueuedTasksGlobal != null
                && maxQueuedTasksGlobal > 0
                && globalQueuedCount >= maxQueuedTasksGlobal;
        boolean reachesTenantLimit = tenantId != null
                && maxQueuedTasksPerTenant != null
                && maxQueuedTasksPerTenant > 0
                && tenantQueuedCount >= maxQueuedTasksPerTenant;

        return new QueuePressureSnapshot(
                globalQueuedCount,
                tenantQueuedCount,
                maxQueuedTasksGlobal == null ? 0 : maxQueuedTasksGlobal,
                maxQueuedTasksPerTenant == null ? 0 : maxQueuedTasksPerTenant,
                reachesGlobalLimit,
                reachesTenantLimit
        );
    }

    private boolean reachesDatasourceConcurrencyLimit(SyncTask candidate, SyncTemplate candidateTemplate,
                                                      List<SyncTask> activeTasks, Map<Long, SyncTemplate> activeTemplateMap) {
        return findSaturatedDatasourceId(candidate, candidateTemplate, activeTasks, activeTemplateMap) != null;
    }

    private Long findSaturatedDatasourceId(SyncTask candidate, SyncTemplate candidateTemplate,
                                           List<SyncTask> activeTasks, Map<Long, SyncTemplate> activeTemplateMap) {
        Integer limit = syncExecutorProperties.getMaxRunningTasksPerDatasource();
        if (limit == null || limit <= 0 || candidateTemplate == null) {
            return null;
        }

        Set<Long> candidateDatasourceIds = new LinkedHashSet<>();
        if (candidateTemplate.getSourceDatasourceId() != null) {
            candidateDatasourceIds.add(candidateTemplate.getSourceDatasourceId());
        }
        if (candidateTemplate.getTargetDatasourceId() != null) {
            candidateDatasourceIds.add(candidateTemplate.getTargetDatasourceId());
        }
        if (candidateDatasourceIds.isEmpty()) {
            return null;
        }

        for (Long datasourceId : candidateDatasourceIds) {
            long activeCount = activeTasks.stream()
                    .filter(task -> !task.getId().equals(candidate.getId()))
                    .filter(task -> taskTouchesDatasource(activeTemplateMap.get(task.getTemplateId()), datasourceId))
                    .count();
            if (activeCount >= limit) {
                return datasourceId;
            }
        }
        return null;
    }

    private Map<Long, SyncTemplate> loadTemplateMap(List<SyncTask> tasks) {
        Set<Long> templateIds = new LinkedHashSet<>();
        for (SyncTask task : tasks) {
            if (task.getTemplateId() != null) {
                templateIds.add(task.getTemplateId());
            }
        }

        Map<Long, SyncTemplate> templateMap = new HashMap<>();
        if (templateIds.isEmpty()) {
            return templateMap;
        }

        List<SyncTemplate> templates = syncTemplateMapper.selectBatchIds(templateIds);
        for (SyncTemplate template : templates) {
            templateMap.put(template.getId(), template);
        }
        return templateMap;
    }

    private Map<Long, Long> summarizeTenantTaskCount(List<SyncTask> tasks) {
        Map<Long, Long> tenantCountMap = new HashMap<>();
        for (SyncTask task : tasks) {
            Long tenantBucket = resolveTenantBucket(task);
            tenantCountMap.put(tenantBucket, tenantCountMap.getOrDefault(tenantBucket, 0L) + 1L);
        }
        return tenantCountMap;
    }

    private Long resolveTenantBucket(SyncTask task) {
        if (task == null) {
            return Long.MIN_VALUE;
        }
        if (task.getTenantId() != null) {
            return task.getTenantId();
        }
        return task.getId() == null ? Long.MIN_VALUE : -task.getId();
    }

    private boolean taskTouchesDatasource(SyncTemplate template, Long datasourceId) {
        if (template == null || datasourceId == null) {
            return false;
        }
        return datasourceId.equals(template.getSourceDatasourceId())
                || datasourceId.equals(template.getTargetDatasourceId());
    }

    /**
     * 排队老化阈值统一从配置解析，避免多个入口各自写默认值。
     */
    private int resolveQueuedTaskAgingThresholdSeconds() {
        return syncExecutorProperties.getQueuedTaskAgingThresholdSeconds() == null
                ? 900
                : Math.max(60, syncExecutorProperties.getQueuedTaskAgingThresholdSeconds());
    }

    /**
     * 队列老化巡检批量大小。
     */
    private int resolveQueuedTaskAgingScanLimit() {
        return syncExecutorProperties.getQueuedTaskAgingScanLimit() == null
                ? 100
                : Math.max(1, syncExecutorProperties.getQueuedTaskAgingScanLimit());
    }

    private int resolveQueueAlertThresholdGlobal() {
        return syncExecutorProperties.getQueueAlertThresholdGlobal() == null
                ? 120
                : Math.max(1, syncExecutorProperties.getQueueAlertThresholdGlobal());
    }

    private int resolveQueueAlertThresholdPerTenant() {
        return syncExecutorProperties.getQueueAlertThresholdPerTenant() == null
                ? 20
                : Math.max(1, syncExecutorProperties.getQueueAlertThresholdPerTenant());
    }

    /**
     * 判断任务是否已经排队过久。
     */
    private boolean isQueuedTaskAged(SyncTask task, LocalDateTime now, int thresholdSeconds) {
        if (task == null || task.getQueuedAt() == null) {
            return false;
        }
        return task.getQueuedAt().isBefore(now.minusSeconds(thresholdSeconds));
    }

    /**
     * 计算任务已经排队了多久。
     */
    private Long computeQueuedDurationSeconds(SyncTask task, LocalDateTime now) {
        if (task == null || task.getQueuedAt() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(task.getQueuedAt(), now).getSeconds());
    }

    /**
     * 解析当前队列压力等级。
     * 当前先用三档：
     * - HEALTHY：未触发明显风险；
     * - WATCH：已出现预警或老化，需要运营关注；
     * - SATURATED：已经触发硬上限或明显接近失控。
     */
    private String resolveQueuePressureLevel(boolean globalAlertTriggered,
                                             boolean tenantAlertTriggered,
                                             boolean globalSaturated,
                                             boolean tenantSaturated,
                                             long agedQueuedTaskCount) {
        if (globalSaturated || tenantSaturated) {
            return "SATURATED";
        }
        if (globalAlertTriggered || tenantAlertTriggered || agedQueuedTaskCount > 0) {
            return "WATCH";
        }
        return "HEALTHY";
    }

    /**
     * 为运营人员生成一个可直接阅读的建议摘要。
     */
    private String buildQueueHealthRecommendation(boolean globalAlertTriggered,
                                                  boolean tenantAlertTriggered,
                                                  boolean globalSaturated,
                                                  boolean tenantSaturated,
                                                  long agedQueuedTaskCount,
                                                  Long oldestQueuedDurationSeconds,
                                                  Long highestBacklogTenantId) {
        if (globalSaturated) {
            return "全局待执行队列已达到容量上限，建议立即检查执行器容量、恢复卡死任务，并临时收紧上游提交节奏。";
        }
        if (tenantSaturated) {
            return "单租户待执行队列已达到上限，建议优先检查该租户的任务洪峰、套餐配额或执行器池隔离策略。";
        }
        if (agedQueuedTaskCount > 0) {
            return "队列中已出现排队老化任务，建议优先处理最老任务，并检查认领公平性、优先级和执行器可用性。";
        }
        if (globalAlertTriggered) {
            return "全局队列已进入预警区间，建议提前观察执行器吞吐和租约恢复情况，避免进一步积压。";
        }
        if (tenantAlertTriggered) {
            return "存在租户积压明显偏高，建议关注 tenantId=" + highestBacklogTenantId + " 的提交模式与容量配置。";
        }
        if (oldestQueuedDurationSeconds != null && oldestQueuedDurationSeconds > 0) {
            return "当前队列整体可控，但建议继续观察最老任务等待时长，防止由局部慢任务演变成系统性积压。";
        }
        return "当前队列压力健康，暂未发现明显积压或老化风险。";
    }

    /**
     * 为老化任务生成统一的人工关注说明。
     */
    private String buildQueueAgingIncidentNote(SyncTask task, LocalDateTime now, int thresholdSeconds, String note) {
        String baseNote = "任务排队超过 " + thresholdSeconds + " 秒，已被队列老化巡检标记为需要人工关注";
        Long queuedDurationSeconds = computeQueuedDurationSeconds(task, now);
        String durationPart = queuedDurationSeconds == null ? "" : "，当前已排队约 " + queuedDurationSeconds + " 秒";
        if (note == null || note.isBlank()) {
            return truncate(baseNote + durationPart + "。");
        }
        return truncate(baseNote + durationPart + "。补充说明：" + note);
    }

    /**
     * 选择一个最近创建的任务作为平台级审计锚点。
     * 当前审计表仍然是任务域表结构，因此平台级观察动作先借助一个任务锚点沉淀轨迹。
     */
    private SyncTask findMostRecentlyCreatedTask() {
        return getOne(new LambdaQueryWrapper<SyncTask>()
                .orderByDesc(SyncTask::getCreateTime)
                .last("LIMIT 1"), false);
    }

    private LocalDateTime computeLeaseExpireAt() {
        int leaseDurationSeconds = syncExecutorProperties.getLeaseDurationSeconds() == null
                ? 120
                : Math.max(10, syncExecutorProperties.getLeaseDurationSeconds());
        return LocalDateTime.now().plusSeconds(leaseDurationSeconds);
    }

    private boolean occupiesConcurrencySlot(SyncTask task) {
        if (task == null) {
            return false;
        }
        SyncTaskState state = SyncTaskState.fromValue(task.getCurrentState());
        if (state != SyncTaskState.RUNNING && state != SyncTaskState.RETRYING) {
            return false;
        }
        LocalDateTime leaseExpireAt = task.getDispatchLeaseExpireAt();
        return leaseExpireAt == null || leaseExpireAt.isAfter(LocalDateTime.now());
    }

    private void clearDispatchAssignment(SyncTask task) {
        task.setCurrentExecutorId(null);
        task.setDispatchLeaseExpireAt(null);
    }

    private int priorityWeight(String priority) {
        if (priority == null || priority.isBlank()) {
            return 0;
        }
        return switch (PriorityLevel.fromValue(priority)) {
            case URGENT -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    private SyncTask getRequiredTask(Long id) {
        SyncTask task = getById(id);
        if (task == null) {
            throw new NoSuchElementException("同步任务不存在: " + id);
        }
        return task;
    }

    private SyncExecution getRequiredExecution(SyncTask task, Long executionId) {
        SyncExecution execution = syncExecutionMapper.selectById(executionId);
        if (execution == null || !task.getId().equals(execution.getSyncTaskId())) {
            throw new NoSuchElementException("执行记录不存在或不属于当前任务: " + executionId);
        }
        return execution;
    }

    private SyncExecution getLatestExecution(SyncTask task) {
        if (task.getLastExecutionId() == null) {
            throw new IllegalStateException("当前任务没有可操作的执行记录");
        }
        return getRequiredExecution(task, task.getLastExecutionId());
    }

    private SyncExecution getLastExecutionIfPresent(SyncTask task) {
        return task.getLastExecutionId() == null ? null : syncExecutionMapper.selectById(task.getLastExecutionId());
    }

    private SyncTemplate getRequiredEnabledTemplate(Long templateId) {
        SyncTemplate template = syncTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new NoSuchElementException("同步模板不存在: " + templateId);
        }
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new IllegalStateException("同步模板当前未启用: " + templateId);
        }
        return template;
    }

    private void ensureTaskNameUnique(Long tenantId, String name, Long currentId) {
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTenantId, tenantId)
                .eq(SyncTask::getName, name)
                .ne(currentId != null, SyncTask::getId, currentId)
                .ne(SyncTask::getCurrentState, SyncTaskState.ARCHIVED.name());
        if (count(wrapper) > 0) {
            throw new IllegalArgumentException("同步任务名称已存在: " + name);
        }
    }

    /**
     * 任务创建权限检查。
     * 这里把“是否能创建任务”和“是否能替别人创建任务”拆开处理：
     * - 项目负责人允许创建，但默认只能给自己创建、自己负责；
     * - 运营和管理员允许代建，以覆盖更真实的运营场景。
     */
    private void assertTaskCreationPermission(CreateSyncTaskRequest request) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getCreatedBy())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(request.getTenantId())
                        .resourceOwnerId(request.getOwnerId())
                        .resourceCreatedBy(request.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.CREATE);
        ActorRole role = ActorRole.fromValue(request.getActorRole());
        if (!role.canCreateSyncTasks()) {
            throw new IllegalStateException("当前角色无创建同步任务权限: " + role.name());
        }
        if (role.canOperateOwnedSyncTasks() && !request.getCreatedBy().equals(request.getOwnerId())) {
            throw new IllegalStateException("项目负责人创建任务时，创建人与负责人必须保持一致");
        }
    }

    /**
     * 任务更新权限检查。
     * 当前策略按“拥有者可改自己，运营和管理员可跨任务治理”来收敛。
     */
    private void assertTaskUpdatePermission(SyncTask task, UpdateSyncTaskRequest request) {
        SyncPermissionContext context = SyncPermissionContext.builder()
                .actorId(request.getUpdatedBy())
                .actorRole(request.getActorRole())
                .actorTenantId(request.getActorTenantId())
                .resourceTenantId(task.getTenantId())
                .resourceOwnerId(task.getOwnerId())
                .resourceCreatedBy(task.getCreatedBy())
                .build();
        if (syncPermissionEvaluator.canAccess(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.UPDATE_ANY)) {
            return;
        }
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.UPDATE_OWNED);
        ActorRole role = ActorRole.fromValue(request.getActorRole());
        if (role.canOperateAnySyncTasks()) {
            return;
        }
        if (role.canOperateOwnedSyncTasks() && isTaskOwnedByActor(task, request.getUpdatedBy())) {
            if (!request.getUpdatedBy().equals(request.getOwnerId())) {
                throw new IllegalStateException("项目负责人更新任务时不允许把负责人改成其他人");
            }
            return;
        }
        throw new IllegalStateException("当前角色无更新该同步任务权限: " + role.name());
    }

    /**
     * 普通任务动作权限检查。
     * 提交审批、调度、入队、运行、暂停、恢复、重试、取消这些动作都走这里，
     * 避免某个入口绕过本地权限矩阵。
     */
    private void assertTaskOperationPermission(SyncTask task, Long actorId, String actorRole, Long actorTenantId) {
        SyncPermissionContext context = SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(task.getTenantId())
                .resourceOwnerId(task.getOwnerId())
                .resourceCreatedBy(task.getCreatedBy())
                .build();
        if (syncPermissionEvaluator.canAccess(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.OPERATE_ANY)) {
            return;
        }
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.OPERATE_OWNED);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role.canOperateAnySyncTasks()) {
            return;
        }
        if (role.canOperateOwnedSyncTasks() && isTaskOwnedByActor(task, actorId)) {
            return;
        }
        throw new IllegalStateException("当前角色无操作该同步任务权限: " + role.name());
    }

    private void ensureApprovalReady(SyncTask task) {
        if (ApprovalState.PENDING.name().equals(task.getApprovalState())) {
            throw new IllegalStateException("当前任务尚未审批通过，不能进入调度或执行");
        }
        if (ApprovalState.REJECTED.name().equals(task.getApprovalState())) {
            throw new IllegalStateException("当前任务审批已被驳回，请先调整配置后重新提交审批");
        }
    }

    private void ensureTaskEnabled(SyncTask task) {
        if (!Boolean.TRUE.equals(task.getEnabled())) {
            throw new IllegalStateException("当前任务未启用，不允许执行");
        }
    }

    /**
     * 执行进度权限检查。
     * 这类接口属于执行器平面，不应该被普通项目角色随意调用。
     */
    private void assertExecutionProgressPermission(String actorRole, Long actorId, Long actorTenantId, SyncTask task) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(task.getTenantId())
                        .resourceOwnerId(task.getOwnerId())
                        .resourceCreatedBy(task.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.REPORT_PROGRESS);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canReportExecutionProgress()) {
            throw new IllegalStateException("当前角色无执行进度回写权限: " + role.name());
        }
    }

    /**
     * 执行结果权限检查。
     * 完成和失败回写都会改变任务主状态，因此单独保留一个检查方法，便于后续细化权限。
     */
    private void assertExecutionResultPermission(String actorRole, Long actorId, Long actorTenantId, SyncTask task) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(task.getTenantId())
                        .resourceOwnerId(task.getOwnerId())
                        .resourceCreatedBy(task.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.REPORT_RESULT);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canReportExecutionResult()) {
            throw new IllegalStateException("当前角色无执行结果回写权限: " + role.name());
        }
    }

    /**
     * 队列健康查看权限检查。
     */
    private void assertQueueHealthPermission(String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .build(),
                SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.VIEW_QUEUE_HEALTH);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canInspectQueueHealth()) {
            throw new IllegalStateException("当前角色无查看队列健康权限: " + role.name());
        }
    }

    /**
     * 队列老化巡检权限检查。
     */
    private void assertQueueAgingPermission(String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .build(),
                SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.SCAN_QUEUE_AGING);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canScanQueueAging()) {
            throw new IllegalStateException("当前角色无执行队列老化巡检权限: " + role.name());
        }
    }

    /**
     * 判断任务是否属于当前操作者。
     * 这里同时接受“负责人”和“创建人”命中，是因为很多平台任务会存在
     * “由某人创建并由自己负责”的组合场景。
     */
    /**
     * 队列健康巡检不应该只返回一个瞬时快照。
     * 更接近商用治理平台的做法，是把“已经发现的风险”沉淀为可追踪的治理告警对象，
     * 这样后续才能进行确认、派单、外送和闭环处理。
     */
    private void openQueueHealthAlerts(SyncQueueHealthSnapshot snapshot,
                                       Long actorId,
                                       String actorRole,
                                       Long highestBacklogTenantId) {
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.getAttentionRequired())) {
            return;
        }

        SyncAlertSeverity severity = "SATURATED".equals(snapshot.getPressureLevel())
                ? SyncAlertSeverity.CRITICAL
                : SyncAlertSeverity.WARNING;

        if (snapshot.getGlobalQueuedCount() != null
                && snapshot.getQueueAlertThresholdGlobal() != null
                && snapshot.getQueueAlertThresholdGlobal() > 0
                && snapshot.getGlobalQueuedCount() >= snapshot.getQueueAlertThresholdGlobal()) {
            syncGovernanceAlertService.openOrRefreshAlert(
                    null,
                    snapshot.getOldestQueuedTaskId(),
                    SyncAlertType.QUEUE_PRESSURE.name(),
                    severity.name(),
                    "QUEUE_PRESSURE:GLOBAL",
                    "全局同步队列进入压力区间",
                    "当前全局待执行任务数=" + snapshot.getGlobalQueuedCount()
                            + "，预警阈值=" + snapshot.getQueueAlertThresholdGlobal()
                            + "，压力等级=" + snapshot.getPressureLevel()
                            + "，建议=" + snapshot.getRecommendation(),
                    SyncPermissionResource.SYNC_QUEUE.name(),
                    SyncAuditAction.INSPECT_QUEUE_HEALTH.name(),
                    actorId,
                    actorRole
            );
        }

        if (highestBacklogTenantId != null
                && snapshot.getHighestBacklogTenantQueuedCount() != null
                && snapshot.getQueueAlertThresholdPerTenant() != null
                && snapshot.getQueueAlertThresholdPerTenant() > 0
                && snapshot.getHighestBacklogTenantQueuedCount() >= snapshot.getQueueAlertThresholdPerTenant()) {
            syncGovernanceAlertService.openOrRefreshAlert(
                    highestBacklogTenantId,
                    snapshot.getOldestQueuedTaskId(),
                    SyncAlertType.QUEUE_PRESSURE.name(),
                    severity.name(),
                    "QUEUE_PRESSURE:TENANT:" + highestBacklogTenantId,
                    "租户同步队列积压偏高",
                    "tenantId=" + highestBacklogTenantId
                            + " 的待执行任务数=" + snapshot.getHighestBacklogTenantQueuedCount()
                            + "，租户预警阈值=" + snapshot.getQueueAlertThresholdPerTenant()
                            + "，建议=" + snapshot.getRecommendation(),
                    SyncPermissionResource.SYNC_QUEUE.name(),
                    SyncAuditAction.INSPECT_QUEUE_HEALTH.name(),
                    actorId,
                    actorRole
            );
        }

        if (snapshot.getAgedQueuedTaskCount() != null && snapshot.getAgedQueuedTaskCount() > 0) {
            syncGovernanceAlertService.openOrRefreshAlert(
                    null,
                    snapshot.getOldestQueuedTaskId(),
                    SyncAlertType.QUEUE_AGING.name(),
                    SyncAlertSeverity.WARNING.name(),
                    "QUEUE_AGING:GLOBAL",
                    "同步队列中存在老化任务",
                    "当前已识别老化排队任务数=" + snapshot.getAgedQueuedTaskCount()
                            + "，最老任务等待秒数=" + snapshot.getOldestQueuedDurationSeconds()
                            + "，建议=" + snapshot.getRecommendation(),
                    SyncPermissionResource.SYNC_QUEUE.name(),
                    SyncAuditAction.INSPECT_QUEUE_HEALTH.name(),
                    actorId,
                    actorRole
            );
        }
    }

    /**
     * 针对单个老化任务生成细粒度告警，便于后续人工定位“到底是哪一个任务持续卡住了队列”。
     * 这种任务级告警和全局快照告警同时存在时，前者更适合落到具体工单，后者更适合做运营态势看板。
     */
    private SyncGovernanceAlert openQueueAgingAlert(SyncTask task,
                                                    LocalDateTime now,
                                                    int thresholdSeconds,
                                                    SyncActionRequest request) {
        Long queuedDurationSeconds = computeQueuedDurationSeconds(task, now);
        SyncAlertSeverity severity = queuedDurationSeconds != null && queuedDurationSeconds >= thresholdSeconds * 4L
                ? SyncAlertSeverity.CRITICAL
                : SyncAlertSeverity.WARNING;
        return syncGovernanceAlertService.openOrRefreshAlert(
                task.getTenantId(),
                task.getId(),
                SyncAlertType.QUEUE_AGING.name(),
                severity.name(),
                "QUEUE_AGING:TASK:" + task.getId(),
                "同步任务排队时间超过治理阈值",
                "taskId=" + task.getId()
                        + "，tenantId=" + task.getTenantId()
                        + "，queuedDurationSeconds=" + queuedDurationSeconds
                        + "，agingThresholdSeconds=" + thresholdSeconds
                        + "，note=" + request.getNote(),
                SyncPermissionResource.SYNC_QUEUE.name(),
                SyncAuditAction.SCAN_QUEUE_AGING.name(),
                request.getActorId(),
                request.getActorRole()
        );
    }

    private boolean isTaskOwnedByActor(SyncTask task, Long actorId) {
        if (task == null || actorId == null) {
            return false;
        }
        return actorId.equals(task.getOwnerId()) || actorId.equals(task.getCreatedBy());
    }

    private void assertAdminRole(String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .build(),
                SyncPermissionResource.SYNC_ADMIN, SyncPermissionAction.ADMIN_OVERRIDE);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canForceOverride()) {
            throw new IllegalStateException("当前角色无管理员强制控制权限: " + actorRole);
        }
    }

    /**
     * 统一的状态守卫。
     * 把状态前置检查收口在一个方法里，后续扩展状态机时只需要对照这里和引用点一起调整。
     */
    private void assertStateIn(SyncTask task, SyncTaskState... allowedStates) {
        SyncTaskState currentState = SyncTaskState.fromValue(task.getCurrentState());
        boolean matched = Arrays.stream(allowedStates).anyMatch(item -> item == currentState);
        if (!matched) {
            throw new IllegalStateException("任务当前状态不允许此操作，currentState=" + currentState.name());
        }
    }

    private void recordAudit(SyncTask task, Long executionId, SyncAuditAction action,
                             Long actorId, String actorRole, String payload) {
        SyncAuditRecord record = new SyncAuditRecord();
        record.setTenantId(task.getTenantId());
        record.setSyncTaskId(task.getId());
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
            builder.append("\"").append(key).append("\":\"")
                    .append(escape(String.valueOf(value))).append("\"");
        }
        builder.append("}");
        return builder.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    /**
     * 认领候选对象。
     * 之所以把任务和模板打包，是因为认领排序和认领结果构造都会同时依赖两侧信息。
     */
    private record ClaimCandidate(SyncTask task, SyncTemplate template) {
    }

    /**
     * 队列压力快照。
     * 把当前看到的全局排队规模、租户排队规模和是否触达保护上限一起打包出来，
     * 便于入队保护、租约恢复和后续审计共用同一份上下文。
     */
    private record QueuePressureSnapshot(long globalQueuedCount,
                                         long tenantQueuedCount,
                                         int maxQueuedTasksGlobal,
                                         int maxQueuedTasksPerTenant,
                                         boolean reachesGlobalLimit,
                                         boolean reachesTenantLimit) {
    }
}
