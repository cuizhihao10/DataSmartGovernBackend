package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import com.czh.datasmart.govern.datasource.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncExecutorDispatchSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncExecutionPersistenceSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncQueueInspectionSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncTaskExecutionControlSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncTaskLifecycleSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    private final SyncAuditSupport syncAuditSupport;
    private final SyncExecutorDispatchSupport syncExecutorDispatchSupport;
    private final SyncExecutionPersistenceSupport syncExecutionPersistenceSupport;
    private final SyncQueueInspectionSupport syncQueueInspectionSupport;
    private final SyncTaskLifecycleSupport syncTaskLifecycleSupport;
    private final SyncTaskExecutionControlSupport syncTaskExecutionControlSupport;

    @Override
    @Transactional
    public SyncTask createTask(CreateSyncTaskRequest request) {
        return syncTaskLifecycleSupport.createTask(request);
    }

    @Override
    @Transactional
    public SyncTask updateTask(Long id, UpdateSyncTaskRequest request) {
        return syncTaskLifecycleSupport.updateTask(id, request);
    }

    @Override
    @Transactional
    public SyncTask submitForApproval(Long id, SyncActionRequest request) {
        return syncTaskLifecycleSupport.submitForApproval(id, request);
    }

    @Override
    @Transactional
    public SyncTask approve(Long id, SyncApprovalRequest request) {
        return syncTaskLifecycleSupport.approve(id, request);
    }

    @Override
    @Transactional
    public SyncTask schedule(Long id, SyncScheduleRequest request) {
        return syncTaskLifecycleSupport.schedule(id, request);
    }

    @Override
    @Transactional
    public SyncTask enqueue(Long id, SyncActionRequest request) {
        return syncTaskLifecycleSupport.enqueue(id, request);
    }

    @Override
    @Transactional
    public SyncTask run(Long id, SyncRunRequest request) {
        return syncTaskExecutionControlSupport.run(id, request);
    }

    @Override
    @Transactional
    public SyncExecutorClaimResult claimNextQueuedTask(SyncExecutorClaimRequest request) {
        return syncExecutorDispatchSupport.claimNextQueuedTask(request);
    }

    @Override
    @Transactional
    public SyncTask heartbeatExecution(SyncExecutorHeartbeatRequest request) {
        return syncExecutorDispatchSupport.heartbeatExecution(request);
    }

    /**
     * 过期租约恢复是执行器认领能力落地后必须补上的平台自愈能力。
     * 如果只有认领和心跳，没有恢复机制，那么执行器失联后任务会长期卡在 RUNNING/RETRYING，
     * 既误导运维判断，也会持续占住租户或数据源的并发槽位。
     */
    @Override
    @Transactional
    public SyncLeaseRecoveryResult recoverExpiredLeases(SyncActionRequest request) {
        return syncExecutorDispatchSupport.recoverExpiredLeases(request);
    }

    @Override
    @Transactional
    public SyncTask pause(Long id, SyncActionRequest request) {
        return syncTaskExecutionControlSupport.pause(id, request);
    }

    @Override
    @Transactional
    public SyncTask resume(Long id, SyncActionRequest request) {
        return syncTaskExecutionControlSupport.resume(id, request);
    }

    @Override
    @Transactional
    public SyncTask retry(Long id, SyncActionRequest request) {
        return syncTaskExecutionControlSupport.retry(id, request);
    }

    @Override
    @Transactional
    public SyncTask cancel(Long id, SyncActionRequest request) {
        return syncTaskExecutionControlSupport.cancel(id, request);
    }

    @Override
    @Transactional
    public SyncTask forceRetry(Long id, SyncActionRequest request) {
        return syncTaskExecutionControlSupport.forceRetry(id, request);
    }

    @Override
    @Transactional
    public SyncTask forceCancel(Long id, SyncActionRequest request) {
        return syncTaskExecutionControlSupport.forceCancel(id, request);
    }

    @Override
    @Transactional
    public SyncTask overridePriority(Long id, SyncPriorityOverrideRequest request) {
        return syncTaskExecutionControlSupport.overridePriority(id, request);
    }

    @Override
    @Transactional
    public SyncTask overrideTimeout(Long id, SyncTimeoutOverrideRequest request) {
        return syncTaskExecutionControlSupport.overrideTimeout(id, request);
    }

    /**
     * 进度回写接口主要给未来的执行器或调度器使用。
     * 当前阶段虽然执行器还未真正落地，但先把回写协议和持久化逻辑做出来，
     * 后续接入异步执行器时就不需要再推翻控制面契约。
     */
    @Override
    @Transactional(readOnly = true)
    public SyncQueueHealthSnapshot inspectQueueHealth(SyncActionRequest request) {
        return syncQueueInspectionSupport.inspectQueueHealth(request);
    }

    @Override
    @Transactional
    public SyncQueueAgingScanResult scanQueuedTaskAging(SyncActionRequest request) {
        return syncQueueInspectionSupport.scanQueuedTaskAging(request);
    }

    @Override
    @Transactional
    public SyncTask reportProgress(Long id, SyncProgressRequest request) {
        return syncTaskExecutionControlSupport.reportProgress(id, request);
    }

    @Override
    @Transactional
    public SyncTask completeExecution(Long id, SyncCompleteRequest request) {
        return syncTaskExecutionControlSupport.completeExecution(id, request);
    }

    @Override
    @Transactional
    public SyncTask failExecution(Long id, SyncFailRequest request) {
        return syncTaskExecutionControlSupport.failExecution(id, request);
    }

    @Override
    @Transactional
    public SyncTask archive(Long id, SyncActionRequest request) {
        return syncTaskLifecycleSupport.archive(id, request);
    }

    @Override
    public List<SyncExecution> listExecutions(Long taskId) {
        syncTaskLifecycleSupport.getRequiredTask(taskId);
        return syncExecutionPersistenceSupport.listExecutions(taskId);
    }

    @Override
    public List<SyncCheckpoint> listCheckpoints(Long taskId) {
        syncTaskLifecycleSupport.getRequiredTask(taskId);
        return syncExecutionPersistenceSupport.listCheckpoints(taskId);
    }

    @Override
    public List<SyncAuditRecord> listAuditRecords(Long taskId) {
        syncTaskLifecycleSupport.getRequiredTask(taskId);
        return syncAuditSupport.listAuditRecords(taskId);
    }

}
