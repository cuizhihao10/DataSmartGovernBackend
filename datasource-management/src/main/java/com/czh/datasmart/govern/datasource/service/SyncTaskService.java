package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncApprovalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncCompleteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncFailRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncProgressRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncRunRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncScheduleRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPriorityOverrideRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncTimeoutOverrideRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncTaskService.java
 * @Version:1.0.0
 *
 * 同步任务服务接口。
 * 这里聚焦的是“控制面能力”，即任务如何被创建、审批、调度、运行、人工干预与归档。
 */
public interface SyncTaskService extends IService<SyncTask> {

    SyncTask createTask(CreateSyncTaskRequest request);

    SyncTask updateTask(Long id, UpdateSyncTaskRequest request);

    SyncTask submitForApproval(Long id, SyncActionRequest request);

    SyncTask approve(Long id, SyncApprovalRequest request);

    SyncTask schedule(Long id, SyncScheduleRequest request);

    SyncTask run(Long id, SyncRunRequest request);

    SyncTask pause(Long id, SyncActionRequest request);

    SyncTask resume(Long id, SyncActionRequest request);

    SyncTask retry(Long id, SyncActionRequest request);

    SyncTask cancel(Long id, SyncActionRequest request);

    SyncTask forceRetry(Long id, SyncActionRequest request);

    SyncTask forceCancel(Long id, SyncActionRequest request);

    SyncTask overridePriority(Long id, SyncPriorityOverrideRequest request);

    SyncTask overrideTimeout(Long id, SyncTimeoutOverrideRequest request);

    SyncTask reportProgress(Long id, SyncProgressRequest request);

    SyncTask completeExecution(Long id, SyncCompleteRequest request);

    SyncTask failExecution(Long id, SyncFailRequest request);

    SyncTask archive(Long id, SyncActionRequest request);

    List<SyncExecution> listExecutions(Long taskId);

    List<SyncAuditRecord> listAuditRecords(Long taskId);
}
