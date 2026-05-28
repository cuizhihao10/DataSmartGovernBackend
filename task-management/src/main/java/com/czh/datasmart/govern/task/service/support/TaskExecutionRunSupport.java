package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionHeartbeatRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskLeaseRecoveryResult;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.mapper.TaskExecutionRunMapper;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.support.TaskExecutionRunState;
import com.czh.datasmart.govern.task.support.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/05/05 23:51
 * @Description DataSmart Govern Backend - TaskExecutionRunSupport.java
 * @Version:1.0.0
 *
 * 任务执行租约与 run 记录支持组件。
 *
 * <p>任务平台从“手动改状态”走向“真实 worker 调度”时，最关键的能力就是：
 * 认领、租约、心跳、超时恢复和执行历史。
 * 这些逻辑如果长期放在 TaskServiceImpl 中，会让主服务同时承担生命周期、队列、权限、日志和执行器协议，
 * 后续接入多线程 worker、分布式调度、租户配额、公平调度时会很难演进。
 *
 * <p>本组件只关心“某一次执行尝试”的技术事实：
 * 哪个 executor 认领了任务、租约何时过期、心跳上报了什么进度、超时后如何安全收口。
 */
@Component
@RequiredArgsConstructor
public class TaskExecutionRunSupport {

    /**
     * 默认执行租约秒数。
     *
     * <p>60 秒适合当前模块的早期 worker 模式：足够覆盖短暂网络抖动，
     * 又不会让失联执行器长期占用任务。后续应按任务类型和执行器能力做配置化。
     */
    private static final long DEFAULT_LEASE_SECONDS = 60L;

    private final TaskMapper taskMapper;
    private final TaskExecutionRunMapper taskExecutionRunMapper;
    private final TaskOperationPermissionSupport permissionSupport;
    private final TaskExecutionLogSupport logSupport;
    private final TaskDataScopeSupport dataScopeSupport;

    /**
     * 执行器认领下一条可执行任务。
     *
     * <p>该方法实现轻量数据库租约模式：
     * 1. 先按优先级和排队时间查询候选任务；
     * 2. 再通过带状态条件的 UPDATE 抢占任务，避免多个 worker 同时拿到同一条任务；
     * 3. 抢占成功后创建 task_execution_run，记录本次执行尝试；
     * 4. 把 runId 回写主表，方便详情页和后续回调快速定位当前执行记录。
     */
    public TaskExecutionClaimResult claimNextTask(TaskExecutionClaimRequest request, TaskActorContext actorContext) {
        permissionSupport.validateExecutorOperationPermission(actorContext);
        validateClaimRequest(request);

        long leaseSeconds = request.getLeaseSeconds() == null ? DEFAULT_LEASE_SECONDS : request.getLeaseSeconds();
        Long tenantFilter = dataScopeSupport.resolveClaimTenantFilter(request.getTenantId(), actorContext);
        Task candidate = taskMapper.selectNextClaimCandidate(trimToNull(request.getTaskType()),
                tenantFilter, request.getOwnerId(), request.getProjectId());
        if (candidate == null) {
            return new TaskExecutionClaimResult(false, "当前没有可认领任务", null, null);
        }

        String previousStatus = candidate.getStatus();
        int claimed = taskMapper.claimTask(candidate.getId(), request.getExecutorId(), leaseSeconds);
        if (claimed == 0) {
            return new TaskExecutionClaimResult(false, "候选任务已被其他执行器抢先认领，请稍后重试", null, null);
        }

        Task claimedTask = requireTask(candidate.getId());
        TaskExecutionRun run = createExecutionRun(claimedTask, request.getExecutorId(), leaseSeconds, actorContext);
        claimedTask.setCurrentExecutionRunId(run.getId());
        taskMapper.updateById(claimedTask);

        logSupport.saveExecutionLog(claimedTask.getId(), "EXECUTOR_CLAIM", previousStatus, TaskStatus.RUNNING,
                "执行器认领任务",
                "executorId=" + request.getExecutorId()
                        + ", runId=" + run.getId()
                        + ", leaseSeconds=" + leaseSeconds
                        + ", previousStatus=" + previousStatus
                        + ", tenantId=" + claimedTask.getTenantId()
                        + ", ownerId=" + claimedTask.getOwnerId()
                        + ", projectId=" + claimedTask.getProjectId()
                        + ", traceId=" + logSupport.nullSafe(actorContext.traceId()),
                logSupport.actorLabel(actorContext));
        return new TaskExecutionClaimResult(true, "任务认领成功", requireTask(claimedTask.getId()), run);
    }

