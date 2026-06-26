/**
 * @Author : Cui
 * @Date: 2026/05/08 09:11
 * @Description DataSmart Govern Backend - SyncExecutionLifecycleSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 同步执行生命周期支撑组件。
 *
 * <p>执行器回调是数据同步最容易变复杂的区域：它会同时改 execution、task、checkpoint、错误样本和审计。
 * 如果直接堆进 DataSyncServiceImpl，服务实现很快会超过 500 行，并且很难看清每个状态流转的业务意图。
 *
 * <p>当前提供 start、checkpoint、complete、fail 四个入口。
 * heartbeat/lease 已拆到 `DataSyncExecutorLeaseServiceImpl`，暂停/取消后的回调控制信号则委托
 * {@link SyncExecutionCallbackControlSignalSupport} 处理，避免本类继续膨胀成状态机大杂烩。
 */
@Component
@RequiredArgsConstructor
public class SyncExecutionLifecycleSupport {

    private final SyncExecutionMapper executionMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncCheckpointMapper checkpointMapper;
    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncAuditSupport auditSupport;
    private final SyncCallbackIdempotencySupport idempotencySupport;
    private final SyncExecutionCallbackControlSignalSupport callbackControlSignalSupport;

    /**
     * 启动执行记录。
     */
    public SyncExecution startExecution(SyncTask task,
                                        SyncExecution execution,
                                        SyncExecutionStartRequest request,
                                        SyncActorContext actorContext) {
        String action = "START";
        String scopeKey = executionScope(task, execution);
        if (idempotencySupport.isDuplicate(task.getTenantId(), task.getId(), execution.getId(), action, scopeKey,
                request.getIdempotencyKey(), request.getExecutorId(), "startExecution,executorId=" + request.getExecutorId())) {
            return executionMapper.selectById(execution.getId());
        }
        requireState(execution, SyncExecutionState.QUEUED.name(), "只有 QUEUED 执行记录可以启动");
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setExecutorId(request.getExecutorId().trim());
        execution.setStartedAt(LocalDateTime.now());
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.RUNNING.name());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "startExecution,executorId=" + request.getExecutorId() + ",idempotencyKey=" + request.getIdempotencyKey());
        idempotencySupport.markSucceeded(task.getTenantId(), action, scopeKey, request.getIdempotencyKey(),
                "state=" + execution.getExecutionState());
        return execution;
    }

    /**
     * 写入 checkpoint。
     */
    public SyncCheckpoint writeCheckpoint(SyncTask task,
                                          SyncExecution execution,
                                          SyncExecutionCheckpointRequest request,
                                          SyncActorContext actorContext) {
        String action = "CHECKPOINT";
        String scopeKey = executionScope(task, execution);
        callbackControlSignalSupport.assertNoStoppedControlSignal(execution, request.getExecutorId(), action);
        if (idempotencySupport.isDuplicate(task.getTenantId(), task.getId(), execution.getId(), action, scopeKey,
                request.getIdempotencyKey(), request.getExecutorId(),
                "checkpoint,type=" + request.getCheckpointType() + ",partition=" + request.getShardOrPartition())) {
            return latestCheckpoint(execution.getId());
        }
        callbackControlSignalSupport.assertActiveCallbackAllowed(execution, request.getExecutorId(), action);
        SyncCheckpoint checkpoint = new SyncCheckpoint();
        checkpoint.setTenantId(task.getTenantId());
        checkpoint.setProjectId(task.getProjectId());
        checkpoint.setWorkspaceId(task.getWorkspaceId());
        checkpoint.setSyncTaskId(task.getId());
        checkpoint.setExecutionId(execution.getId());
        checkpoint.setCheckpointType(normalizeCode(request.getCheckpointType()));
        checkpoint.setCheckpointValue(request.getCheckpointValue().trim());
        checkpoint.setShardOrPartition(trimToNull(request.getShardOrPartition()));
        checkpoint.setRecordsRead(safeLong(request.getRecordsRead()));
        checkpoint.setRecordsWritten(safeLong(request.getRecordsWritten()));
        checkpoint.setCheckpointTime(LocalDateTime.now());
        checkpoint.setCreateTime(LocalDateTime.now());
        checkpoint.setUpdateTime(LocalDateTime.now());
        checkpointMapper.insert(checkpoint);

        execution.setCheckpointRef(String.valueOf(checkpoint.getId()));
        execution.setRecordsRead(checkpoint.getRecordsRead());
        execution.setRecordsWritten(checkpoint.getRecordsWritten());
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.updateById(execution);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.UPDATE_CHECKPOINT,
                actorContext, "checkpointId=" + checkpoint.getId() + ",type=" + checkpoint.getCheckpointType());
        idempotencySupport.markSucceeded(task.getTenantId(), action, scopeKey, request.getIdempotencyKey(),
                "checkpointId=" + checkpoint.getId());
        return checkpoint;
    }

    /**
     * 标记执行成功。
     */
    public SyncExecution completeExecution(SyncTask task,
                                           SyncExecution execution,
                                           SyncExecutionCompleteRequest request,
                                           SyncActorContext actorContext) {
        String action = "COMPLETE";
        String scopeKey = executionScope(task, execution);
        callbackControlSignalSupport.assertNoStoppedControlSignal(execution, request.getExecutorId(), action);
        if (idempotencySupport.isDuplicate(task.getTenantId(), task.getId(), execution.getId(), action, scopeKey,
                request.getIdempotencyKey(), request.getExecutorId(),
                "complete,recordsRead=" + request.getRecordsRead() + ",recordsWritten=" + request.getRecordsWritten())) {
            return executionMapper.selectById(execution.getId());
        }
        callbackControlSignalSupport.assertActiveCallbackAllowed(execution, request.getExecutorId(), action);
        execution.setExecutionState(SyncExecutionState.SUCCEEDED.name());
        execution.setRecordsRead(safeLong(request.getRecordsRead()));
        execution.setRecordsWritten(safeLong(request.getRecordsWritten()));
        execution.setCheckpointRef(trimToNull(request.getCheckpointRef()));
        execution.setFinishedAt(LocalDateTime.now());
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.SUCCEEDED.name());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "completeExecution,recordsRead=" + execution.getRecordsRead()
                        + ",recordsWritten=" + execution.getRecordsWritten());
        idempotencySupport.markSucceeded(task.getTenantId(), action, scopeKey, request.getIdempotencyKey(),
                "state=" + execution.getExecutionState());
        return execution;
    }

    /**
     * 标记执行失败并记录错误样本。
     */
    public SyncErrorSample failExecution(SyncTask task,
                                         SyncExecution execution,
                                         SyncExecutionFailRequest request,
                                         SyncActorContext actorContext) {
        String action = "FAIL";
        String scopeKey = executionScope(task, execution);
        callbackControlSignalSupport.assertNoStoppedControlSignal(execution, request.getExecutorId(), action);
        if (idempotencySupport.isDuplicate(task.getTenantId(), task.getId(), execution.getId(), action, scopeKey,
                request.getIdempotencyKey(), request.getExecutorId(),
                "fail,errorType=" + request.getErrorType() + ",errorCode=" + request.getErrorCode())) {
            return latestErrorSample(execution.getId());
        }
        callbackControlSignalSupport.assertFailureCallbackAllowed(execution, request.getExecutorId());
        SyncErrorSample errorSample = new SyncErrorSample();
        errorSample.setTenantId(task.getTenantId());
        errorSample.setProjectId(task.getProjectId());
        errorSample.setWorkspaceId(task.getWorkspaceId());
        errorSample.setSyncTaskId(task.getId());
        errorSample.setExecutionId(execution.getId());
        errorSample.setErrorType(normalizeCode(request.getErrorType()));
        errorSample.setErrorCode(trimToNull(request.getErrorCode()));
        errorSample.setErrorMessage(truncate(trimToNull(request.getErrorMessage()), 1000));
        errorSample.setSourceRecordKey(trimToNull(request.getSourceRecordKey()));
        errorSample.setTargetRecordKey(trimToNull(request.getTargetRecordKey()));
        errorSample.setSamplePayload(truncate(trimToNull(request.getSamplePayload()), 4000));
        errorSample.setRetryable(Boolean.TRUE.equals(request.getRetryable()));
        errorSample.setCreateTime(LocalDateTime.now());
        errorSampleMapper.insert(errorSample);

        execution.setExecutionState(SyncExecutionState.FAILED.name());
        execution.setFailedRecordCount(safeLong(execution.getFailedRecordCount()) + 1L);
        execution.setErrorSummary(errorSample.getErrorMessage());
        execution.setFinishedAt(LocalDateTime.now());
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.updateById(execution);

        task.setCurrentState(SyncTaskState.FAILED.name());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RECORD_ERROR_SAMPLE,
                actorContext, "errorSampleId=" + errorSample.getId() + ",errorType=" + errorSample.getErrorType());
        idempotencySupport.markSucceeded(task.getTenantId(), action, scopeKey, request.getIdempotencyKey(),
                "errorSampleId=" + errorSample.getId());
        return errorSample;
    }

    /**
     * 生成 execution 级幂等作用域。
     *
     * <p>幂等键本身由调用方生成，但服务端仍然需要把它绑定到具体 task/execution。
     * 这样即使两个执行器错误地复用了相同 idempotencyKey，只要 execution 不同，也不会互相干扰。
     */
    private String executionScope(SyncTask task, SyncExecution execution) {
        return task.getId() + ":" + execution.getId();
    }

    /**
     * 重复 checkpoint 请求直接返回当前 execution 最近的 checkpoint。
     */
    private SyncCheckpoint latestCheckpoint(Long executionId) {
        SyncCheckpoint checkpoint = checkpointMapper.selectOne(new LambdaQueryWrapper<SyncCheckpoint>()
                .eq(SyncCheckpoint::getExecutionId, executionId)
                .orderByDesc(SyncCheckpoint::getCheckpointTime)
                .orderByDesc(SyncCheckpoint::getId)
                .last("LIMIT 1"));
        if (checkpoint == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "重复 checkpoint 请求已被识别，但未找到首次 checkpoint 结果，executionId=" + executionId);
        }
        return checkpoint;
    }

    /**
     * 重复 fail 请求直接返回当前 execution 最近的错误样本。
     */
    private SyncErrorSample latestErrorSample(Long executionId) {
        SyncErrorSample errorSample = errorSampleMapper.selectOne(new LambdaQueryWrapper<SyncErrorSample>()
                .eq(SyncErrorSample::getExecutionId, executionId)
                .orderByDesc(SyncErrorSample::getCreateTime)
                .orderByDesc(SyncErrorSample::getId)
                .last("LIMIT 1"));
        if (errorSample == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "重复 fail 请求已被识别，但未找到首次错误样本，executionId=" + executionId);
        }
        return errorSample;
    }

    private void requireState(SyncExecution execution, String expectedState, String message) {
        if (!expectedState.equals(execution.getExecutionState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    message + "，当前状态=" + execution.getExecutionState());
        }
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
