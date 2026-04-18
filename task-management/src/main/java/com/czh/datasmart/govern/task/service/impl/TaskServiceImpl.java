package com.czh.datasmart.govern.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.mapper.TaskExecutionLogMapper;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.support.TaskPriority;
import com.czh.datasmart.govern.task.support.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:18
 * @Description DataSmart Govern Backend - TaskServiceImpl.java
 * @Version:1.0.0
 *
 * 任务服务实现。
 * 这一层是任务模块真正承载业务规则的核心位置，不是简单的 CRUD 转发层。
 * 当前它主要负责四件事：
 * 1. 维护任务生命周期状态机。
 * 2. 维护任务快照字段，如进度、结果、开始时间、结束时间。
 * 3. 对不合法状态转换做明确阻止。
 * 4. 把关键业务动作落成结构化执行日志。
 *
 * 可以把它理解成一个“轻量任务状态机服务”。
 * 虽然还没有引入完整工作流引擎，但现阶段已经把最关键的状态守卫、重试边界、轨迹记录建立起来了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    /**
     * 执行日志 Mapper。
     * 当前刻意把“主表快照”和“历史轨迹”拆成两张表，是任务系统里很常见的做法：
     * - 主表适合列表和详情快速查询。
     * - 日志表适合排障、审计和复盘。
     */
    private final TaskExecutionLogMapper taskExecutionLogMapper;

    /**
     * 创建任务。
     * 这里不只是插入一条数据库记录，还会同步完成一系列初始化动作：
     * 1. 归一化优先级，避免脏数据进入数据库。
     * 2. 把状态固定为 PENDING，表示任务已登记但尚未真正执行。
     * 3. 初始化进度、重试次数和最大重试次数。
     * 4. 写入第一条 CREATE 执行日志，让任务从诞生开始就有可追踪轨迹。
     */
    @Override
    @Transactional
    public Task createTask(String name, String description, String type, String params, String priority,
                           Integer maxRetryCount) {
        Task task = new Task();
        task.setName(name);
        task.setDescription(description);
        task.setType(type);
        task.setParams(params);
        task.setPriority(TaskPriority.normalize(priority));
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setMaxRetryCount(maxRetryCount != null ? maxRetryCount : 3);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());

        save(task);
        saveExecutionLog(task.getId(), "CREATE", null, TaskStatus.PENDING, "任务已创建", task.getParams());
        log.info("创建任务成功，taskId={}", task.getId());
        return task;
    }

    /**
     * 启动任务。
     * 当前只允许 PENDING -> RUNNING，
     * 目的是让任务生命周期保持清晰，避免处于其他状态的任务被误启动。
     */
    @Override
    @Transactional
    public boolean startTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.PENDING, "只有待执行任务才能启动");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.RUNNING);
        task.setStartTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "START", previousStatus, TaskStatus.RUNNING, "任务已启动", null);
        log.info("启动任务成功，taskId={}", taskId);
        return true;
    }

    /**
     * 暂停任务。
     * 暂停只对 RUNNING 状态有意义，因为只有正在执行的任务才存在“暂停”的业务动作。
     */
    @Override
    @Transactional
    public boolean pauseTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能暂停");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PAUSED);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "PAUSE", previousStatus, TaskStatus.PAUSED, "任务已暂停", task.getCheckpoint());
        log.info("暂停任务成功，taskId={}", taskId);
        return true;
    }

    /**
     * 恢复任务。
     * 恢复不是重新创建任务，而是让同一条任务记录从 PAUSED 回到 RUNNING，
     * 这样任务 ID、上下文和日志链路都能保持连续。
     */
    @Override
    @Transactional
    public boolean resumeTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.PAUSED, "只有已暂停任务才能恢复");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.RUNNING);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "RESUME", previousStatus, TaskStatus.RUNNING, "任务已恢复", task.getCheckpoint());
        log.info("恢复任务成功，taskId={}", taskId);
        return true;
    }

    /**
     * 取消任务。
     * 取消和失败不是同一回事：
     * - 失败表示执行过程中出现异常。
     * - 取消表示用户或系统主动终止。
     *
     * 当前不允许对已成功或已失败任务再执行取消，
     * 因为它们已经进入明确终态，强行改成 CANCELLED 会破坏业务语义。
     */
    @Override
    @Transactional
    public boolean cancelTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        if (TaskStatus.SUCCESS.equals(task.getStatus()) || TaskStatus.FAILED.equals(task.getStatus())) {
            throw new IllegalStateException("已结束任务不能取消");
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.CANCELLED);
        task.setEndTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "CANCEL", previousStatus, TaskStatus.CANCELLED, "任务已取消", null);
        log.info("取消任务成功，taskId={}", taskId);
        return true;
    }

    /**
     * 重试任务。
     * 这是任务系统中很关键的恢复能力。
     * 重试不是简单再调一次 start，而是要显式重置一批执行态字段：
     * 1. 状态回到 PENDING，重新进入待调度阶段。
     * 2. 进度清零，因为这是新一轮执行。
     * 3. 开始时间、结束时间和结果清空，避免旧执行痕迹污染新一轮结果。
     * 4. retryCount 自增，并与最大重试次数比较，防止无限重试。
     */
    @Override
    @Transactional
    public Task retryTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        if (!TaskStatus.FAILED.equals(task.getStatus()) && !TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new IllegalStateException("只有失败或已取消任务才能重试");
        }
        if (task.getRetryCount() >= task.getMaxRetryCount()) {
            throw new IllegalStateException("任务已超过最大重试次数");
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setCheckpoint(null);
        task.setStartTime(null);
        task.setEndTime(null);
        task.setResult(null);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "RETRY", previousStatus, TaskStatus.PENDING, "任务已重试",
                "retryCount=" + task.getRetryCount());
        log.info("重试任务成功，taskId={}, retryCount={}", taskId, task.getRetryCount());
        return task;
    }

    /**
     * 更新任务进度。
     * 当前允许 RUNNING 和 PAUSED 两种状态更新进度：
     * - RUNNING 场景用于持续上报执行进展。
     * - PAUSED 场景用于在暂停前后落最后一个 checkpoint，便于恢复。
     *
     * 这类更新不一定伴随状态变化，所以日志里的 fromStatus 和 toStatus 可能相同。
     */
    @Override
    @Transactional
    public boolean updateProgress(Long taskId, Integer progress, String checkpoint) {
        Task task = getRequiredTask(taskId);
        if (!TaskStatus.RUNNING.equals(task.getStatus()) && !TaskStatus.PAUSED.equals(task.getStatus())) {
            throw new IllegalStateException("只有运行中或已暂停任务才能更新进度");
        }

        task.setProgress(progress);
        task.setCheckpoint(checkpoint);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "PROGRESS", task.getStatus(), task.getStatus(), "任务进度已更新", checkpoint);
        log.info("更新任务进度成功，taskId={}, progress={}", taskId, progress);
        return true;
    }

    /**
     * 标记任务完成。
     * 当前只允许 RUNNING -> SUCCESS，
     * 并同步把进度补到 100，保证任务快照对外呈现是自洽的。
     */
    @Override
    @Transactional
    public boolean completeTask(Long taskId, String result) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能标记完成");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.SUCCESS);
        task.setProgress(100);
        task.setResult(result);
        task.setEndTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "COMPLETE", previousStatus, TaskStatus.SUCCESS, "任务已完成", result);
        log.info("完成任务成功，taskId={}", taskId);
        return true;
    }

    /**
     * 标记任务失败。
     * 失败原因会同时写入主表 result 和日志 details：
     * - 主表 result 便于快速看到最近一次失败摘要。
     * - 日志 details 便于沿时间线回看详细上下文。
     */
    @Override
    @Transactional
    public boolean failTask(Long taskId, String errorMessage) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能标记失败");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.FAILED);
        task.setResult(errorMessage);
        task.setEndTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "FAIL", previousStatus, TaskStatus.FAILED, "任务执行失败", errorMessage);
        log.error("任务执行失败，taskId={}, errorMessage={}", taskId, errorMessage);
        return true;
    }

    /**
     * 查询任务执行日志。
     * 先校验任务存在，再查询日志，
     * 可以避免“任务不存在”和“任务存在但暂时没有日志”被同样的空列表掩盖。
     */
    @Override
    public List<TaskExecutionLog> listExecutionLogs(Long taskId) {
        getRequiredTask(taskId);
        return taskExecutionLogMapper.selectList(new LambdaQueryWrapper<TaskExecutionLog>()
                .eq(TaskExecutionLog::getTaskId, taskId)
                .orderByDesc(TaskExecutionLog::getCreateTime)
                .orderByDesc(TaskExecutionLog::getId));
    }

    /**
     * 查询必须存在的任务。
     * 这是服务层里非常常见的收口方法，用于消除重复的“查询 + 判空”模板代码。
     */
    private Task getRequiredTask(Long taskId) {
        Task task = getById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + taskId);
        }
        return task;
    }

    /**
     * 状态守卫。
     * 状态型业务最容易出现的问题，就是在错误状态上执行了错误动作。
     * 这个方法的目的就是把“状态前置条件”显式收拢起来。
     */
    private void ensureStatus(Task task, String expectedStatus, String message) {
        if (!expectedStatus.equals(task.getStatus())) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 写入结构化执行日志。
     * 与普通应用日志相比，数据库执行日志更适合按任务维度做结构化查询，
     * 后续任务监控页、审计报表、失败分析都可以直接复用这些数据。
     */
    private void saveExecutionLog(Long taskId, String action, String fromStatus, String toStatus,
                                  String message, String details) {
        TaskExecutionLog executionLog = new TaskExecutionLog();
        executionLog.setTaskId(taskId);
        executionLog.setAction(action);
        executionLog.setFromStatus(fromStatus);
        executionLog.setToStatus(toStatus);
        executionLog.setMessage(message);
        executionLog.setOperator("system");
        executionLog.setDetails(details);
        executionLog.setCreateTime(LocalDateTime.now());
        taskExecutionLogMapper.insert(executionLog);
    }
}
