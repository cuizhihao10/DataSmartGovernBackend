package com.czh.datasmart.govern.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionHeartbeatRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskLeaseRecoveryResult;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueItemView;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueSummaryResponse;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.service.support.TaskAdminOperationSupport;
import com.czh.datasmart.govern.task.service.support.TaskDataScopeSupport;
import com.czh.datasmart.govern.task.service.support.TaskExecutionLogSupport;
import com.czh.datasmart.govern.task.service.support.TaskExecutionRunSupport;
import com.czh.datasmart.govern.task.service.support.TaskLifecycleSupport;
import com.czh.datasmart.govern.task.service.support.TaskQueueInspectionSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/04/18 22:18
 * @Description DataSmart Govern Backend - TaskServiceImpl.java
 * @Version:1.0.0
 *
 * 任务服务实现门面。
 *
 * <p>这个类现在刻意保持为“薄服务”，主要负责实现 TaskService 接口、声明事务边界，
 * 并把具体业务委托给更小的 support 组件：
 * 1. TaskLifecycleSupport：普通任务生命周期状态机；
 * 2. TaskAdminOperationSupport：管理员强控与事故处置动作；
 * 3. TaskQueueInspectionSupport：队列运营视图与汇总；
 * 4. TaskExecutionRunSupport：执行器认领、心跳、租约恢复和 run 历史；
 * 5. TaskExecutionLogSupport：结构化执行日志查询和写入。
 *
 * <p>这样设计的核心意图是降低耦合度，而不是机械减少行数。
 * task-management 是整个 DataSmart Govern 的调度底座，未来同步任务、质量扫描、AI Agent 工作流、
 * 审批流、回放和补偿任务都会依赖它。如果所有细节继续堆在一个 Impl 中，
 * 后续新增租户配额、公平调度、依赖触发、SLA 告警和执行产物管理时会非常容易互相影响。
 */
