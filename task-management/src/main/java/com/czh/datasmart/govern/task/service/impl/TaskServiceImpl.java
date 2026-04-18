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
 * 任务服务实现。
 * <p>
 * 这一层是任务模块真正承载业务规则的地方，而不是简单地把 Controller 转发到 Mapper。
 * 在当前项目里，任务管理模块承担的是平台中枢角色之一，因此服务层需要清楚表达：
 * 1. 任务的生命周期如何流转。
 * 2. 哪些状态允许执行哪些操作。
 * 3. 哪些字段应该在状态变化时被同步维护。
 * 4. 为什么每个关键动作都要留下执行日志。
 * <p>
 * 当前实现可以把它理解成一个“轻量任务状态机”：
 * PENDING -> RUNNING -> SUCCESS / FAILED
 * RUNNING -> PAUSED -> RUNNING
 * RUNNING / PAUSED / PENDING -> CANCELLED
 * FAILED / CANCELLED -> PENDING（通过 retry 进入下一轮）
 * <p>
 * 后续即使接入 Kafka、调度器、智能体执行器，这一层仍然是规则中心，
 * 因为执行介质可以变化，但状态管理和持久化语义不能漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    /**
     * 执行日志数据访问对象。
     * <p>
     * 当前我们刻意把“任务当前状态”和“任务执行历史”拆成两张表：
     * - task：保存当前快照，适合列表和详情页直接查询。
     * - task_execution_log：保存变化轨迹，适合追踪、审计、复盘。
     * <p>
     * 这是很常见的业务建模方式，优点是查询当前状态和回看历史都比较自然。
     */
    private final TaskExecutionLogMapper taskExecutionLogMapper;

    /**
     * 创建任务。
     * <p>
     * 这里不仅是“插入一条记录”，还顺带完成了几个关键初始化动作：
     * 1. 归一化 priority，避免数据库里同时出现 HIGH / high / High 这类脏数据。
     * 2. 初始状态固定为 PENDING，表达“任务已登记、但尚未真正开始执行”。
     * 3. retryCount 和 maxRetryCount 一起初始化，给未来的失败恢复预留边界。
     * 4. 创建完成后立即写一条 CREATE 日志，让生命周期从第一步就可追踪。
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
        task.setMaxRetryCount(maxRetryCount != null && maxRetryCount >= 0 ? maxRetryCount : 3);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());

        save(task);
        saveExecutionLog(task.getId(), "CREATE", null, TaskStatus.PENDING, "Task created", task.getParams());
        log.info("Created task: {}", task.getId());
        return task;
    }

    /**
     * 启动任务。
     * <p>
     * 为什么只允许 PENDING -> RUNNING：
     * 1. 避免重复启动已经运行中的任务。
     * 2. 避免跳过待调度阶段，导致生命周期混乱。
     * 3. 让状态流转更像一个明确的业务状态机，而不是任意字符串赋值。
     */
    @Override
    @Transactional
    public boolean startTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.PENDING, "Only pending tasks can be started");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.RUNNING);
        task.setStartTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        // 每次关键状态迁移都写日志，是为了让“当前快照”和“历史轨迹”同时存在。
        saveExecutionLog(taskId, "START", previousStatus, TaskStatus.RUNNING, "Task started", null);
        log.info("Started task: {}", taskId);
        return true;
    }

    /**
     * 暂停任务。
     * <p>
     * 暂停只对 RUNNING 状态有意义，因为只有正在执行的任务才存在“先停一下”的业务动作。
     * 如果任务已经完成或本来就没启动，再暂停就是无意义操作。
     * <p>
     * 暂停时会保留 checkpoint，这样后续 resume 才有机会从断点继续。
     */
    @Override
    @Transactional
    public boolean pauseTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.RUNNING, "Only running tasks can be paused");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PAUSED);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        saveExecutionLog(taskId, "PAUSE", previousStatus, TaskStatus.PAUSED, "Task paused", task.getCheckpoint());
        log.info("Paused task: {}", taskId);
        return true;
    }

    /**
     * 恢复暂停任务。
     * <p>
     * 这里不是新建任务，而是让同一条任务记录从 PAUSED 回到 RUNNING。
     * 这样 taskId 不会变，日志链路也保持连续，更适合作为后续运维和学习时的跟踪对象。
     */
    @Override
    @Transactional
    public boolean resumeTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.PAUSED, "Only paused tasks can be resumed");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.RUNNING);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        saveExecutionLog(taskId, "RESUME", previousStatus, TaskStatus.RUNNING, "Task resumed", task.getCheckpoint());
        log.info("Resumed task: {}", taskId);
        return true;
    }

    /**
     * 取消任务。
     * <p>
     * 取消和失败是两个不同概念：
     * - 失败：执行过程中出现错误。
     * - 取消：人为或系统主动终止，不一定代表执行逻辑有 bug。
     * <p>
     * 已经 SUCCESS 或 FAILED 的任务不允许再取消，
     * 因为它们已经是明确终态，再改成 CANCELLED 会破坏业务语义。
     */
    @Override
    @Transactional
    public boolean cancelTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        if (TaskStatus.SUCCESS.equals(task.getStatus()) || TaskStatus.FAILED.equals(task.getStatus())) {
            throw new IllegalStateException("Completed tasks cannot be cancelled");
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.CANCELLED);
        task.setEndTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        saveExecutionLog(taskId, "CANCEL", previousStatus, TaskStatus.CANCELLED, "Task cancelled", null);
        log.info("Cancelled task: {}", taskId);
        return true;
    }

    /**
     * 重试任务。
     * <p>
     * 这是当前阶段比较关键的一段业务逻辑，因为它体现了“可恢复任务”的基本设计思想。
     * 重试不是简单再调一次 start，而是要显式地重置一批字段：
     * 1. 状态回到 PENDING，让任务重新进入待调度阶段。
     * 2. progress 归零，因为新一轮执行不能继承旧进度。
     * 3. startTime、endTime、result 清空，避免旧执行结果污染新一轮。
     * 4. retryCount + 1，并和 maxRetryCount 比较，防止无限重试。
     * <p>
     * 这里只允许 FAILED 或 CANCELLED 重试，是为了避免正常执行中的任务被错误重置。
     */
    @Override
    @Transactional
    public Task retryTask(Long taskId) {
        Task task = getRequiredTask(taskId);
        if (!TaskStatus.FAILED.equals(task.getStatus()) && !TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new IllegalStateException("Only failed or cancelled tasks can be retried");
        }
        if (task.getRetryCount() >= task.getMaxRetryCount()) {
            throw new IllegalStateException("Task retry limit exceeded");
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setStartTime(null);
        task.setEndTime(null);
        task.setResult(null);
        task.setUpdateTime(LocalDateTime.now());
        task.setRetryCount(task.getRetryCount() + 1);
        updateById(task);

        saveExecutionLog(taskId, "RETRY", previousStatus, TaskStatus.PENDING, "Task retried",
                "retryCount=" + task.getRetryCount());
        log.info("Retried task: {}", taskId);
        return task;
    }

    /**
     * 更新任务进度。
     * <p>
     * 当前允许 RUNNING 和 PAUSED 两种状态更新进度，是为了兼容两类现实场景：
     * 1. 运行中持续回写执行进度。
     * 2. 暂停前最后一次持久化 checkpoint，确保恢复时有依据。
     * <p>
     * 这里写日志时 fromStatus 和 toStatus 相同，因为这次操作本质上是“同状态下的过程更新”，
     * 不是一次状态跳转。
     */
    @Override
    @Transactional
    public boolean updateProgress(Long taskId, Integer progress, String checkpoint) {
        Task task = getRequiredTask(taskId);
        if (!TaskStatus.RUNNING.equals(task.getStatus()) && !TaskStatus.PAUSED.equals(task.getStatus())) {
            throw new IllegalStateException("Only running or paused tasks can update progress");
        }

        task.setProgress(progress);
        task.setCheckpoint(checkpoint);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        saveExecutionLog(taskId, "PROGRESS", task.getStatus(), task.getStatus(), "Task progress updated", checkpoint);
        log.info("Updated progress for task {}: {}%", taskId, progress);
        return true;
    }

    /**
     * 标记任务完成。
     * <p>
     * 只允许 RUNNING -> SUCCESS，目的是把“完成”限定为明确的执行终态。
     * 同时把 progress 设置为 100，保证主表中的快照对外是自洽的。
     */
    @Override
    @Transactional
    public boolean completeTask(Long taskId, String result) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.RUNNING, "Only running tasks can be completed");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.SUCCESS);
        task.setProgress(100);
        task.setResult(result);
        task.setEndTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        saveExecutionLog(taskId, "COMPLETE", previousStatus, TaskStatus.SUCCESS, "Task completed", result);
        log.info("Completed task: {}", taskId);
        return true;
    }

    /**
     * 标记任务失败。
     * <p>
     * 失败原因同时写入 task.result 和 task_execution_log.details，
     * 这是因为两者承担不同职责：
     * - 主表 result：便于快速看到最后一次失败摘要。
     * - 日志表 details：便于回看失败过程和上下文。
     */
    @Override
    @Transactional
    public boolean failTask(Long taskId, String errorMessage) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.RUNNING, "Only running tasks can be marked as failed");

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.FAILED);
        task.setResult(errorMessage);
        task.setEndTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        saveExecutionLog(taskId, "FAIL", previousStatus, TaskStatus.FAILED, "Task failed", errorMessage);
        log.error("Failed task {}: {}", taskId, errorMessage);
        return true;
    }

    /**
     * 查询执行日志。
     * <p>
     * 这里先校验任务是否存在，再查日志，是为了避免“任务不存在”和“任务存在但暂无日志”
     * 这两种语义被同样的空列表掩盖掉。
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
     * 获取必存在的任务。
     * <p>
     * 这是一个很常见的服务层收口方法：把“查询 + 不存在校验”封装起来，
     * 避免每个业务方法重复写同样的模板代码。
     */
    private Task getRequiredTask(Long taskId) {
        Task task = getById(taskId);
        if (task == null) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }
        return task;
    }

    /**
     * 状态机守卫。
     * <p>
     * 状态类业务最容易出现的问题，就是在不合法的状态上执行了不合法的动作。
     * 这个方法的意义就是把“期望状态校验”显式化，减少隐性 bug。
     */
    private void ensureStatus(Task task, String expectedStatus, String message) {
        if (!expectedStatus.equals(task.getStatus())) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 写入任务执行日志。
     * <p>
     * 这里选择把关键事件写到数据库，而不仅仅打应用日志，原因是：
     * 1. 数据库日志更结构化，适合按任务维度查询。
     * 2. 应用日志更偏技术排障，数据库日志更偏业务审计。
     * 3. 后续做任务复盘、统计分析、监控页面时，结构化数据更容易复用。
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
