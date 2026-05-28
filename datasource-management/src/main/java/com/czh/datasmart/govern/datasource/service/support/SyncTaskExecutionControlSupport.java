/**
 * @Author : Cui
 * @Date: 2026/05/05 23:42
 * @Description DataSmart Govern Backend - SyncTaskExecutionControlSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncCompleteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncFailRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPriorityOverrideRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncProgressRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncRunRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncTimeoutOverrideRequest;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.support.PriorityLevel;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import com.czh.datasmart.govern.datasource.support.TriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 同步任务执行控制支撑组件。
 *
 * <p>该组件承载同步任务“进入执行、执行中控制、执行结果回写”的状态推进逻辑。
 * 它与 `SyncTaskLifecycleSupport`、`SyncExecutorDispatchSupport` 的边界不同：
 * 1. 生命周期组件负责配置态、审批态、排期态和入队态；
 * 2. 执行器调度组件负责 worker 认领、心跳续租和租约恢复；
 * 3. 本组件负责任务已经要运行或正在运行时，如何暂停、恢复、重试、取消、完成、失败和回写进度。
 *
 * <p>商业化数据同步产品里，执行控制是事故高发区：
 * - 人工直跑可能绕过队列，需要受配置保护；
 * - 暂停/取消可能发生在执行器仍在写数据时，后续需要和执行器协议联动；
 * - 成功/失败回调必须考虑幂等、重复回调、过期 executionId、错误摘要截断；
 * - 重试必须考虑最大重试次数、检查点恢复、重复写入和租户配额。
 *
 * <p>当前实现仍是控制面第一版，但把它独立出来后，后续可以集中补充：
 * 回调幂等键、乐观锁、执行器签名、任务中止信号、分片完成汇总、补偿/回滚策略、执行指标和 SLA 事件。
 */
@Component
@RequiredArgsConstructor
public class SyncTaskExecutionControlSupport {

    /**
     * 同步任务 Mapper。
     * 执行控制会频繁推进任务主表的 currentState、lastExecutionId、错误摘要和运营关注标记。
     */
    private final SyncTaskMapper syncTaskMapper;

    /**
     * 同步执行记录 Mapper。
     * 执行控制需要更新 execution 的状态、记录量、错误摘要、完成时间和租约字段。
     */
    private final SyncExecutionMapper syncExecutionMapper;

    /**
     * 执行器配置。
     * 当前主要用于判断人工直跑是否允许绕过队列。
     */
    private final SyncExecutorProperties syncExecutorProperties;

    /**
     * 执行记录与检查点持久化组件。
     */
    private final SyncExecutionPersistenceSupport syncExecutionPersistenceSupport;

    /**
     * 队列容量组件。
     * 人工直跑和恢复入队都必须检查并发/队列保护阈值。
     */
    private final SyncQueueCapacitySupport syncQueueCapacitySupport;

    /**
     * 权限组件。
     * 执行动作比普通查看更危险，必须校验操作者角色、租户边界和执行器回调权限。
     */
    private final SyncTaskPermissionSupport syncTaskPermissionSupport;

    /**
     * 生命周期组件。
     * 这里复用任务查询、模板启用校验、状态守卫、入队字段维护和清理派发字段等通用能力。
     */
    private final SyncTaskLifecycleSupport syncTaskLifecycleSupport;

    /**
     * 审计组件。
     * 所有执行控制动作都要写审计，便于事故复盘和合规追踪。
     */
    private final SyncAuditSupport syncAuditSupport;

    public SyncTask run(Long id, SyncRunRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        syncTaskLifecycleSupport.assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.SCHEDULED, SyncTaskState.QUEUED, SyncTaskState.RETRYING);
        syncTaskLifecycleSupport.ensureApprovalReady(task);
        syncTaskLifecycleSupport.ensureTaskEnabled(task);
        SyncTemplate template = syncTaskLifecycleSupport.getRequiredEnabledTemplate(task.getTemplateId());
        if (!Boolean.TRUE.equals(syncExecutorProperties.getAllowManualRunBypassQueue())
                && !SyncTaskState.QUEUED.name().equals(task.getCurrentState())) {
            throw new IllegalStateException("当前环境不允许人工直跑绕过队列，请先将任务 enqueue 后由执行器认领");
        }
        syncQueueCapacitySupport.ensureConcurrencyCapacity(task, template);

