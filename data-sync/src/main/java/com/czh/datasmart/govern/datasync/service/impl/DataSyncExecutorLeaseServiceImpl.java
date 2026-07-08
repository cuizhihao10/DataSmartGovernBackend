/**
 * @Author : Cui
 * @Date: 2026/05/08 21:53
 * @Description DataSmart Govern Backend - DataSyncExecutorLeaseServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncExecutorProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDeferRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionHeartbeatRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionHeartbeatResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncExecutorLeaseService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncCallbackIdempotencySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLogSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncWorkerExecutionPlanSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.LocalDateTime;

/**
 * data-sync 执行器租约服务实现。
 *
 * <p>租约协议解决的是“谁有权执行这条 execution”的问题：
 * 1. claim 通过数据库条件更新完成并发裁决；
 * 2. heartbeat 只有当前 executorId 可以续租；
 * 3. defer 允许执行器把暂时不能处理的 execution 延迟放回队列。
 */
@Service
@RequiredArgsConstructor
public class DataSyncExecutorLeaseServiceImpl implements DataSyncExecutorLeaseService {

    private static final long DEFAULT_LEASE_SECONDS = 120L;
    private static final long MAX_LEASE_SECONDS = 1800L;
    private static final long DEFAULT_DEFER_SECONDS = 60L;
    private static final long MAX_DEFER_SECONDS = 3600L;
    private static final int DEFAULT_RECOVERY_LIMIT = 50;
    private static final int MAX_RECOVERY_LIMIT = 500;

    private final SyncExecutionMapper executionMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncAuditSupport auditSupport;
    private final DataSyncExecutorProperties executorProperties;
    private final SyncCallbackIdempotencySupport idempotencySupport;
    private final SyncWorkerExecutionPlanSupport workerExecutionPlanSupport;
    private final SyncExecutionLogSupport executionLogSupport;

