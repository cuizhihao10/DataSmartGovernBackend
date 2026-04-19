package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.extension.service.IService;
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

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/19 21:01
 * @Description DataSmart Govern Backend - SyncTaskService.java
 * @Version:1.0.0
 *
 * 同步任务服务接口。
 * 这一层聚焦的是“任务控制面能力”，也就是任务在平台中如何被管理，而不是底层真正的数据搬运执行器如何实现。
 *
 * 当前接口面向的核心产品问题包括：
 * 1. 任务如何从模板实例化出来。
 * 2. 任务如何进入审批、调度、运行、暂停、恢复、重试、取消和归档流程。
 * 3. 管理员如何做强制控制，例如覆盖优先级、强制取消、强制重试。
 * 4. 执行器如何向平台持续回写运行进度、检查点和最终结果。
 * 5. 运维和审计人员如何查看执行历史、断点恢复信息和审计轨迹。
 * 6. 平台运营如何查看队列健康、发现积压和巡检排队老化。
 */
public interface SyncTaskService extends IService<SyncTask> {

    /**
     * 创建同步任务。
     */
    SyncTask createTask(CreateSyncTaskRequest request);

    /**
     * 更新同步任务基础配置。
     */
    SyncTask updateTask(Long id, UpdateSyncTaskRequest request);

    /**
     * 提交审批。
     * 用于把已配置好的任务推进到治理流程。
     */
    SyncTask submitForApproval(Long id, SyncActionRequest request);

    /**
     * 审批处理。
     */
    SyncTask approve(Long id, SyncApprovalRequest request);

    /**
     * 配置调度计划。
     */
    SyncTask schedule(Long id, SyncScheduleRequest request);

    /**
     * 将任务放入待认领队列。
     */
    SyncTask enqueue(Long id, SyncActionRequest request);

    /**
     * 启动任务执行。
     */
    SyncTask run(Long id, SyncRunRequest request);

    /**
     * 执行器认领下一个待执行任务。
     */
    SyncExecutorClaimResult claimNextQueuedTask(SyncExecutorClaimRequest request);

    /**
     * 执行器上报心跳，续租当前执行。
     */
    SyncTask heartbeatExecution(SyncExecutorHeartbeatRequest request);

    /**
     * 恢复过期租约。
     */
    SyncLeaseRecoveryResult recoverExpiredLeases(SyncActionRequest request);

    /**
     * 暂停任务。
     */
    SyncTask pause(Long id, SyncActionRequest request);

    /**
     * 恢复任务。
     */
    SyncTask resume(Long id, SyncActionRequest request);

    /**
     * 常规重试。
     */
    SyncTask retry(Long id, SyncActionRequest request);

    /**
     * 常规取消。
     */
    SyncTask cancel(Long id, SyncActionRequest request);

    /**
     * 管理员强制重试。
     */
    SyncTask forceRetry(Long id, SyncActionRequest request);

    /**
     * 管理员强制取消。
     */
    SyncTask forceCancel(Long id, SyncActionRequest request);

    /**
     * 覆盖任务优先级。
     */
    SyncTask overridePriority(Long id, SyncPriorityOverrideRequest request);

    /**
     * 覆盖任务超时配置。
     */
    SyncTask overrideTimeout(Long id, SyncTimeoutOverrideRequest request);

    /**
     * 查看当前队列健康快照。
     */
    SyncQueueHealthSnapshot inspectQueueHealth(SyncActionRequest request);

    /**
     * 执行队列老化巡检。
     */
    SyncQueueAgingScanResult scanQueuedTaskAging(SyncActionRequest request);

    /**
     * 回写执行进度。
     */
    SyncTask reportProgress(Long id, SyncProgressRequest request);

    /**
     * 标记执行完成。
     */
    SyncTask completeExecution(Long id, SyncCompleteRequest request);

    /**
     * 标记执行失败。
     */
    SyncTask failExecution(Long id, SyncFailRequest request);

    /**
     * 归档任务。
     */
    SyncTask archive(Long id, SyncActionRequest request);

    /**
     * 查询任务执行历史。
     */
    List<SyncExecution> listExecutions(Long taskId);

    /**
     * 查询任务下的检查点记录。
     */
    List<SyncCheckpoint> listCheckpoints(Long taskId);

    /**
     * 查询任务审计记录。
     */
    List<SyncAuditRecord> listAuditRecords(Long taskId);
}