        SyncExecution execution = syncExecutionPersistenceSupport.createExecution(
                task, SyncTaskState.RUNNING, request.getActorId(), request.getTriggerReason());
        task.setCurrentState(SyncTaskState.RUNNING.name());
        task.setTriggerType(TriggerType.fromValue(request.getTriggerType()).name());
        task.setLastExecutionId(execution.getId());
        task.setQueuedAt(null);
        task.setCurrentExecutorId(null);
        task.setDispatchLeaseExpireAt(null);
        task.setLatestErrorSummary(null);
        task.setOperatorAttentionRequired(false);
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.RUN_TASK, request.getActorId(), request.getActorRole(),
                buildPayload(
                        "executionId", execution.getId(),
                        "triggerType", request.getTriggerType(),
                        "triggerReason", request.getTriggerReason()
                ));
        return task;
    }

    public SyncTask pause(Long id, SyncActionRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        syncTaskLifecycleSupport.assertStateIn(task, SyncTaskState.RUNNING, SyncTaskState.RETRYING);
        SyncExecution execution = syncExecutionPersistenceSupport.getLatestExecution(task);

        execution.setState(SyncTaskState.PAUSED.name());
        execution.setLeaseExpireAt(null);
        syncExecutionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.PAUSED.name());
        syncTaskLifecycleSupport.clearDispatchAssignment(task);
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);

        recordAudit(task, execution.getId(), SyncAuditAction.PAUSE_TASK, request.getActorId(), request.getActorRole(),
                buildPayload("executionId", execution.getId(), "note", request.getNote()));
        return task;
    }

    public SyncTask resume(Long id, SyncActionRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        syncTaskLifecycleSupport.assertStateIn(task, SyncTaskState.PAUSED);
        syncQueueCapacitySupport.ensureQueueCapacity(task);
        syncTaskLifecycleSupport.markTaskQueued(task, request.getActorId(), request.getNote());
        syncTaskMapper.updateById(task);
        recordAudit(task, task.getLastExecutionId(), SyncAuditAction.RESUME_TASK, request.getActorId(), request.getActorRole(),
                buildPayload("executionId", task.getLastExecutionId(), "note", request.getNote()));
        return task;
    }

    public SyncTask retry(Long id, SyncActionRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        syncTaskLifecycleSupport.assertStateIn(task, SyncTaskState.FAILED, SyncTaskState.PARTIALLY_SUCCEEDED);
        if (task.getRetryCount() >= task.getMaxRetryCount()) {
            throw new IllegalStateException("任务已达到最大重试次数上限");
        }
        return doRetry(task, request.getActorId(), request.getActorRole(), request.getNote(), SyncAuditAction.RETRY_TASK);
    }

    public SyncTask cancel(Long id, SyncActionRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertTaskOperationPermission(task, request.getActorId(), request.getActorRole(), request.getActorTenantId());
        syncTaskLifecycleSupport.assertStateIn(task, SyncTaskState.CONFIGURED, SyncTaskState.PENDING_APPROVAL, SyncTaskState.SCHEDULED,
                SyncTaskState.QUEUED, SyncTaskState.RUNNING, SyncTaskState.PAUSED,
                SyncTaskState.RETRYING, SyncTaskState.PARTIALLY_SUCCEEDED);
        return cancelTask(task, request.getActorId(), request.getActorRole(), request.getNote(),
                false, SyncAuditAction.CANCEL_TASK);
    }

    public SyncTask forceRetry(Long id, SyncActionRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertAdminRole(request.getActorRole(), request.getActorTenantId());
        syncTaskLifecycleSupport.assertStateIn(task, SyncTaskState.FAILED, SyncTaskState.CANCELLED, SyncTaskState.PARTIALLY_SUCCEEDED, SyncTaskState.PAUSED);
        return doRetry(task, request.getActorId(), request.getActorRole(), request.getNote(), SyncAuditAction.FORCE_RETRY_TASK);
    }

    public SyncTask forceCancel(Long id, SyncActionRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertAdminRole(request.getActorRole(), request.getActorTenantId());
        if (SyncTaskState.ARCHIVED.name().equals(task.getCurrentState())) {
            throw new IllegalStateException("归档任务不允许再执行强制取消");
        }
        return cancelTask(task, request.getActorId(), request.getActorRole(), request.getNote(),
                true, SyncAuditAction.FORCE_CANCEL_TASK);
    }

    public SyncTask overridePriority(Long id, SyncPriorityOverrideRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertAdminRole(request.getActorRole(), request.getActorTenantId());
        task.setPriority(PriorityLevel.fromValue(request.getPriority()).name());
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        recordAudit(task, null, SyncAuditAction.OVERRIDE_PRIORITY, request.getActorId(), request.getActorRole(),
                buildPayload("priority", task.getPriority(), "note", request.getNote()));
        return task;
    }

    public SyncTask overrideTimeout(Long id, SyncTimeoutOverrideRequest request) {
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskPermissionSupport.assertAdminRole(request.getActorRole(), request.getActorTenantId());
        task.setTimeoutSeconds(request.getTimeoutSeconds());
        task.setIncidentNote(truncate(request.getNote()));
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        recordAudit(task, null, SyncAuditAction.OVERRIDE_TIMEOUT, request.getActorId(), request.getActorRole(),
                buildPayload("timeoutSeconds", request.getTimeoutSeconds(), "note", request.getNote()));
        return task;
    }

    public SyncTask reportProgress(Long id, SyncProgressRequest request) {
        syncTaskPermissionSupport.assertExecutionProgressPermission(request.getActorRole(), request.getActorId(), request.getActorTenantId(),
                syncTaskLifecycleSupport.getRequiredTask(id));
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        syncTaskLifecycleSupport.assertStateIn(task, SyncTaskState.RUNNING, SyncTaskState.RETRYING);
        SyncExecution execution = syncExecutionPersistenceSupport.getRequiredExecution(task, request.getExecutionId());

        execution.setRecordsRead(request.getRecordsRead());
        execution.setRecordsWritten(request.getRecordsWritten());
        execution.setFailedRecordCount(request.getFailedRecordCount());
        execution.setErrorSummary(truncate(request.getErrorSummary()));
        if (request.getCheckpointValue() != null && !request.getCheckpointValue().isBlank()) {
            execution.setCheckpointRef(request.getCheckpointType() + ":" + truncate(request.getCheckpointValue()));
            syncExecutionPersistenceSupport.upsertCheckpoint(
                    execution.getId(), request.getCheckpointType(), request.getCheckpointValue(), request.getShardOrPartition());
            recordAudit(task, execution.getId(), SyncAuditAction.SAVE_CHECKPOINT, request.getActorId(), request.getActorRole(),
                    buildPayload("checkpointType", request.getCheckpointType(), "shardOrPartition", request.getShardOrPartition()));
        }
        syncExecutionMapper.updateById(execution);

        if (request.getFailedRecordCount() > 0) {
            task.setOperatorAttentionRequired(true);
        }
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        recordAudit(task, execution.getId(), SyncAuditAction.UPDATE_PROGRESS, request.getActorId(), request.getActorRole(),
                buildPayload(
                        "recordsRead", request.getRecordsRead(),
                        "recordsWritten", request.getRecordsWritten(),
                        "failedRecordCount", request.getFailedRecordCount()
                ));
        return task;
    }

    public SyncTask completeExecution(Long id, SyncCompleteRequest request) {
        syncTaskPermissionSupport.assertExecutionResultPermission(request.getActorRole(), request.getActorId(), request.getActorTenantId(),
                syncTaskLifecycleSupport.getRequiredTask(id));
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        SyncExecution execution = syncExecutionPersistenceSupport.getRequiredExecution(task, request.getExecutionId());

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
        syncTaskLifecycleSupport.clearDispatchAssignment(task);
        task.setLatestErrorSummary(request.getFailedRecordCount() > 0 ? truncate(request.getSummary()) : null);
        task.setOperatorAttentionRequired(request.getFailedRecordCount() > 0);
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        recordAudit(task, execution.getId(), SyncAuditAction.COMPLETE_EXECUTION, request.getActorId(), request.getActorRole(),
                buildPayload("finalState", finalState.name(), "summary", request.getSummary()));
        return task;
    }

    public SyncTask failExecution(Long id, SyncFailRequest request) {
        syncTaskPermissionSupport.assertExecutionResultPermission(request.getActorRole(), request.getActorId(), request.getActorTenantId(),
                syncTaskLifecycleSupport.getRequiredTask(id));
        SyncTask task = syncTaskLifecycleSupport.getRequiredTask(id);
        SyncExecution execution = syncExecutionPersistenceSupport.getRequiredExecution(task, request.getExecutionId());
        execution.setState(SyncTaskState.FAILED.name());
        execution.setFailedRecordCount(request.getFailedRecordCount());
        execution.setErrorSummary(truncate(request.getErrorSummary()));
        execution.setFinishedAt(LocalDateTime.now());
        execution.setLeaseExpireAt(null);
        syncExecutionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.FAILED.name());
        syncTaskLifecycleSupport.clearDispatchAssignment(task);
        task.setLatestErrorSummary(truncate(request.getErrorSummary()));
        task.setOperatorAttentionRequired(true);
        task.setUpdatedBy(request.getActorId());
        syncTaskMapper.updateById(task);
        recordAudit(task, execution.getId(), SyncAuditAction.FAIL_EXECUTION, request.getActorId(), request.getActorRole(),
                buildPayload("failedRecordCount", request.getFailedRecordCount(), "errorSummary", request.getErrorSummary()));
        return task;
    }

    private SyncTask doRetry(SyncTask task, Long actorId, String actorRole, String note, SyncAuditAction action) {
        syncTaskLifecycleSupport.ensureTaskEnabled(task);
        syncTaskLifecycleSupport.getRequiredEnabledTemplate(task.getTemplateId());
        task.setRetryCount(task.getRetryCount() + 1);
        task.setCurrentState(SyncTaskState.RETRYING.name());
        task.setLatestErrorSummary(null);
        task.setOperatorAttentionRequired(false);
        task.setIncidentNote(truncate(note));
        task.setUpdatedBy(actorId);
        SyncExecution execution = syncExecutionPersistenceSupport.createExecution(task, SyncTaskState.RETRYING, actorId, note);
        task.setLastExecutionId(execution.getId());
        syncTaskMapper.updateById(task);
        recordAudit(task, execution.getId(), action, actorId, actorRole,
                buildPayload("retryCount", task.getRetryCount(), "note", note));
        return task;
    }

    private SyncTask cancelTask(SyncTask task, Long actorId, String actorRole, String note,
                                boolean force, SyncAuditAction action) {
        SyncExecution execution = syncExecutionPersistenceSupport.getLastExecutionIfPresent(task);
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
        syncTaskLifecycleSupport.clearDispatchAssignment(task);
        task.setOperatorAttentionRequired(force);
        task.setIncidentNote(truncate(note));
        task.setUpdatedBy(actorId);
        syncTaskMapper.updateById(task);
        recordAudit(task, execution == null ? null : execution.getId(), action,
                actorId, actorRole,
                buildPayload("executionId", execution == null ? "" : execution.getId(), "note", note));
        return task;
    }

    private void recordAudit(SyncTask task, Long executionId, SyncAuditAction action,
                             Long actorId, String actorRole, String payload) {
        syncAuditSupport.recordAudit(task, executionId, action, actorId, actorRole, payload);
    }

    private String buildPayload(Object... pairs) {
        return syncAuditSupport.buildPayload(pairs);
    }

    private String truncate(String value) {
        return syncAuditSupport.truncate(value);
    }
}