    @Override
    @Transactional
    public SyncExecutionClaimResult claimNext(SyncExecutionClaimRequest request, SyncActorContext actorContext) {
        SyncExecution candidate = executionMapper.selectNextClaimCandidate(request.getTenantId());
        if (candidate == null) {
            return new SyncExecutionClaimResult(false, "当前没有可认领的同步执行记录", null, null, null);
        }
        long leaseSeconds = boundedSeconds(request.getLeaseSeconds(), DEFAULT_LEASE_SECONDS, MAX_LEASE_SECONDS);
        int updated = executionMapper.claimQueuedExecution(candidate.getId(), request.getExecutorId().trim(), leaseSeconds);
        if (updated == 0) {
            return new SyncExecutionClaimResult(false, "候选执行记录已被其他执行器认领，请稍后重试", null, null, null);
        }

        SyncExecution claimed = requireExecution(candidate.getId());
        SyncTask task = requireTask(claimed.getSyncTaskId());
        task.setCurrentState(SyncTaskState.RUNNING.name());
        task.setLastExecutionId(claimed.getId());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), claimed.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "claimExecution,executorId=" + request.getExecutorId() + ",leaseSeconds=" + leaseSeconds);
        SyncExecutionClaimResult result = new SyncExecutionClaimResult(true, "同步执行记录认领成功",
                claimed, task, workerExecutionPlanSupport.buildPlan(claimed, task));
        /*
         * 认领日志是执行时间线里从“排队”到“真正开始运行”的分界点。
         *
         * 后续如果用户看到任务长时间没有数据写入，可以先看是否已经出现 WORKER_CLAIMED：
         * - 没有出现：说明队列、调度器或 worker 扫描有问题；
         * - 已经出现：说明 worker 已经拿到租约，应继续看计划、通道和远端批次日志。
         */
        executionLogSupport.recordExecutionEvent(task, claimed, actorContext,
                "CLAIM",
                "INFO",
                "WORKER_CLAIMED",
                "STARTED",
                "执行记录已被 worker 认领，开始进入执行链路",
                "executorId=" + request.getExecutorId()
                        + ", leaseSeconds=" + leaseSeconds
                        + ", workerPlanStatus=" + (result.workerPlan() == null ? "UNKNOWN" : result.workerPlan().planStatus()));
        return result;
    }

    @Override
    @Transactional
    public SyncExecutionHeartbeatResult heartbeat(Long executionId,
                                                  SyncExecutionHeartbeatRequest request,
                                                  SyncActorContext actorContext) {
        SyncExecution execution = requireExecution(executionId);
        SyncExecutionHeartbeatResult controlResult = resolveHeartbeatControlSignal(execution, request, actorContext);
        if (controlResult != null) {
            return controlResult;
        }
        String action = "HEARTBEAT";
        String scopeKey = executionScope(execution);
        if (idempotencySupport.isDuplicate(execution.getTenantId(), execution.getSyncTaskId(), execution.getId(),
                action, scopeKey, request.getIdempotencyKey(), request.getExecutorId(),
                "heartbeat,recordsRead=" + request.getRecordsRead() + ",recordsWritten=" + request.getRecordsWritten())) {
            return SyncExecutionHeartbeatResult.leaseExtended(execution);
        }
        requireHeartbeatRunningExecutor(execution, request.getExecutorId());
        long leaseSeconds = boundedSeconds(request.getLeaseSeconds(), DEFAULT_LEASE_SECONDS, MAX_LEASE_SECONDS);
        int updated = executionMapper.heartbeatLease(
                executionId,
                request.getExecutorId().trim(),
                safeLong(request.getRecordsRead()),
                safeLong(request.getRecordsWritten()),
                leaseSeconds);
        if (updated == 0) {
            return heartbeatAfterFailedLeaseUpdate(executionId, request, actorContext);
        }
        SyncExecution refreshed = requireExecution(executionId);
        auditSupport.saveAudit(refreshed.getTenantId(), refreshed.getSyncTaskId(), refreshed.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "heartbeat,executorId=" + request.getExecutorId() + ",leaseSeconds=" + leaseSeconds);
        idempotencySupport.markSucceeded(refreshed.getTenantId(), action, scopeKey, request.getIdempotencyKey(),
                "leaseExpireTime=" + refreshed.getLeaseExpireTime());
        executionLogSupport.recordExecutionEvent(requireTask(refreshed.getSyncTaskId()), refreshed, actorContext,
                "HEARTBEAT",
                "INFO",
                "WORKER_HEARTBEAT",
                "PROGRESS",
                "worker 心跳续租成功，执行仍在推进",
                "executorId=" + request.getExecutorId()
                        + ", leaseSeconds=" + leaseSeconds
                        + ", recordsRead=" + safeLong(request.getRecordsRead())
                        + ", recordsWritten=" + safeLong(request.getRecordsWritten()));
        return SyncExecutionHeartbeatResult.leaseExtended(refreshed);
    }

    @Override
    @Transactional
    public SyncExecution defer(Long executionId, SyncExecutionDeferRequest request, SyncActorContext actorContext) {
        SyncExecution execution = requireExecution(executionId);
        String action = "DEFER";
        String scopeKey = executionScope(execution);
        if (idempotencySupport.isDuplicate(execution.getTenantId(), execution.getSyncTaskId(), execution.getId(),
                action, scopeKey, request.getIdempotencyKey(), request.getExecutorId(),
                "defer,delaySeconds=" + request.getDelaySeconds() + ",reason=" + truncate(request.getReason(), 200))) {
            return execution;
        }
        requireRunningExecutor(execution, request.getExecutorId());
        long delaySeconds = boundedSeconds(request.getDelaySeconds(), DEFAULT_DEFER_SECONDS, MAX_DEFER_SECONDS);
        String reason = defaultText(request.getReason(), "执行器主动退避，等待后续重新认领");
        int maxDeferCount = executorProperties.effectiveMaxDeferCount();
        int updated = executionMapper.deferRunningExecution(
                executionId,
                request.getExecutorId().trim(),
                delaySeconds,
                truncate(reason, 1000),
                maxDeferCount);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "执行退避失败，可能状态不是 RUNNING 或 executorId 不匹配");
        }

        SyncExecution deferred = requireExecution(executionId);
        SyncTask task = requireTask(deferred.getSyncTaskId());
        refreshTaskAfterLeaseTransition(task, deferred,
                "超过最大退避次数 " + maxDeferCount + "，同步任务需要人工介入，最近原因：" + truncate(reason, 300));
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "defer,delaySeconds=" + delaySeconds
                        + ",deferCount=" + deferred.getDeferCount()
                        + ",maxDeferCount=" + maxDeferCount
                        + ",state=" + deferred.getExecutionState()
                        + ",reason=" + truncate(reason, 200));
        idempotencySupport.markSucceeded(deferred.getTenantId(), action, scopeKey, request.getIdempotencyKey(),
                "state=" + deferred.getExecutionState() + ",deferCount=" + deferred.getDeferCount());
        executionLogSupport.recordExecutionEvent(task, deferred, actorContext,
                "DEFER",
                SyncExecutionState.FAILED.name().equals(deferred.getExecutionState()) ? "ERROR" : "WARN",
                "WORKER_DEFERRED",
                SyncExecutionState.FAILED.name().equals(deferred.getExecutionState()) ? "FAILED" : "RETRYING",
                "worker 主动退避，本次执行暂时无法继续",
                "delaySeconds=" + delaySeconds
                        + ", deferCount=" + deferred.getDeferCount()
                        + ", maxDeferCount=" + maxDeferCount
                        + ", state=" + deferred.getExecutionState()
                        + ", reason=" + truncate(reason, 300));
        return deferred;
    }

    @Override
    @Transactional
    public SyncExpiredLeaseRecoveryResult recoverExpiredLeases(SyncExpiredLeaseRecoveryRequest request,
                                                               SyncActorContext actorContext) {
        int limit = boundedLimit(request == null ? null : request.limit());
        Long tenantId = request == null ? null : request.tenantId();
        boolean requeue = request == null || request.requeue() == null || request.requeue();
        String reason = defaultText(request == null ? null : request.reason(), "执行器租约过期，系统自动恢复");
        String action = "RECOVER_EXPIRED_LEASE";
        String scopeKey = "RECOVERY:" + (tenantId == null ? "ALL" : tenantId);
        String idempotencyKey = request == null ? null : request.idempotencyKey();
        Long idempotencyTenantId = tenantId == null ? 0L : tenantId;
        if (idempotencySupport.isDuplicate(idempotencyTenantId, null, null, action, scopeKey, idempotencyKey,
                "SERVICE_ACCOUNT", "recoverExpiredLease,limit=" + limit + ",reason=" + truncate(reason, 200))) {
            return new SyncExpiredLeaseRecoveryResult(0, 0, Collections.emptyList(), 0, Collections.emptyList(),
                    "重复过期租约恢复请求已识别，本次不再重复扫描和恢复");
        }
        if (!requeue) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "当前版本仅支持将过期租约重新放回队列，暂不支持标记失败或人工关注模式");
        }

        List<SyncExecution> expiredExecutions = executionMapper.selectExpiredRunningLeases(tenantId, limit);
        List<Long> recoveredIds = new ArrayList<>();
        List<Long> attentionIds = new ArrayList<>();
        int maxDeferCount = executorProperties.effectiveMaxDeferCount();
        for (SyncExecution expiredExecution : expiredExecutions) {
            int updated = executionMapper.requeueExpiredLease(expiredExecution.getId(), truncate(reason, 1000), maxDeferCount);
            if (updated == 0) {
                continue;
            }
            SyncExecution recovered = requireExecution(expiredExecution.getId());
            SyncTask task = requireTask(recovered.getSyncTaskId());
            refreshTaskAfterLeaseTransition(task, recovered,
                    "过期租约恢复已达到最大退避次数 " + maxDeferCount + "，同步任务需要人工介入，最近原因：" + truncate(reason, 300));
            auditSupport.saveAudit(task.getTenantId(), task.getId(), recovered.getId(), SyncAuditActionType.RUN_TASK,
                    actorContext, "recoverExpiredLease,state=" + recovered.getExecutionState()
                            + ",deferCount=" + recovered.getDeferCount()
                            + ",maxDeferCount=" + maxDeferCount
                            + ",reason=" + truncate(reason, 200));
            if (SyncExecutionState.FAILED.name().equals(recovered.getExecutionState())) {
                attentionIds.add(recovered.getId());
            } else {
                recoveredIds.add(recovered.getId());
            }
            executionLogSupport.recordExecutionEvent(task, recovered, actorContext,
                    "LEASE_RECOVERY",
                    SyncExecutionState.FAILED.name().equals(recovered.getExecutionState()) ? "ERROR" : "WARN",
                    "EXPIRED_LEASE_RECOVERED",
                    SyncExecutionState.FAILED.name().equals(recovered.getExecutionState()) ? "FAILED" : "RETRYING",
                    "系统发现 worker 租约过期并执行恢复处理",
                    "state=" + recovered.getExecutionState()
                            + ", deferCount=" + recovered.getDeferCount()
                            + ", maxDeferCount=" + maxDeferCount
                            + ", reason=" + truncate(reason, 300));
        }
        SyncExpiredLeaseRecoveryResult result = new SyncExpiredLeaseRecoveryResult(
                expiredExecutions.size(),
                recoveredIds.size(),
                recoveredIds,
                attentionIds.size(),
                attentionIds,
                "过期租约恢复完成，扫描=" + expiredExecutions.size()
                        + "，恢复回队列=" + recoveredIds.size()
                        + "，进入人工介入=" + attentionIds.size());
        idempotencySupport.markSucceeded(idempotencyTenantId, action, scopeKey, idempotencyKey,
                "scanned=" + result.scanned() + ",recovered=" + result.recovered()
                        + ",attentionRequired=" + result.attentionRequired());
        return result;
    }

    private SyncExecution requireExecution(Long executionId) {
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步执行记录不存在: " + executionId);
        }
        return execution;
    }

    private SyncTask requireTask(Long taskId) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + taskId);
        }
        return task;
    }

    private void requireRunningExecutor(SyncExecution execution, String executorId) {
        if (!SyncExecutionState.RUNNING.name().equals(execution.getExecutionState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 RUNNING 状态的执行记录可以 defer，当前状态=" + execution.getExecutionState());
        }
        if (executorId == null || executorId.isBlank() || !executorId.trim().equals(execution.getExecutorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "executorId 不匹配，不能 defer 当前执行记录");
        }
    }

    /**
     * 解析心跳阶段的控制面停止信号。
     *
     * <p>pause/cancel/manual terminate 是控制台、调度器或运营人员对运行中 execution 发出的协作式控制信号。
     * 它们和普通 heartbeat 最大的差异是：服务端不应该再延长租约，否则 worker 会继续认为自己有权写入。
     * 因此本方法在幂等判断和数据库续租之前执行，确保即使 worker 使用了重复的 idempotencyKey，
     * 也能优先看到最新的暂停、取消或手工结束状态，而不是因为幂等命中而拿到旧的“继续执行”响应。
     *
     * <p>安全边界：
     * 1. 只有原 executorId 匹配的 worker 才能收到暂停、取消或手工结束原因，避免其它 worker 通过猜 executionId 探测状态；
     * 2. 返回值只包含低敏控制动作和计数，不暴露 checkpoint、错误摘要、SQL、样本数据、连接信息或凭据；
     * 3. 每次收到停止心跳会写入低敏审计摘要，方便后续排查“worker 是否收到过停止信号”。
     */
    private SyncExecutionHeartbeatResult resolveHeartbeatControlSignal(SyncExecution execution,
                                                                       SyncExecutionHeartbeatRequest request,
                                                                       SyncActorContext actorContext) {
        if (SyncExecutionState.PAUSED.name().equals(execution.getExecutionState())) {
            requireExecutorOwnershipForControlSignal(execution, request.getExecutorId(), "暂停");
            auditHeartbeatControlSignal(execution, request, actorContext, "STOP_FOR_PAUSE");
            return SyncExecutionHeartbeatResult.stopForPause(execution);
        }
        if (SyncExecutionState.CANCELLED.name().equals(execution.getExecutionState())) {
            requireExecutorOwnershipForControlSignal(execution, request.getExecutorId(), "取消");
            auditHeartbeatControlSignal(execution, request, actorContext, "STOP_FOR_CANCEL");
            return SyncExecutionHeartbeatResult.stopForCancel(execution);
        }
        if (SyncExecutionState.MANUALLY_TERMINATED.name().equals(execution.getExecutionState())) {
            requireExecutorOwnershipForControlSignal(execution, request.getExecutorId(), "手工结束");
            auditHeartbeatControlSignal(execution, request, actorContext, "STOP_FOR_MANUAL_TERMINATE");
            return SyncExecutionHeartbeatResult.stopForManualTerminate(execution);
        }
        return null;
    }

    /**
     * 处理 heartbeatLease 原子更新失败后的并发竞态。
     *
     * <p>即使方法开始时看到 execution 仍是 RUNNING，也可能在执行 UPDATE 之前被控制台暂停或取消。
     * 如果这里仍然只抛“状态不是 RUNNING”，worker 会把暂停/取消误判为普通失败并可能触发错误重试。
     * 因此更新失败后重新读取一次最新状态：
     * 1. 最新状态是 PAUSED/CANCELLED/MANUALLY_TERMINATED：返回明确停止指令；
     * 2. 最新状态仍是 RUNNING 但 executor 不匹配：按权限错误处理；
     * 3. 其它状态：说明 execution 已完成、失败或被其它流程接管，返回状态冲突。
     */
    private SyncExecutionHeartbeatResult heartbeatAfterFailedLeaseUpdate(Long executionId,
                                                                         SyncExecutionHeartbeatRequest request,
                                                                         SyncActorContext actorContext) {
        SyncExecution latest = requireExecution(executionId);
        SyncExecutionHeartbeatResult controlResult = resolveHeartbeatControlSignal(latest, request, actorContext);
        if (controlResult != null) {
            return controlResult;
        }
        if (SyncExecutionState.RUNNING.name().equals(latest.getExecutionState())
                && !matchesExecutor(latest, request.getExecutorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "executorId 不匹配，不能为当前同步执行续租");
        }
        throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                "执行心跳续租失败，当前 execution 状态为 " + latest.getExecutionState() + "，不能继续续租");
    }

    /**
     * 校验普通心跳是否仍由当前租约持有者发起。
     *
     * <p>心跳不仅是“我还活着”的信号，也是“我仍然拥有写入 checkpoint/complete/fail 的权利”的续期动作。
     * 因此在真正 UPDATE 之前先做一次清晰校验，可以让错误调用方得到更准确的业务异常，
     * 也避免把所有失败都压缩成数据库更新 0 行。
     */
    private void requireHeartbeatRunningExecutor(SyncExecution execution, String executorId) {
        if (!SyncExecutionState.RUNNING.name().equals(execution.getExecutionState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 RUNNING 状态的执行记录可以续租，当前状态=" + execution.getExecutionState());
        }
        if (!matchesExecutor(execution, executorId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "executorId 不匹配，不能为当前同步执行续租");
        }
    }

    /**
     * 校验暂停/取消控制信号只能返回给原租约持有者。
     */
    private void requireExecutorOwnershipForControlSignal(SyncExecution execution, String executorId, String controlName) {
        if (!matchesExecutor(execution, executorId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "executorId 不匹配，不能读取同步执行" + controlName + "控制信号");
        }
    }

    /**
     * 写入 worker 已感知控制信号的低敏审计摘要。
     *
     * <p>审计 payload 只记录控制动作、executorId 和执行状态，不记录用户填写的暂停/取消原因原文。
     * 原因原文可能包含业务解释、表名、SQL 片段或外部系统信息，已由生命周期操作审计负责保存摘要。
     */
    private void auditHeartbeatControlSignal(SyncExecution execution,
                                             SyncExecutionHeartbeatRequest request,
                                             SyncActorContext actorContext,
                                             String controlAction) {
        auditSupport.saveAudit(execution.getTenantId(), execution.getSyncTaskId(), execution.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "heartbeatControl,controlAction=" + controlAction
                        + ",executorId=" + request.getExecutorId()
                        + ",state=" + execution.getExecutionState());
    }

    private boolean matchesExecutor(SyncExecution execution, String executorId) {
        return executorId != null
                && !executorId.isBlank()
                && execution.getExecutorId() != null
                && executorId.trim().equals(execution.getExecutorId());
    }

    private String executionScope(SyncExecution execution) {
        return execution.getSyncTaskId() + ":" + execution.getId();
    }

    /**
     * 根据执行租约流转结果刷新任务主状态。
     *
     * <p>execution 和 task 的状态粒度不同：
     * 1. execution 表达“这一次运行”的结果，可以因为超过退避上限而 FAILED；
     * 2. task 表达“这个同步任务作为运营对象接下来该怎么处理”，超过上限时更适合进入 AWAITING_OPERATOR_ACTION。
     *
     * <p>这种拆分避免把所有失败都混成一个 FAILED：
     * 普通 SQL 语法错误、字段映射错误、目标端唯一键冲突可以是业务失败；
     * 但多次租约过期或反复 defer 更像执行环境、容量、配额或连接器稳定性问题，需要运营台显式提醒。
     */
    private void refreshTaskAfterLeaseTransition(SyncTask task,
                                                 SyncExecution execution,
                                                 String attentionReason) {
        if (SyncExecutionState.FAILED.name().equals(execution.getExecutionState())) {
            taskMapper.markAwaitingOperatorAction(task.getId(), execution.getId(), truncate(attentionReason, 1000));
        } else {
            taskMapper.markQueuedAfterLeaseTransition(task.getId(), execution.getId());
        }
    }

    private long boundedSeconds(Long requested, long defaultValue, long maxValue) {
        long value = requested == null || requested <= 0 ? defaultValue : requested;
        return Math.min(value, maxValue);
    }

    private int boundedLimit(Integer requested) {
        int value = requested == null || requested <= 0 ? DEFAULT_RECOVERY_LIMIT : requested;
        return Math.min(value, MAX_RECOVERY_LIMIT);
    }

    private Long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
