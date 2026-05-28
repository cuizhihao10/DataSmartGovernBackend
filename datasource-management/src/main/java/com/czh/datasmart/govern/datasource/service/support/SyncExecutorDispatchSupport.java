/**
 * @Author : Cui
 * @Date: 2026/05/05 23:18
 * @Description DataSmart Govern Backend - SyncExecutorDispatchSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorHeartbeatRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 同步执行器调度与租约恢复支撑组件。
 *
 * <p>该组件从 `SyncTaskServiceImpl` 中拆出，专门承载“执行器如何领取任务、如何续租、失联后如何恢复”的控制面逻辑。
 * 在商业化数据同步平台中，执行器调度不是普通状态更新，而是可靠执行体系的核心：
 * 1. claim 决定哪个 worker 能拿到哪一个排队任务；
 * 2. heartbeat 用租约证明 worker 仍然存活，避免任务永远占用运行槽位；
 * 3. recoverExpiredLeases 在 worker 崩溃、网络隔离、进程重启后把任务重新纳入可运营状态；
 * 4. 审计记录把系统自动恢复和人工恢复都沉淀为可追踪事件。
 *
 * <p>后续如果要支持批量认领、分片执行、租户级公平调度、执行器分组、资源标签、区域亲和、抢占式调度，
 * 都应优先扩展该组件，而不是继续让同步任务主服务承载 worker 调度细节。
 */
@Component
@RequiredArgsConstructor
public class SyncExecutorDispatchSupport {

    /**
     * 同步任务 Mapper，用于查询和推进任务快照状态。
     */
    private final SyncTaskMapper syncTaskMapper;

    /**
     * 同步执行记录 Mapper，用于写入执行器 ID、心跳时间、租约过期时间和失败恢复结果。
     */
    private final SyncExecutionMapper syncExecutionMapper;

    /**
     * 执行器调度配置。
     * 这里读取租约时长、过期租约恢复批量、是否认领前自动恢复、是否自动重新入队等运行参数。
     */
    private final SyncExecutorProperties syncExecutorProperties;

    /**
     * 执行记录与检查点持久化组件。
     */
    private final SyncExecutionPersistenceSupport syncExecutionPersistenceSupport;

    /**
     * 队列容量组件。
     * 租约恢复时如果要自动重新入队，必须先检查全局和租户队列是否还有容量。
     */
    private final SyncQueueCapacitySupport syncQueueCapacitySupport;

    /**
     * 权限组件。
     * 执行器认领、心跳和管理员恢复都需要独立权限边界，避免普通用户伪造 worker 回调。
     */
    private final SyncTaskPermissionSupport syncTaskPermissionSupport;

    /**
     * 审计组件。
     * worker 认领、租约恢复属于生产级关键事件，必须写入审计轨迹。
     */
    private final SyncAuditSupport syncAuditSupport;

    public SyncExecutorClaimResult claimNextQueuedTask(SyncExecutorClaimRequest request) {
        syncTaskPermissionSupport.assertClaimPermission(request);
        if (Boolean.TRUE.equals(syncExecutorProperties.getAutoRecoverExpiredLeasesBeforeClaim())) {
            recoverExpiredLeasesInternal(
                    request.getActorId(),
                    request.getActorRole(),
                    "AUTO_RECOVER_BEFORE_CLAIM:" + request.getExecutorId(),
                    false
            );
        }

        SyncQueueCapacitySupport.ClaimCandidate candidate = syncQueueCapacitySupport.selectNextClaimableTask(request);
        if (candidate == null) {
            return null;
        }

        SyncTask task = candidate.task();
        SyncTemplate template = candidate.template();
        SyncExecution execution = syncExecutionPersistenceSupport.createExecution(task, SyncTaskState.RUNNING, request.getActorId(),
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
        syncTaskMapper.updateById(task);

        syncAuditSupport.recordAudit(task, execution.getId(), SyncAuditAction.CLAIM_TASK, request.getActorId(), request.getActorRole(),
                syncAuditSupport.buildPayload("executorId", request.getExecutorId(), "leaseExpireAt", leaseExpireAt, "executionId", execution.getId()));
        return buildClaimResult(task, template, execution, leaseExpireAt, request.getExecutorId());
    }

    public SyncTask heartbeatExecution(SyncExecutorHeartbeatRequest request) {
        syncTaskPermissionSupport.assertHeartbeatPermission(request);
        SyncTask task = getRequiredTask(request.getTaskId());
        assertStateIn(task, SyncTaskState.RUNNING, SyncTaskState.RETRYING);
        SyncExecution execution = syncExecutionPersistenceSupport.getRequiredExecution(task, request.getExecutionId());
        if (execution.getExecutorId() == null || !execution.getExecutorId().equals(request.getExecutorId())) {
            throw new IllegalStateException("当前执行记录不属于该执行器实例，禁止续租");
        }

        LocalDateTime leaseExpireAt = computeLeaseExpireAt();
        execution.setHeartbeatAt(LocalDateTime.now());
        execution.setLeaseExpireAt(leaseExpireAt);
        syncExecutionMapper.updateById(execution);

        task.setCurrentExecutorId(request.getExecutorId());
        task.setDispatchLeaseExpireAt(leaseExpireAt);
        syncTaskMapper.updateById(task);
        return task;
    }

    public SyncLeaseRecoveryResult recoverExpiredLeases(SyncActionRequest request) {
        return recoverExpiredLeasesInternal(request.getActorId(), request.getActorRole(), request.getNote(), true);
    }

    private SyncLeaseRecoveryResult recoverExpiredLeasesInternal(Long actorId, String actorRole, String note, boolean requireAdmin) {
        if (requireAdmin) {
            syncTaskPermissionSupport.assertAdminRole(actorRole, null);
        }

        LocalDateTime now = LocalDateTime.now();
        int batchSize = syncExecutorProperties.getExpiredLeaseRecoveryBatchSize() == null
                ? 50
                : Math.max(1, syncExecutorProperties.getExpiredLeaseRecoveryBatchSize());
        List<SyncTask> expiredTasks = syncTaskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
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
            recoverSingleExpiredLease(task, actorId, actorRole, note, now, autoRequeue, result);
        }
        return result;
    }