    /**
     * 执行器心跳续租。
     *
     * <p>心跳同时更新 task 主表和 execution_run：
     * 主表用于列表快速展示当前进度，run 表用于保存本次执行尝试的持续轨迹。
     * 只有持有当前租约的 executorId 才能续租，避免其他实例误写进度。
     */
    public TaskExecutionRun heartbeatExecution(Long runId,
                                               TaskExecutionHeartbeatRequest request,
                                               TaskActorContext actorContext) {
        permissionSupport.validateExecutorOperationPermission(actorContext);
        validateHeartbeatRequest(request);

        TaskExecutionRun run = taskExecutionRunMapper.selectById(runId);
        if (run == null) {
            throw new NoSuchElementException("任务执行记录不存在: " + runId);
        }
        if (!TaskExecutionRunState.RUNNING.equals(run.getState())) {
            throw new IllegalStateException("只有 RUNNING 执行记录才能上报心跳，当前状态=" + run.getState());
        }
        if (!request.getExecutorId().equals(run.getExecutorId())) {
            throw new IllegalStateException("执行器 ID 与当前执行记录不匹配");
        }

        Task task = requireTask(run.getTaskId());
        long leaseSeconds = request.getLeaseSeconds() == null ? DEFAULT_LEASE_SECONDS : request.getLeaseSeconds();
        Integer progress = request.getProgress() == null ? task.getProgress() : request.getProgress();
        String checkpoint = request.getCheckpoint() == null ? task.getCheckpoint() : request.getCheckpoint();
        int updated = taskMapper.heartbeatLease(task.getId(), request.getExecutorId(), progress, checkpoint, leaseSeconds);
        if (updated == 0) {
            throw new IllegalStateException("任务租约续期失败，可能任务已结束、已被超时恢复或执行器不匹配");
        }

        run.setProgress(progress);
        run.setCheckpoint(checkpoint);
        run.setHeartbeatAt(LocalDateTime.now());
        run.setLeaseExpireTime(LocalDateTime.now().plusSeconds(leaseSeconds));
        run.setUpdateTime(LocalDateTime.now());
        taskExecutionRunMapper.updateById(run);

        logSupport.saveExecutionLog(task.getId(), "EXECUTOR_HEARTBEAT", TaskStatus.RUNNING, TaskStatus.RUNNING,
                "执行器心跳续租",
                "executorId=" + request.getExecutorId()
                        + ", runId=" + runId
                        + ", progress=" + progress
                        + ", checkpoint=" + logSupport.defaultText(checkpoint, "")
                        + ", leaseSeconds=" + leaseSeconds,
                logSupport.actorLabel(actorContext));
        return taskExecutionRunMapper.selectById(runId);
    }

    /**
     * 恢复执行器租约超时的任务。
     *
     * <p>当前基线选择把超时任务标记为 FAILED 并设置 attentionRequired=true。
     * 这比自动回到 PENDING 更保守，因为执行器失联时无法确认外部副作用是否已经发生。
     * 对数据同步、通知发送、外部 API 调用等任务，盲目自动重跑可能造成重复写入或重复调用。
     */
    public TaskLeaseRecoveryResult recoverTimedOutExecutions(Integer limit, TaskActorContext actorContext) {
        permissionSupport.validateAdminOperationPermission(actorContext);
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<TaskExecutionRun> timedOutRuns = taskExecutionRunMapper.selectTimedOutRuns(safeLimit);
        int recovered = 0;
        for (TaskExecutionRun run : timedOutRuns) {
            Task task = taskMapper.selectById(run.getTaskId());
            if (task == null || !TaskStatus.RUNNING.equals(task.getStatus())) {
                continue;
            }
            int finished = taskExecutionRunMapper.finishRunningRun(run.getId(), TaskExecutionRunState.TIMEOUT,
                    "执行器心跳租约超时");
            if (finished == 0) {
                continue;
            }

            String previousStatus = task.getStatus();
            task.setStatus(TaskStatus.FAILED);
            task.setResult("执行器心跳租约超时，executorId=" + run.getExecutorId());
            task.setEndTime(LocalDateTime.now());
            task.setCurrentExecutorId(null);
            task.setHeartbeatTime(null);
            task.setLeaseExpireTime(null);
            task.setAttentionRequired(true);
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(task);

            logSupport.saveExecutionLog(task.getId(), "LEASE_TIMEOUT_RECOVER", previousStatus, TaskStatus.FAILED,
                    "执行器租约超时，任务已标记失败并需要运营关注",
                    "runId=" + run.getId()
                            + ", executorId=" + run.getExecutorId()
                            + ", leaseExpireTime=" + logSupport.nullSafe(run.getLeaseExpireTime())
                            + ", traceId=" + logSupport.nullSafe(actorContext.traceId()),
                    logSupport.actorLabel(actorContext));
            recovered++;
        }
        return new TaskLeaseRecoveryResult(timedOutRuns.size(), recovered, "租约超时恢复扫描完成");
    }

