package com.czh.datasmart.govern.task.service.support;

import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
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
 * @Date: 2026/05/05 23:52
 * @Description DataSmart Govern Backend - TaskAdminOperationSupport.java
 * @Version:1.0.0
 *
 * 任务管理员强控操作支持组件。
 *
 * <p>管理员动作不是普通用户生命周期动作的简单别名，而是商业化运维后台必须具备的事故处置能力。
 * 例如下游数据库故障时需要冻结未执行任务，worker 集群异常时需要强制取消运行任务，
 * 外部依赖恢复后需要忽略普通重试上限做一次受控补偿。
 *
 * <p>这些动作统一放在本组件，是为了让强控语义、权限校验、审计日志和状态边界集中维护，
 * 防止 TaskServiceImpl 再次变成“所有特殊情况都塞进去”的胖服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskAdminOperationSupport {

    private static final int DEFAULT_MAX_DEFER_COUNT = 20;

    private final TaskMapper taskMapper;
    private final TaskOperationPermissionSupport permissionSupport;
    private final TaskExecutionRunSupport runSupport;
    private final TaskExecutionLogSupport logSupport;
    private final TaskDataScopeSupport dataScopeSupport;

    /**
     * 管理员强制暂停任务。
     *
     * <p>普通 pause 只允许 RUNNING -> PAUSED；强制暂停额外允许 PENDING -> PAUSED。
     * 这服务于真实运营场景：下游依赖故障、租户资源超额、模板配置风险等情况下，
     * 运维人员需要先阻断未开始任务继续被调度。
     */
    public Task forcePauseTask(Long taskId, String reason, TaskActorContext actorContext) {
        permissionSupport.validateAdminOperationPermission(actorContext);
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "强制暂停任务");
        if (!TaskStatus.PENDING.equals(task.getStatus()) && !TaskStatus.RUNNING.equals(task.getStatus())) {
            throw new IllegalStateException("只有待执行或运行中任务才能强制暂停，当前状态=" + task.getStatus());
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PAUSED);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "ADMIN_FORCE_PAUSE", previousStatus, TaskStatus.PAUSED,
                "管理员强制暂停任务", logSupport.adminDetails(reason, actorContext), logSupport.actorLabel(actorContext));
        log.warn("管理员强制暂停任务，taskId={}, actorRole={}, actorId={}, reason={}",
                taskId, actorContext.actorRole(), actorContext.actorId(), reason);
        return task;
    }

    /**
     * 管理员恢复任务。
     *
     * <p>恢复选择 PAUSED -> PENDING，而不是 PAUSED -> RUNNING。
     * RUNNING 表示已经被执行器实际持有，管理员点击恢复并不能证明有 worker 接手；
     * 放回 PENDING 更符合调度模型，由执行器重新认领并建立新的租约。
     */
    public Task forceResumeTask(Long taskId, String reason, TaskActorContext actorContext) {
        permissionSupport.validateAdminOperationPermission(actorContext);
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "强制恢复任务");
        ensureStatus(task, TaskStatus.PAUSED, "只有已暂停任务才能由管理员恢复");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PENDING);
        task.setQueuedTime(LocalDateTime.now());
        task.setCurrentExecutorId(null);
        task.setCurrentExecutionRunId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "ADMIN_FORCE_RESUME", previousStatus, TaskStatus.PENDING,
                "管理员恢复任务并放回待调度队列", logSupport.adminDetails(reason, actorContext), logSupport.actorLabel(actorContext));
        return task;
    }

    /**
     * 管理员强制取消任务。
     *
     * <p>强制取消用于止损，不等同于执行失败。它会结束当前 run 并清理执行器租约，
     * 避免 worker 后续继续心跳污染已经取消的任务。
     */
    public Task forceCancelTask(Long taskId, String reason, TaskActorContext actorContext) {
        permissionSupport.validateAdminOperationPermission(actorContext);
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "强制取消任务");
        if (TaskStatus.SUCCESS.equals(task.getStatus()) || TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new IllegalStateException("已成功或已取消任务不能再次强制取消，当前状态=" + task.getStatus());
        }

        String previousStatus = task.getStatus();
        String safeReason = logSupport.defaultText(reason, "管理员强制取消任务");
        task.setStatus(TaskStatus.CANCELLED);
        task.setEndTime(LocalDateTime.now());
        task.setResult(safeReason);
        task.setCurrentExecutorId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        runSupport.finishCurrentRunIfPresent(task, TaskExecutionRunState.CANCELLED, safeReason);

        logSupport.saveExecutionLog(taskId, "ADMIN_FORCE_CANCEL", previousStatus, TaskStatus.CANCELLED,
                "管理员强制取消任务", logSupport.adminDetails(reason, actorContext), logSupport.actorLabel(actorContext));
        return task;
    }

    /**
     * 管理员强制重试任务。
     *
     * <p>普通 retry 必须遵守最大重试次数；强制 retry 可以在填写原因后忽略上限，
     * 用于外部依赖恢复、人工确认可补偿、死信任务重新入队等受控场景。
     */
    public Task forceRetryTask(Long taskId, String reason, Boolean ignoreRetryLimit, TaskActorContext actorContext) {
        permissionSupport.validateAdminOperationPermission(actorContext);
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "强制重试任务");
        if (TaskStatus.SUCCESS.equals(task.getStatus())) {
            throw new IllegalStateException("已成功任务不应通过 retry 覆盖结果，后续应使用 replay/backfill 能力");
        }
        if (!TaskStatus.FAILED.equals(task.getStatus())
                && !TaskStatus.CANCELLED.equals(task.getStatus())
                && !TaskStatus.DEAD_LETTER.equals(task.getStatus())
                && !TaskStatus.PAUSED.equals(task.getStatus())) {
            throw new IllegalStateException("只有失败、已取消、死信或已暂停任务才能强制重试，当前状态=" + task.getStatus());
        }
        if (!Boolean.TRUE.equals(ignoreRetryLimit) && safeRetryCount(task) >= task.getMaxRetryCount()) {
            throw new IllegalStateException("任务已超过最大重试次数，如确需补偿请使用 ignoreRetryLimit=true 并填写原因");
        }

        String previousStatus = task.getStatus();
        resetTaskForRetry(task);
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "ADMIN_FORCE_RETRY", previousStatus, TaskStatus.PENDING,
                "管理员强制重试任务",
                logSupport.adminDetails(reason, actorContext)
                        + ", ignoreRetryLimit=" + Boolean.TRUE.equals(ignoreRetryLimit)
                        + ", retryCount=" + task.getRetryCount(),
                logSupport.actorLabel(actorContext));
        return task;
    }

    /**
     * 管理员覆盖任务优先级。
     *
     * <p>优先级会影响调度公平性和 SLA，因此必须是显式管理员动作并写入日志。
     * 后续真实队列落地后，这里还应触发队列重排、租户公平性检查和 SLA 风险重算。
     */
    public Task overridePriority(Long taskId, String priority, String reason, TaskActorContext actorContext) {
        permissionSupport.validateAdminOperationPermission(actorContext);
        Task task = requireTask(taskId);
        dataScopeSupport.validateTaskInActorScope(task, actorContext, "覆盖任务优先级");
        if (TaskStatus.SUCCESS.equals(task.getStatus()) || TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new IllegalStateException("已成功或已取消任务不允许调整优先级，当前状态=" + task.getStatus());
        }

        String previousPriority = task.getPriority();
        String normalizedPriority = TaskPriority.normalize(priority);
        task.setPriority(normalizedPriority);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);

        logSupport.saveExecutionLog(taskId, "ADMIN_OVERRIDE_PRIORITY", task.getStatus(), task.getStatus(),
                "管理员覆盖任务优先级",
                logSupport.adminDetails(reason, actorContext)
                        + ", fromPriority=" + previousPriority
                        + ", toPriority=" + normalizedPriority,
                logSupport.actorLabel(actorContext));
        return task;
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
        task.setCurrentExecutorId(null);
        task.setCurrentExecutionRunId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
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

    private int normalizeMaxDeferCount(Integer maxDeferCount) {
        if (maxDeferCount == null) {
            return DEFAULT_MAX_DEFER_COUNT;
        }
        return Math.max(0, Math.min(maxDeferCount, 10_000));
    }

    private int safeRetryCount(Task task) {
        return task.getRetryCount() == null ? 0 : Math.max(0, task.getRetryCount());
    }
}