    private void recoverSingleExpiredLease(SyncTask task, Long actorId, String actorRole, String note,
                                           LocalDateTime now, boolean autoRequeue, SyncLeaseRecoveryResult result) {
        String previousState = task.getCurrentState();
        String previousExecutorId = task.getCurrentExecutorId();
        String recoverySummary = syncAuditSupport.truncate("租约已过期，系统判定执行器可能失联，已触发恢复处理。executorId=" + previousExecutorId);

        SyncExecution execution = syncExecutionPersistenceSupport.getLastExecutionIfPresent(task);
        if (execution != null && execution.getFinishedAt() == null) {
            execution.setState(SyncTaskState.FAILED.name());
            execution.setFinishedAt(now);
            execution.setLeaseExpireAt(null);
            execution.setErrorSummary(recoverySummary);
            syncExecutionMapper.updateById(execution);
            result.getExecutionIds().add(execution.getId());
        }

        SyncQueueCapacitySupport.QueuePressureSnapshot queuePressure =
                syncQueueCapacitySupport.inspectQueuePressure(task.getTenantId(), task.getId());
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
                    ? syncAuditSupport.truncate(recoverySummary + "；但当前待执行队列已达到保护上限，系统改为标记失败等待人工处理。")
                    : recoverySummary);
            task.setOperatorAttentionRequired(true);
            task.setIncidentNote(syncAuditSupport.truncate(note));
            task.setUpdatedBy(actorId);
            result.setFailedTaskCount(result.getFailedTaskCount() + 1);
        }

        syncTaskMapper.updateById(task);
        result.getTaskIds().add(task.getId());
        result.setRecoveredTaskCount(result.getRecoveredTaskCount() + 1);
        syncAuditSupport.recordAudit(task, execution == null ? null : execution.getId(), SyncAuditAction.RECOVER_EXPIRED_LEASE,
                actorId, actorRole,
                syncAuditSupport.buildPayload(
                        "previousState", previousState,
                        "nextState", task.getCurrentState(),
                        "executorId", previousExecutorId,
                        "autoRequeue", autoRequeue,
                        "queueGlobalCount", queuePressure.globalQueuedCount(),
                        "queueTenantCount", queuePressure.tenantQueuedCount(),
                        "note", note
                ));
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

    private LocalDateTime computeLeaseExpireAt() {
        int leaseDurationSeconds = syncExecutorProperties.getLeaseDurationSeconds() == null
                ? 120
                : Math.max(10, syncExecutorProperties.getLeaseDurationSeconds());
        return LocalDateTime.now().plusSeconds(leaseDurationSeconds);
    }

    private void markTaskQueued(SyncTask task, Long actorId, String note) {
        task.setCurrentState(SyncTaskState.QUEUED.name());
        task.setQueuedAt(LocalDateTime.now());
        task.setQueueAttemptCount((task.getQueueAttemptCount() == null ? 0 : task.getQueueAttemptCount()) + 1);
        clearDispatchAssignment(task);
        task.setIncidentNote(syncAuditSupport.truncate(note));
        task.setUpdatedBy(actorId);
    }

    private void clearDispatchAssignment(SyncTask task) {
        task.setCurrentExecutorId(null);
        task.setDispatchLeaseExpireAt(null);
    }

    private SyncTask getRequiredTask(Long id) {
        SyncTask task = syncTaskMapper.selectById(id);
        if (task == null) {
            throw new NoSuchElementException("同步任务不存在: " + id);
        }
        return task;
    }

    private void assertStateIn(SyncTask task, SyncTaskState... allowedStates) {
        SyncTaskState currentState = SyncTaskState.fromValue(task.getCurrentState());
        boolean matched = Arrays.stream(allowedStates).anyMatch(item -> item == currentState);
        if (!matched) {
            throw new IllegalStateException("任务当前状态不允许此操作，currentState=" + currentState.name());
        }
    }
}
