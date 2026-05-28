package com.czh.datasmart.govern.task.service.support;

import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.support.TaskExecutionRunState;
import com.czh.datasmart.govern.task.support.TaskPriority;
import com.czh.datasmart.govern.task.support.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/05/05 23:53
 * @Description DataSmart Govern Backend - TaskLifecycleSupport.java
 * @Version:1.0.0
 *
 * 任务生命周期支持组件。
 *
 * <p>该组件承载普通业务侧可见的任务状态流转，例如创建、启动、暂停、恢复、取消、重试、进度更新、
 * 完成、失败和延迟回队列。它和管理员强控的区别在于：
 * 1. 普通生命周期动作遵循更严格的状态前置条件；
 * 2. 普通动作通常来自业务系统或执行器回调，不应随意绕过重试上限、死信保护等规则；
 * 3. 管理员强控更多服务于事故止损和人工补偿，单独放在 TaskAdminOperationSupport。
 *
 * <p>把生命周期从 TaskServiceImpl 中拆出后，主服务可以回到“API 门面 + 事务边界”的角色，
 * 本组件则像一个小型状态机，集中维护任务主表快照和执行日志之间的一致性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLifecycleSupport {

    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;
    private static final int DEFAULT_DEFER_SECONDS = 30;
    private static final int DEFAULT_MAX_DEFER_COUNT = 20;
    private static final int MAX_DEFER_SECONDS = 3600;

    private final TaskMapper taskMapper;
    private final TaskExecutionRunSupport runSupport;
    private final TaskExecutionLogSupport logSupport;
    private final TaskCallbackIdempotencySupport idempotencySupport;
    private final TaskExecutorCallbackSupport executorCallbackSupport;
    private final TaskDataScopeSupport dataScopeSupport;

    /**
     * 创建任务。
     *
     * <p>创建动作不只是插入一行数据库记录，还会初始化任务调度所需的关键快照字段：
     * 优先级、状态、进度、重试次数、连续 defer 次数、排队时间和超时配置。
     * 这些字段让任务从创建开始就具备可调度、可查询、可审计的基础能力。
     */
    public Task createTask(String name, String description, String type, String params, String priority,
                           Integer maxRetryCount, Integer maxDeferCount, Long tenantId, Long ownerId,
                           Long projectId, TaskActorContext actorContext) {
        Task task = new Task();
        task.setName(name);
        task.setDescription(description);
        task.setType(type);
        task.setTenantId(dataScopeSupport.resolveTenantIdForCreate(tenantId, actorContext));
        task.setOwnerId(dataScopeSupport.resolveOwnerIdForCreate(ownerId, actorContext));
        task.setProjectId(dataScopeSupport.resolveProjectIdForCreate(projectId, actorContext));
        task.setParams(params);
        task.setPriority(TaskPriority.normalize(priority));
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setMaxRetryCount(maxRetryCount != null ? maxRetryCount : 3);
        task.setDeferCount(0);
        task.setMaxDeferCount(normalizeMaxDeferCount(maxDeferCount));
        task.setQueuedTime(LocalDateTime.now());
        task.setAttentionRequired(false);
        task.setTimeoutSeconds(DEFAULT_TIMEOUT_SECONDS);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());

        taskMapper.insert(task);
        logSupport.saveExecutionLog(task.getId(), "CREATE", null, TaskStatus.PENDING, "任务已创建",
                "tenantId=" + task.getTenantId()
                        + ", ownerId=" + task.getOwnerId()
                        + ", projectId=" + task.getProjectId()
                        + ", params=" + task.getParams(),
                logSupport.actorLabel(actorContext));
        log.info("创建任务成功，taskId={}", task.getId());
        return task;
    }

    /**
     * 启动任务。
     *
     * <p>当前仅允许 PENDING -> RUNNING。后续如果接入真正 worker 调度，人工 start 可以逐步弱化，
     * 更多任务会通过 claimNextTask 建立执行租约后进入 RUNNING。
     */
    public boolean startTask(Long taskId, TaskActorContext actorContext) {
        Task task = requireTask(taskId);
        /*
         * 普通生命周期动作同样需要数据范围校验。
         * 例如项目成员可以启动自己负责的质量扫描任务，但不应该通过猜测 ID 启动其他租户或其他负责人任务。
         * 这里先校验“能不能触达这条任务”，更细的动作级权限矩阵后续会继续下沉到 permission-admin。
         */
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "启动任务");
        ensureStatus(task, TaskStatus.PENDING, "只有待执行任务才能启动");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.RUNNING);
        task.setStartTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "START", previousStatus, TaskStatus.RUNNING, "任务已启动", null);
        return true;
    }

    /**
     * 暂停运行中任务。
     */
    public boolean pauseTask(Long taskId, TaskActorContext actorContext) {
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "暂停任务");
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能暂停");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PAUSED);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "PAUSE", previousStatus, TaskStatus.PAUSED, "任务已暂停", task.getCheckpoint());
        return true;
    }

    /**
     * 恢复已暂停任务。
     *
     * <p>保留原有 PAUSED -> RUNNING 语义，确保旧接口行为不变。
     * 管理员恢复接口则会把任务放回 PENDING，交给调度器重新认领。
     */
    public boolean resumeTask(Long taskId, TaskActorContext actorContext) {
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "恢复任务");
        ensureStatus(task, TaskStatus.PAUSED, "只有已暂停任务才能恢复");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.RUNNING);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "RESUME", previousStatus, TaskStatus.RUNNING, "任务已恢复", task.getCheckpoint());
        return true;
    }

    /**
     * 取消任务。
     *
     * <p>取消是用户或系统主动终止，不等同于执行失败。
     * 进入取消状态后，会清理执行器租约并尝试结束当前 run，避免后续心跳继续污染任务快照。
     */
    public boolean cancelTask(Long taskId, TaskActorContext actorContext) {
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "取消任务");
        if (TaskStatus.SUCCESS.equals(task.getStatus()) || TaskStatus.FAILED.equals(task.getStatus())) {
            throw new IllegalStateException("已结束任务不能取消");
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.CANCELLED);
        task.setEndTime(LocalDateTime.now());
        clearExecutorLease(task);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        runSupport.finishCurrentRunIfPresent(task, TaskExecutionRunState.CANCELLED, "任务被取消");

        logSupport.saveExecutionLog(taskId, "CANCEL", previousStatus, TaskStatus.CANCELLED, "任务已取消", null);
        return true;
    }

    /**
     * 普通重试任务。
     *
     * <p>普通 retry 只允许 FAILED/CANCELLED 任务重新进入 PENDING，并必须遵守最大重试次数。
     * 死信、暂停等特殊情况交给管理员强控接口，避免普通调用方绕过运营保护。
     */
    public Task retryTask(Long taskId, TaskActorContext actorContext) {
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "重试任务");
        if (!TaskStatus.FAILED.equals(task.getStatus()) && !TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new IllegalStateException("只有失败或已取消任务才能重试");
        }
        if (safeRetryCount(task) >= task.getMaxRetryCount()) {
            throw new IllegalStateException("任务已超过最大重试次数");
        }

        String previousStatus = task.getStatus();
        resetTaskForRetry(task);
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "RETRY", previousStatus, TaskStatus.PENDING,
                "任务已重试", "retryCount=" + task.getRetryCount());
        return task;
    }

    /**
     * 更新任务进度。
     *
     * <p>允许 RUNNING 和 PAUSED 更新进度：
     * RUNNING 用于持续上报执行进展，PAUSED 用于落下最后 checkpoint，便于恢复和复盘。
     */
    public boolean updateProgress(Long taskId, Integer progress, String checkpoint,
                                  TaskExecutionCallbackContext callbackContext) {
        if (isDuplicateCallback(taskId, "PROGRESS", callbackContext,
                "progress=" + progress + ", checkpoint=" + logSupport.defaultText(checkpoint, ""))) {
            return true;
        }
        Task task = requireTask(taskId);
        executorCallbackSupport.validateExecutorCallback(task, callbackContext, "更新任务进度");
        if (!TaskStatus.RUNNING.equals(task.getStatus()) && !TaskStatus.PAUSED.equals(task.getStatus())) {
            throw new IllegalStateException("只有运行中或已暂停任务才能更新进度");
        }

        task.setProgress(progress);
        task.setCheckpoint(checkpoint);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "PROGRESS", task.getStatus(), task.getStatus(),
                "任务进度已更新",
                executorCallbackSupport.callbackDetails(callbackContext,
                        "checkpoint=" + logSupport.defaultText(checkpoint, "")));
        idempotencySupport.markSucceeded(taskId, "PROGRESS", callbackContext, "progress=" + progress);
        return true;
    }

    /**
     * 标记任务完成。
     */
    public boolean completeTask(Long taskId, String result, TaskExecutionCallbackContext callbackContext) {
        if (isDuplicateCallback(taskId, "COMPLETE", callbackContext,
                "result=" + logSupport.defaultText(result, ""))) {
            return true;
        }
        Task task = requireTask(taskId);
        executorCallbackSupport.validateExecutorCallback(task, callbackContext, "标记任务完成");
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能标记完成");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.SUCCESS);
        task.setProgress(100);
        task.setResult(result);
        task.setEndTime(LocalDateTime.now());
        clearExecutorLease(task);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        runSupport.finishCurrentRunIfPresent(task, TaskExecutionRunState.SUCCESS, null);

        logSupport.saveExecutionLog(taskId, "COMPLETE", previousStatus, TaskStatus.SUCCESS,
                "任务已完成", executorCallbackSupport.callbackDetails(callbackContext,
                        "result=" + logSupport.defaultText(result, "")));
        idempotencySupport.markSucceeded(taskId, "COMPLETE", callbackContext, TaskStatus.SUCCESS);
        return true;
    }

    /**
     * 标记任务失败。
     *
     * <p>失败会设置 attentionRequired=true，表示需要运营人员关注。
     * 这为后续告警中心、任务失败工作台和 SLA 报表提供统一风险入口。
     */
    public boolean failTask(Long taskId, String errorMessage, TaskExecutionCallbackContext callbackContext) {
        if (isDuplicateCallback(taskId, "FAIL", callbackContext,
                "errorMessage=" + logSupport.defaultText(errorMessage, ""))) {
            return true;
        }
        Task task = requireTask(taskId);
        executorCallbackSupport.validateExecutorCallback(task, callbackContext, "标记任务失败");
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能标记失败");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.FAILED);
        task.setResult(errorMessage);
        task.setEndTime(LocalDateTime.now());
        clearExecutorLease(task);
        task.setAttentionRequired(true);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        runSupport.finishCurrentRunIfPresent(task, TaskExecutionRunState.FAILED, errorMessage);

        logSupport.saveExecutionLog(taskId, "FAIL", previousStatus, TaskStatus.FAILED,
                "任务执行失败", executorCallbackSupport.callbackDetails(callbackContext,
                        "errorMessage=" + logSupport.defaultText(errorMessage, "")));
        idempotencySupport.markSucceeded(taskId, "FAIL", callbackContext, TaskStatus.FAILED);
        return true;
    }

    /**
     * 执行器主动延迟任务并放回可认领队列。
     *
     * <p>defer 代表容量背压，不代表业务失败。
     * 例如 data-quality worker 发现当前实例并发已满，可以结束当前 run、释放租约，并把任务延迟一小段时间后重新排队。
     * 连续 defer 超过上限后，任务会进入 DEAD_LETTER，避免无限“认领 -> 退避 -> 再认领”的隐性循环。
     */
    public boolean deferTask(Long taskId, String reason, Integer delaySeconds,
                             TaskExecutionCallbackContext callbackContext) {
        if (isDuplicateCallback(taskId, "DEFER", callbackContext,
                "reason=" + logSupport.defaultText(reason, "") + ", delaySeconds=" + delaySeconds)) {
            return true;
        }
        Task task = requireTask(taskId);
        executorCallbackSupport.validateExecutorCallback(task, callbackContext, "延迟任务回队列");
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能延迟回队列");

        String previousStatus = task.getStatus();
        int safeDelaySeconds = normalizeDeferSeconds(delaySeconds);
        LocalDateTime nextQueuedTime = LocalDateTime.now().plusSeconds(safeDelaySeconds);
        String safeReason = logSupport.defaultText(reason, "执行器主动退避，任务延迟回队列");
        int nextDeferCount = safeDeferCount(task) + 1;
        int maxDeferCount = normalizeMaxDeferCount(task.getMaxDeferCount());

        runSupport.finishCurrentRunIfPresent(task, TaskExecutionRunState.DEFERRED,
                safeReason + ", deferCount=" + nextDeferCount + ", maxDeferCount=" + maxDeferCount);

        if (nextDeferCount > maxDeferCount) {
            moveToDeadLetterAfterExcessiveDefers(task, previousStatus, safeReason, nextDeferCount,
                    maxDeferCount, callbackContext);
            idempotencySupport.markSucceeded(taskId, "DEFER", callbackContext, TaskStatus.DEAD_LETTER);
            return true;
        }

        task.setStatus(TaskStatus.DEFERRED);
        task.setQueuedTime(nextQueuedTime);
        task.setDeferCount(nextDeferCount);
        task.setMaxDeferCount(maxDeferCount);
        task.setResult("任务已延迟回队列: " + safeReason);
        clearExecutorLease(task);
        task.setCurrentExecutionRunId(null);
        task.setStartTime(null);
        task.setEndTime(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "DEFER", previousStatus, TaskStatus.DEFERRED,
                "执行器主动退避，任务已延迟回队列",
                "delaySeconds=" + safeDelaySeconds
                        + ", nextQueuedTime=" + nextQueuedTime
                        + ", deferCount=" + nextDeferCount
                        + ", maxDeferCount=" + maxDeferCount
                        + ", reason=" + safeReason
                        + ", " + executorCallbackSupport.callbackDetails(callbackContext, null));
        idempotencySupport.markSucceeded(taskId, "DEFER", callbackContext, TaskStatus.DEFERRED);
        return true;
    }

    private void moveToDeadLetterAfterExcessiveDefers(Task task,
                                                      String previousStatus,
                                                      String reason,
                                                      int deferCount,
                                                      int maxDeferCount,
                                                      TaskExecutionCallbackContext callbackContext) {
        task.setStatus(TaskStatus.DEAD_LETTER);
        task.setQueuedTime(null);
        task.setDeferCount(deferCount);
        task.setMaxDeferCount(maxDeferCount);
        task.setResult("任务连续延迟回队列超过上限，已进入死信状态: " + reason);
        clearExecutorLease(task);
        task.setCurrentExecutionRunId(null);
        task.setEndTime(LocalDateTime.now());
        task.setAttentionRequired(true);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(task.getId(), "DEFER_DEAD_LETTER", previousStatus, TaskStatus.DEAD_LETTER,
                "任务连续退避超过上限，已进入死信状态",
                "deferCount=" + deferCount
                        + ", maxDeferCount=" + maxDeferCount
                        + ", reason=" + reason
                        + ", " + executorCallbackSupport.callbackDetails(callbackContext, null));
        log.warn("任务连续退避超过上限，进入死信状态，taskId={}, deferCount={}, maxDeferCount={}, reason={}",
                task.getId(), deferCount, maxDeferCount, reason);
    }

    private void resetTaskForRetry(Task task) {
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setCheckpoint(null);
        task.setStartTime(null);
        task.setEndTime(null);
        task.setResult(null);
        task.setRetryCount(safeRetryCount(task) + 1);
        task.setDeferCount(0);
        task.setMaxDeferCount(normalizeMaxDeferCount(task.getMaxDeferCount()));
        task.setQueuedTime(LocalDateTime.now());
        clearExecutorLease(task);
        task.setCurrentExecutionRunId(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
    }

    private void clearExecutorLease(Task task) {
        task.setCurrentExecutorId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
    }

    private Task requireTask(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + taskId);
        }
        return task;
    }

    private void ensureStatus(Task task, String expectedStatus, String message) {
        if (!expectedStatus.equals(task.getStatus())) {
            throw new IllegalStateException(message);
        }
    }

    private boolean isDuplicateCallback(Long taskId,
                                        String action,
                                        TaskExecutionCallbackContext callbackContext,
                                        String requestDigest) {
        /*
         * 幂等检查放在加载任务和状态流转之前，是为了尽量降低重复回调带来的数据库写放大。
         * 首次请求会在 task_callback_idempotency 中插入 PROCESSING 记录并继续执行业务；
         * 重复请求会命中唯一键冲突并直接返回成功，不再重复推进任务状态。
         */
        return idempotencySupport.isDuplicateCallback(taskId, action, callbackContext, requestDigest);
    }

    private int normalizeDeferSeconds(Integer delaySeconds) {
        if (delaySeconds == null) {
            return DEFAULT_DEFER_SECONDS;
        }
        return Math.max(1, Math.min(delaySeconds, MAX_DEFER_SECONDS));
    }

    private int normalizeMaxDeferCount(Integer maxDeferCount) {
        if (maxDeferCount == null) {
            return DEFAULT_MAX_DEFER_COUNT;
        }
        return Math.max(0, Math.min(maxDeferCount, 10_000));
    }

    private int safeDeferCount(Task task) {
        return task.getDeferCount() == null ? 0 : Math.max(0, task.getDeferCount());
    }

    private int safeRetryCount(Task task) {
        return task.getRetryCount() == null ? 0 : Math.max(0, task.getRetryCount());
    }
}