@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    /**
     * 普通任务生命周期状态机。
     *
     * <p>这里的“普通”指不绕过业务保护规则，例如 retry 必须遵守最大次数，
     * pause/resume 必须遵守明确状态前置条件。
     */
    private final TaskLifecycleSupport lifecycleSupport;

    /**
     * 管理员强控动作组件。
     *
     * <p>强控动作必须带操作者上下文和原因，用于审计、事故复盘和合规追踪。
     */
    private final TaskAdminOperationSupport adminOperationSupport;

    /**
     * 队列运营视图组件。
     *
     * <p>用于支撑任务工作台、队列积压排障、死信任务治理和后续 SLA 大盘。
     */
    private final TaskQueueInspectionSupport queueInspectionSupport;

    /**
     * 执行器租约组件。
     *
     * <p>用于支撑真实 worker 的认领、心跳、超时恢复和 run 历史查询。
     */
    private final TaskExecutionRunSupport executionRunSupport;

    /**
     * 结构化执行日志组件。
     */
    private final TaskExecutionLogSupport executionLogSupport;

    /**
     * 任务数据范围组件。
     *
     * <p>列表查询属于高频读路径，也是最容易发生越权数据暴露的地方。
     * 这里把 tenant/owner/project 的范围规则委托给独立组件，保证 TaskServiceImpl 仍然保持薄门面。
     */
    private final TaskDataScopeSupport dataScopeSupport;

    @Override
    @Transactional
    public Task createTask(String name, String description, String type, String params, String priority,
                           Integer maxRetryCount, Integer maxDeferCount, Long tenantId, Long ownerId,
                           Long projectId, TaskActorContext actorContext) {
        return createTask(name, description, type, params, priority, maxRetryCount, maxDeferCount,
                tenantId, ownerId, projectId, actorContext, null);
    }

    @Override
    @Transactional
    public Task createTask(String name, String description, String type, String params, String priority,
                           Integer maxRetryCount, Integer maxDeferCount, Long tenantId, Long ownerId,
                           Long projectId, TaskActorContext actorContext, String creationIdempotencyKey) {
        return lifecycleSupport.createTask(name, description, type, params, priority, maxRetryCount, maxDeferCount,
                tenantId, ownerId, projectId, actorContext, creationIdempotencyKey);
    }

    @Override
    public IPage<Task> listTasks(Integer current, Integer size, String status, String type,
                                 Long tenantId, Long ownerId, Long projectId, TaskActorContext actorContext) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Task::getStatus, status);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Task::getType, type);
        }
        dataScopeSupport.applyListScope(wrapper, tenantId, ownerId, projectId, actorContext);
        wrapper.orderByDesc(Task::getCreateTime);

        int safeCurrent = Math.max(1, current == null ? 1 : current);
        int safeSize = Math.max(1, Math.min(size == null ? 10 : size, 200));
        return page(new Page<>(safeCurrent, safeSize), wrapper);
    }

    @Override
    public Task getTaskDetail(Long taskId, TaskActorContext actorContext) {
        Task task = getById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + taskId);
        }
        /*
         * 详情接口必须在服务层做范围校验，而不能只依赖列表接口的过滤。
         * 原因是商业系统中很多前端页面会通过“列表 -> 详情”两步访问数据，
         * 但攻击者或误用方也可能直接构造 /tasks/{id} 请求。
         * 这里统一复用 TaskDataScopeSupport，确保详情、日志、执行记录、生命周期动作使用同一套范围语义。
         */
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "查询任务详情");
        return task;
    }

    @Override
    @Transactional
    public boolean startTask(Long taskId, TaskActorContext actorContext) {
        return lifecycleSupport.startTask(taskId, actorContext);
    }

    @Override
    @Transactional
    public boolean pauseTask(Long taskId, TaskActorContext actorContext) {
        return lifecycleSupport.pauseTask(taskId, actorContext);
    }

    @Override
    @Transactional
    public boolean resumeTask(Long taskId, TaskActorContext actorContext) {
        return lifecycleSupport.resumeTask(taskId, actorContext);
    }

    @Override
    @Transactional
    public boolean cancelTask(Long taskId, TaskActorContext actorContext) {
        return lifecycleSupport.cancelTask(taskId, actorContext);
    }

    @Override
    @Transactional
    public Task retryTask(Long taskId, TaskActorContext actorContext) {
        return lifecycleSupport.retryTask(taskId, actorContext);
    }

    @Override
    @Transactional
    public Task forcePauseTask(Long taskId, String reason, TaskActorContext actorContext) {
        return adminOperationSupport.forcePauseTask(taskId, reason, actorContext);
    }

    @Override
    @Transactional
    public Task forceResumeTask(Long taskId, String reason, TaskActorContext actorContext) {
        return adminOperationSupport.forceResumeTask(taskId, reason, actorContext);
    }

    @Override
    @Transactional
    public Task forceCancelTask(Long taskId, String reason, TaskActorContext actorContext) {
        return adminOperationSupport.forceCancelTask(taskId, reason, actorContext);
    }

    @Override
    @Transactional
    public Task forceRetryTask(Long taskId, String reason, Boolean ignoreRetryLimit, TaskActorContext actorContext) {
        return adminOperationSupport.forceRetryTask(taskId, reason, ignoreRetryLimit, actorContext);
    }

    @Override
    @Transactional
    public Task overridePriority(Long taskId, String priority, String reason, TaskActorContext actorContext) {
        return adminOperationSupport.overridePriority(taskId, priority, reason, actorContext);
    }

    @Override
    public IPage<Task> inspectQueue(TaskQueueInspectionRequest request, TaskActorContext actorContext) {
        return queueInspectionSupport.inspectQueue(request, actorContext);
    }

    @Override
    public IPage<TaskQueueItemView> inspectQueueItems(TaskQueueInspectionRequest request, TaskActorContext actorContext) {
        return queueInspectionSupport.inspectQueueItems(request, actorContext);
    }

    @Override
    public TaskQueueSummaryResponse summarizeQueue(TaskQueueInspectionRequest request, TaskActorContext actorContext) {
        return queueInspectionSupport.summarizeQueue(request, actorContext);
    }

    @Override
    @Transactional
    public boolean updateProgress(Long taskId, Integer progress, String checkpoint,
                                  TaskExecutionCallbackContext callbackContext) {
        return lifecycleSupport.updateProgress(taskId, progress, checkpoint, callbackContext);
    }

    @Override
    @Transactional
    public boolean completeTask(Long taskId, String result, TaskExecutionCallbackContext callbackContext) {
        return lifecycleSupport.completeTask(taskId, result, callbackContext);
    }

    @Override
    @Transactional
    public boolean failTask(Long taskId, String errorMessage, TaskExecutionCallbackContext callbackContext) {
        return lifecycleSupport.failTask(taskId, errorMessage, callbackContext);
    }

    @Override
    @Transactional
    public boolean deferTask(Long taskId, String reason, Integer delaySeconds,
                             TaskExecutionCallbackContext callbackContext) {
        return lifecycleSupport.deferTask(taskId, reason, delaySeconds, callbackContext);
    }

    @Override
    public List<TaskExecutionLog> listExecutionLogs(Long taskId, TaskActorContext actorContext) {
        getTaskDetail(taskId, actorContext);
        return executionLogSupport.listExecutionLogs(taskId);
    }

    @Override
    @Transactional
    public TaskExecutionClaimResult claimNextTask(TaskExecutionClaimRequest request, TaskActorContext actorContext) {
        return executionRunSupport.claimNextTask(request, actorContext);
    }

    @Override
    @Transactional
    public TaskExecutionRun heartbeatExecution(Long runId,
                                               TaskExecutionHeartbeatRequest request,
                                               TaskActorContext actorContext) {
        return executionRunSupport.heartbeatExecution(runId, request, actorContext);
    }

    @Override
    @Transactional
    public TaskLeaseRecoveryResult recoverTimedOutExecutions(Integer limit, TaskActorContext actorContext) {
        return executionRunSupport.recoverTimedOutExecutions(limit, actorContext);
    }

    @Override
    public List<TaskExecutionRun> listExecutionRuns(Long taskId, TaskActorContext actorContext) {
        getTaskDetail(taskId, actorContext);
        return executionRunSupport.listExecutionRuns(taskId);
    }

    @Override
    @Transactional
    public boolean deleteTask(Long taskId, TaskActorContext actorContext) {
        /*
         * 删除动作先复用详情校验，获得两个收益：
         * 1. 任务不存在时可以返回清晰的业务错误，而不是静默 false；
         * 2. 删除前一定经过 tenant/owner 范围判断，避免直接 removeById 造成越权删除。
         */
        getTaskDetail(taskId, actorContext);
        return removeById(taskId);
    }
}