    /**
     * 查询某个任务的执行 run 历史。
     */
    public List<TaskExecutionRun> listExecutionRuns(Long taskId) {
        requireTask(taskId);
        return taskExecutionRunMapper.selectList(new LambdaQueryWrapper<TaskExecutionRun>()
                .eq(TaskExecutionRun::getTaskId, taskId)
                .orderByDesc(TaskExecutionRun::getRunNo)
                .orderByDesc(TaskExecutionRun::getId));
    }

    /**
     * 创建执行 run。
     *
     * <p>runNo 以任务为维度递增，能让运营人员清楚看到“第几次执行尝试”。
     * 这比只看全局自增 ID 更接近业务理解，也方便未来实现 selective retry、replay 和 backfill。
     */
    public TaskExecutionRun createExecutionRun(Task task,
                                               String executorId,
                                               long leaseSeconds,
                                               TaskActorContext actorContext) {
        Long maxRunNo = taskExecutionRunMapper.selectMaxRunNo(task.getId());
        TaskExecutionRun run = new TaskExecutionRun();
        run.setTaskId(task.getId());
        run.setRunNo(maxRunNo + 1);
        run.setExecutorId(executorId);
        run.setState(TaskExecutionRunState.RUNNING);
        run.setTriggerType("EXECUTOR_CLAIM");
        run.setTriggeredBy(actorContext == null ? null : actorContext.actorId());
        run.setStartedAt(LocalDateTime.now());
        run.setHeartbeatAt(LocalDateTime.now());
        run.setLeaseExpireTime(LocalDateTime.now().plusSeconds(leaseSeconds));
        run.setProgress(task.getProgress());
        run.setCheckpoint(task.getCheckpoint());
        run.setCreateTime(LocalDateTime.now());
        run.setUpdateTime(LocalDateTime.now());
        taskExecutionRunMapper.insert(run);
        return run;
    }

    /**
     * 结束当前 run。
     *
     * <p>任务主表和 run 表必须保持语义一致：如果任务已经 SUCCESS、FAILED 或 CANCELLED，
     * 但 execution_run 仍是 RUNNING，运营后台会看到互相矛盾的数据，超时恢复也可能误处理已结束任务。
     */
    public void finishCurrentRunIfPresent(Task task, String state, String errorMessage) {
        if (task.getCurrentExecutionRunId() == null) {
            return;
        }
        taskExecutionRunMapper.finishRunningRun(task.getCurrentExecutionRunId(), state, errorMessage);
    }

    private void validateClaimRequest(TaskExecutionClaimRequest request) {
        if (request == null || request.getExecutorId() == null || request.getExecutorId().isBlank()) {
            throw new IllegalStateException("执行器认领任务时必须提供 executorId");
        }
    }

    private void validateHeartbeatRequest(TaskExecutionHeartbeatRequest request) {
        if (request == null || request.getExecutorId() == null || request.getExecutorId().isBlank()) {
            throw new IllegalStateException("执行器心跳续租时必须提供 executorId");
        }
    }

    private Task requireTask(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + taskId);
        }
        return task;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
