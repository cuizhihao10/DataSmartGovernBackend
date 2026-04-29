package com.czh.datasmart.govern.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionHeartbeatRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskLeaseRecoveryResult;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueItemView;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueSummaryResponse;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.mapper.TaskExecutionLogMapper;
import com.czh.datasmart.govern.task.mapper.TaskExecutionRunMapper;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.support.TaskExecutionRunState;
import com.czh.datasmart.govern.task.support.TaskPriority;
import com.czh.datasmart.govern.task.support.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

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
     * 可以执行任务运维干预的角色集合。
     *
     * <p>任务运维动作会直接改变任务生命周期，不应开放给普通用户和服务账号。
     * 当前先允许三类角色：
     * 1. OPERATOR：面向运行故障、队列拥堵、依赖异常的日常处置；
     * 2. TENANT_ADMINISTRATOR：面向本租户任务的管理责任；
     * 3. PLATFORM_ADMINISTRATOR：面向全平台事故恢复和跨租户治理。
     *
     * <p>当前 task 表还没有 tenant_id / owner_id 字段，因此这里只能做角色级拦截。
     * 后续补齐租户字段后，应继续加入“只能管理本租户任务”的数据范围校验。
     */
    private static final Set<String> ADMIN_OPERATION_ROLES = Set.of(
            "OPERATOR",
            "TENANT_ADMINISTRATOR",
            "PLATFORM_ADMINISTRATOR"
    );

    /**
     * 可以认领任务或上报心跳的角色集合。
     *
     * <p>SERVICE_ACCOUNT 用于真实执行器、调度器、Agent Runtime 等机器身份；
     * OPERATOR 和 PLATFORM_ADMINISTRATOR 主要用于本地联调、故障演练和人工补偿。
     */
    private static final Set<String> EXECUTOR_OPERATION_ROLES = Set.of(
            "SERVICE_ACCOUNT",
            "OPERATOR",
            "PLATFORM_ADMINISTRATOR"
    );

    /**
     * 队列运营视图默认关注的状态集合。
     *
     * <p>普通任务列表可以查询所有状态，但运营视图默认应该聚焦“仍然会影响调度健康”的任务：
     * - PENDING：等待认领，可能出现积压；
     * - RUNNING：正在执行，可能出现租约/心跳风险；
     * - DEFERRED：容量背压后延迟回队列；
     * - DEAD_LETTER：已停止自动调度，需要人工关注；
     * - FAILED：执行失败，可能需要重试或复盘；
     * - PAUSED：被人工或系统暂停，可能需要恢复。
     *
     * <p>SUCCESS 和 CANCELLED 默认不查，因为它们已经不会继续影响队列推进；
     * 如需事故复盘，可以通过 includeTerminal=true 查看所有状态。
     */
    private static final Set<String> DEFAULT_OPERATIONAL_QUEUE_STATUSES = Set.of(
            TaskStatus.PENDING,
            TaskStatus.RUNNING,
            TaskStatus.DEFERRED,
            TaskStatus.DEAD_LETTER,
            TaskStatus.FAILED,
            TaskStatus.PAUSED
    );

    /**
     * 队列汇总中默认展示的状态顺序。
     *
     * <p>这里用 List 而不是 Set，是为了让响应里的 statusCounts 顺序稳定。
     * 稳定顺序对前端图表、测试断言和人工阅读都更友好。
     */
    private static final List<String> QUEUE_SUMMARY_STATUS_ORDER = List.of(
            TaskStatus.PENDING,
            TaskStatus.RUNNING,
            TaskStatus.DEFERRED,
            TaskStatus.DEAD_LETTER,
            TaskStatus.FAILED,
            TaskStatus.PAUSED,
            TaskStatus.RETRYING,
            TaskStatus.SUCCESS,
            TaskStatus.CANCELLED
    );

    /**
     * 默认执行租约秒数。
     */
    private static final long DEFAULT_LEASE_SECONDS = 60L;

    /**
     * 默认任务执行超时秒数。
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    /**
     * 执行器主动退避时默认延迟秒数。
     *
     * <p>这个默认值用于调用方没有传 delaySeconds 的情况。
     * 30 秒足够让短暂的本实例并发拥塞得到缓解，又不会让用户误以为任务长时间无响应。
     * 后续可以进一步把默认值做成配置，并支持按任务类型、租户等级、失败原因动态退避。
     */
    private static final int DEFAULT_DEFER_SECONDS = 30;

    /**
     * 默认最大连续退避次数。
     *
     * <p>20 次是一个偏保守的早期默认值。
     * 如果 `data-quality` 默认 30 秒退避一次，连续 20 次约等于 10 分钟仍无法获得执行资源，
     * 这通常已经值得运营人员关注：可能需要扩容 worker、调低任务提交速率、调整租户配额或检查某个数据源是否长期不可用。
     */
    private static final int DEFAULT_MAX_DEFER_COUNT = 20;

    /**
     * 队列积压预警秒数。
     *
     * <p>当前先用 10 分钟作为早期默认值。
     * 后续应按任务类型、优先级、租户 SLA、执行器容量和业务窗口动态调整。
     */
    private static final long QUEUE_AGING_WARNING_SECONDS = 600L;

    /**
     * 执行器主动退避允许的最大延迟秒数。
     *
     * <p>设置上限是为了避免某个错误执行器把任务延迟到很久以后，导致运营后台难以及时发现。
     * 真正需要长时间冻结任务时，应使用管理员 pause/cancel 等显式运维动作。
     */
    private static final int MAX_DEFER_SECONDS = 3600;

    /**
     * 执行日志 Mapper。
     * 当前刻意把“主表快照”和“历史轨迹”拆成两张表，是任务系统里很常见的做法：
     * - 主表适合列表和详情快速查询。
     * - 日志表适合排障、审计和复盘。
     */
    private final TaskExecutionLogMapper taskExecutionLogMapper;

    /**
     * 执行记录 Mapper。
     *
     * <p>执行记录是任务调度从“手动状态变更”走向“执行器认领和心跳续租”的关键表。
     */
    private final TaskExecutionRunMapper taskExecutionRunMapper;

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
                           Integer maxRetryCount, Integer maxDeferCount) {
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
        task.setDeferCount(0);
        task.setMaxDeferCount(normalizeMaxDeferCount(maxDeferCount));
        task.setQueuedTime(LocalDateTime.now());
        task.setAttentionRequired(false);
        task.setTimeoutSeconds(DEFAULT_TIMEOUT_SECONDS);
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
        task.setCurrentExecutorId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        finishCurrentRunIfPresent(task, TaskExecutionRunState.CANCELLED, "任务被取消");

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
        task.setDeferCount(0);
        task.setMaxDeferCount(normalizeMaxDeferCount(task.getMaxDeferCount()));
        task.setQueuedTime(LocalDateTime.now());
        task.setCurrentExecutorId(null);
        task.setCurrentExecutionRunId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "RETRY", previousStatus, TaskStatus.PENDING, "任务已重试",
                "retryCount=" + task.getRetryCount());
        log.info("重试任务成功，taskId={}, retryCount={}", taskId, task.getRetryCount());
        return task;
    }

    /**
     * 管理员强制暂停任务。
     *
     * <p>普通 pauseTask 只允许 RUNNING -> PAUSED。
     * 但真实生产场景中，运营人员经常需要暂停尚未开始执行的 PENDING 任务，例如：
     * - 下游数据库故障，暂时不要让待执行任务继续调度；
     * - 某个租户资源超额，需要暂停低优先级任务；
     * - 发现模板配置风险，需要先冻结相关任务。
     *
     * <p>因此强制暂停允许 PENDING/RUNNING -> PAUSED，并写入操作者、原因和 traceId。
     */
    @Override
    @Transactional
    public Task forcePauseTask(Long taskId, String reason, TaskActorContext actorContext) {
        validateAdminOperationPermission(actorContext);
        Task task = getRequiredTask(taskId);
        if (!TaskStatus.PENDING.equals(task.getStatus()) && !TaskStatus.RUNNING.equals(task.getStatus())) {
            throw new IllegalStateException("只有待执行或运行中任务才能强制暂停，当前状态=" + task.getStatus());
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PAUSED);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "ADMIN_FORCE_PAUSE", previousStatus, TaskStatus.PAUSED,
                "管理员强制暂停任务", adminDetails(reason, actorContext), actorLabel(actorContext));
        log.warn("管理员强制暂停任务，taskId={}, actorRole={}, actorId={}, reason={}",
                taskId, actorContext.actorRole(), actorContext.actorId(), reason);
        return task;
    }

    /**
     * 管理员恢复任务。
     *
     * <p>恢复时这里选择 PAUSED -> PENDING，而不是 PAUSED -> RUNNING。
     * 原因是 RUNNING 表示已经被执行器实际执行；如果只是管理员点击恢复，系统并不能证明执行器已经接手。
     * 放回 PENDING 更符合未来调度器模型：恢复后的任务重新进入可调度池，由调度器负责后续启动。
     */
    @Override
    @Transactional
    public Task forceResumeTask(Long taskId, String reason, TaskActorContext actorContext) {
        validateAdminOperationPermission(actorContext);
        Task task = getRequiredTask(taskId);
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
        updateById(task);

        saveExecutionLog(taskId, "ADMIN_FORCE_RESUME", previousStatus, TaskStatus.PENDING,
                "管理员恢复任务并放回待调度队列", adminDetails(reason, actorContext), actorLabel(actorContext));
        log.warn("管理员恢复任务，taskId={}, actorRole={}, actorId={}, reason={}",
                taskId, actorContext.actorRole(), actorContext.actorId(), reason);
        return task;
    }

    /**
     * 管理员强制取消任务。
     *
     * <p>强制取消用于运维止损，不等同于执行失败。
     * 当前允许取消 PENDING、RUNNING、PAUSED、FAILED、RETRYING：
     * - 对待执行/运行中/暂停任务，取消表示不再继续；
     * - 对 FAILED 任务，取消表示管理员确认该失败任务不再恢复；
     * - 对 SUCCESS/CANCELLED 这类明确终态，不允许重复覆盖。
     */
    @Override
    @Transactional
    public Task forceCancelTask(Long taskId, String reason, TaskActorContext actorContext) {
        validateAdminOperationPermission(actorContext);
        Task task = getRequiredTask(taskId);
        if (TaskStatus.SUCCESS.equals(task.getStatus()) || TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new IllegalStateException("已成功或已取消任务不能再次强制取消，当前状态=" + task.getStatus());
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.CANCELLED);
        task.setEndTime(LocalDateTime.now());
        task.setResult(defaultText(reason, "管理员强制取消任务"));
        task.setCurrentExecutorId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        finishCurrentRunIfPresent(task, TaskExecutionRunState.CANCELLED, defaultText(reason, "管理员强制取消任务"));

        saveExecutionLog(taskId, "ADMIN_FORCE_CANCEL", previousStatus, TaskStatus.CANCELLED,
                "管理员强制取消任务", adminDetails(reason, actorContext), actorLabel(actorContext));
        log.warn("管理员强制取消任务，taskId={}, actorRole={}, actorId={}, reason={}",
                taskId, actorContext.actorRole(), actorContext.actorId(), reason);
        return task;
    }

    /**
     * 管理员强制重试任务。
     *
     * <p>普通 retry 只允许 FAILED/CANCELLED 且必须遵守最大重试次数。
     * 强制重试扩展了两个商业化运维场景：
     * 1. FAILED/CANCELLED/PAUSED 任务都可重新放回 PENDING；
     * 2. 当外部依赖已修复时，可由受控角色忽略 retry 上限再补偿一次。
     *
     * <p>这里仍不允许 SUCCESS 任务重试，因为成功任务的“重跑”应建模为 replay/backfill/rerun，
     * 而不是直接覆盖原任务生命周期，否则会破坏已成功结果的审计语义。
     */
    @Override
    @Transactional
    public Task forceRetryTask(Long taskId, String reason, Boolean ignoreRetryLimit, TaskActorContext actorContext) {
        validateAdminOperationPermission(actorContext);
        Task task = getRequiredTask(taskId);
        if (TaskStatus.SUCCESS.equals(task.getStatus())) {
            throw new IllegalStateException("已成功任务不应通过 retry 覆盖结果，后续应使用 replay/backfill 能力");
        }
        if (!TaskStatus.FAILED.equals(task.getStatus())
                && !TaskStatus.CANCELLED.equals(task.getStatus())
                && !TaskStatus.DEAD_LETTER.equals(task.getStatus())
                && !TaskStatus.PAUSED.equals(task.getStatus())) {
            throw new IllegalStateException("只有失败、已取消、死信或已暂停任务才能强制重试，当前状态=" + task.getStatus());
        }
        if (!Boolean.TRUE.equals(ignoreRetryLimit) && task.getRetryCount() >= task.getMaxRetryCount()) {
            throw new IllegalStateException("任务已超过最大重试次数，如确需补偿请使用 ignoreRetryLimit=true 并填写原因");
        }

        String previousStatus = task.getStatus();
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setCheckpoint(null);
        task.setStartTime(null);
        task.setEndTime(null);
        task.setResult(null);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setDeferCount(0);
        task.setMaxDeferCount(normalizeMaxDeferCount(task.getMaxDeferCount()));
        task.setQueuedTime(LocalDateTime.now());
        task.setCurrentExecutorId(null);
        task.setCurrentExecutionRunId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "ADMIN_FORCE_RETRY", previousStatus, TaskStatus.PENDING,
                "管理员强制重试任务", adminDetails(reason, actorContext) + ", ignoreRetryLimit=" + Boolean.TRUE.equals(ignoreRetryLimit)
                        + ", retryCount=" + task.getRetryCount(), actorLabel(actorContext));
        log.warn("管理员强制重试任务，taskId={}, actorRole={}, actorId={}, ignoreRetryLimit={}, retryCount={}, reason={}",
                taskId, actorContext.actorRole(), actorContext.actorId(), ignoreRetryLimit, task.getRetryCount(), reason);
        return task;
    }

    /**
     * 管理员覆盖优先级。
     *
     * <p>当前仅修改 task.priority 字段并写日志。
     * 后续当真正的调度队列落地后，该动作还应触发队列重排、租户公平性检查、SLA 风险计算和告警。
     */
    @Override
    @Transactional
    public Task overridePriority(Long taskId, String priority, String reason, TaskActorContext actorContext) {
        validateAdminOperationPermission(actorContext);
        Task task = getRequiredTask(taskId);
        if (TaskStatus.SUCCESS.equals(task.getStatus()) || TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new IllegalStateException("已成功或已取消任务不允许调整优先级，当前状态=" + task.getStatus());
        }

        String previousPriority = task.getPriority();
        String normalizedPriority = TaskPriority.normalize(priority);
        task.setPriority(normalizedPriority);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "ADMIN_OVERRIDE_PRIORITY", task.getStatus(), task.getStatus(),
                "管理员覆盖任务优先级",
                adminDetails(reason, actorContext) + ", fromPriority=" + previousPriority + ", toPriority=" + normalizedPriority,
                actorLabel(actorContext));
        log.warn("管理员覆盖任务优先级，taskId={}, fromPriority={}, toPriority={}, actorRole={}, actorId={}, reason={}",
                taskId, previousPriority, normalizedPriority, actorContext.actorRole(), actorContext.actorId(), reason);
        return task;
    }

    /**
     * 查询任务队列运营视图。
     *
     * <p>普通分页列表主要服务业务浏览；这个方法服务运营排障和队列健康观察。
     * 它把查询规则集中在服务层，原因有三点：
     * 1. 默认只查运营相关状态，避免 SUCCESS/CANCELLED 淹没真正需要处理的任务；
     * 2. 对分页大小、时间阈值、退避次数等输入做保护，避免运维查询本身造成数据库压力；
     * 3. 排序策略固定为“需要关注优先、排队更久优先、最近更新靠后兜底”，让前端不用重复理解队列语义。
     */
    @Override
    public IPage<Task> inspectQueue(TaskQueueInspectionRequest request) {
        TaskQueueInspectionRequest safeRequest = request == null ? new TaskQueueInspectionRequest() : request;
        int current = Math.max(1, safeRequest.getCurrent() == null ? 1 : safeRequest.getCurrent());
        int size = Math.max(1, Math.min(safeRequest.getSize() == null ? 20 : safeRequest.getSize(), 200));
        LambdaQueryWrapper<Task> wrapper = buildQueueInspectionWrapper(safeRequest, true);
        return page(new Page<>(current, size), wrapper);
    }

    /**
     * 查询任务队列运营项视图。
     *
     * <p>这里直接复用 inspectQueue 的分页和排序结果，然后通过 IPage.convert 把 Task 转成运营视图。
     * 这样可以保证原始队列列表和运营项列表的分页、排序、过滤完全一致。
     */
    @Override
    public IPage<TaskQueueItemView> inspectQueueItems(TaskQueueInspectionRequest request) {
        return inspectQueue(request).convert(task -> toQueueItemView(task, LocalDateTime.now()));
    }

    /**
     * 查询任务队列运营汇总。
     *
     * <p>该方法刻意不通过“查询所有任务后在内存中统计”的方式实现。
     * 生产队列积压时，任务量可能很大；如果汇总接口还拉全量数据，会让排障接口本身放大数据库压力。
     * 因此这里使用多次轻量 count/top1 查询：
     * - count 查询用于总量、状态分布、关注任务数；
     * - top1 查询用于最老 queuedTime 和最大 deferCount。
     */
    @Override
    public TaskQueueSummaryResponse summarizeQueue(TaskQueueInspectionRequest request) {
        TaskQueueInspectionRequest safeRequest = request == null ? new TaskQueueInspectionRequest() : request;
        LocalDateTime now = LocalDateTime.now();

        TaskQueueSummaryResponse summary = new TaskQueueSummaryResponse();
        summary.setGeneratedAt(now);
        summary.setTotalCount(count(buildQueueInspectionWrapper(safeRequest, false)));

        Map<String, Long> statusCounts = calculateStatusCounts(safeRequest);
        summary.setStatusCounts(statusCounts);
        summary.setPendingCount(statusCounts.getOrDefault(TaskStatus.PENDING, 0L));
        summary.setRunningCount(statusCounts.getOrDefault(TaskStatus.RUNNING, 0L));
        summary.setDeferredCount(statusCounts.getOrDefault(TaskStatus.DEFERRED, 0L));
        summary.setDeadLetterCount(statusCounts.getOrDefault(TaskStatus.DEAD_LETTER, 0L));
        summary.setFailedCount(statusCounts.getOrDefault(TaskStatus.FAILED, 0L));
        summary.setPausedCount(statusCounts.getOrDefault(TaskStatus.PAUSED, 0L));
        summary.setAttentionRequiredCount(count(buildQueueInspectionWrapper(safeRequest, false)
                .eq(Task::getAttentionRequired, true)));

        Task oldestQueuedTask = baseMapper.selectOne(buildQueueInspectionWrapper(safeRequest, false)
                .isNotNull(Task::getQueuedTime)
                .orderByAsc(Task::getQueuedTime)
                .last("LIMIT 1"));
        if (oldestQueuedTask != null && oldestQueuedTask.getQueuedTime() != null) {
            summary.setOldestQueuedTime(oldestQueuedTask.getQueuedTime());
            summary.setOldestQueuedAgeSeconds(Duration.between(oldestQueuedTask.getQueuedTime(), now).getSeconds());
        }

        Task maxDeferredTask = baseMapper.selectOne(buildQueueInspectionWrapper(safeRequest, false)
                .isNotNull(Task::getDeferCount)
                .orderByDesc(Task::getDeferCount)
                .last("LIMIT 1"));
        summary.setMaxObservedDeferCount(maxDeferredTask == null ? 0 : safeDeferCount(maxDeferredTask));
        return summary;
    }

    /**
     * 计算状态分布。
     *
     * <p>这里按状态逐个 count，而不是使用 group by，是为了复用 LambdaQueryWrapper 的字段引用和过滤逻辑，
     * 在当前阶段保持实现直观、可读、便于学习。后续如果队列规模很大，可以把它下沉为 Mapper 层 group by SQL。
     */
    private Map<String, Long> calculateStatusCounts(TaskQueueInspectionRequest request) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        String requestedStatus = trimToNull(request.getStatus());
        List<String> statuses = requestedStatus == null
                ? QUEUE_SUMMARY_STATUS_ORDER
                : List.of(requestedStatus.toUpperCase(Locale.ROOT));
        for (String status : statuses) {
            Long count = count(buildQueueInspectionWrapper(request, false, status));
            if (count > 0 || requestedStatus != null || shouldExposeZeroStatus(request, status)) {
                statusCounts.put(status, count);
            }
        }
        return statusCounts;
    }

    /**
     * 判断某个零计数状态是否仍应出现在汇总响应中。
     *
     * <p>默认运营视图只展示运营相关状态，includeTerminal=true 时展示所有已知状态。
     * 这样前端图表可以稳定拿到关键状态列，同时不会在默认视图里被 SUCCESS/CANCELLED 这类终态干扰。
     */
    private boolean shouldExposeZeroStatus(TaskQueueInspectionRequest request, String status) {
        return Boolean.TRUE.equals(request.getIncludeTerminal()) || DEFAULT_OPERATIONAL_QUEUE_STATUSES.contains(status);
    }

    /**
     * 构建任务队列运营查询条件。
     *
     * <p>列表和汇总共用这一段过滤逻辑，避免出现“列表看到一批任务，但汇总统计另一批任务”的语义漂移。
     */
    private LambdaQueryWrapper<Task> buildQueueInspectionWrapper(TaskQueueInspectionRequest request, boolean includeOrdering) {
        return buildQueueInspectionWrapper(request, includeOrdering, null);
    }

    /**
     * 构建任务队列运营查询条件，并允许状态覆盖。
     *
     * <p>状态覆盖主要用于 summary 逐状态计数：其他过滤条件保持一致，只把 status 固定为正在统计的状态。
     */
    private LambdaQueryWrapper<Task> buildQueueInspectionWrapper(TaskQueueInspectionRequest request,
                                                                boolean includeOrdering,
                                                                String statusOverride) {
        TaskQueueInspectionRequest safeRequest = request == null ? new TaskQueueInspectionRequest() : request;
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        String status = statusOverride == null ? trimToNull(safeRequest.getStatus()) : statusOverride;
        applyQueueStatusFilter(wrapper, status, Boolean.TRUE.equals(safeRequest.getIncludeTerminal()));
        String type = trimToNull(safeRequest.getType());
        if (type != null) {
            wrapper.eq(Task::getType, type);
        }
        String priority = trimToNull(safeRequest.getPriority());
        if (priority != null) {
            wrapper.eq(Task::getPriority, TaskPriority.normalize(priority));
        }
        if (safeRequest.getAttentionRequired() != null) {
            wrapper.eq(Task::getAttentionRequired, safeRequest.getAttentionRequired());
        }
        String currentExecutorId = trimToNull(safeRequest.getCurrentExecutorId());
        if (currentExecutorId != null) {
            wrapper.eq(Task::getCurrentExecutorId, currentExecutorId);
        }
        if (safeRequest.getDeferCountAtLeast() != null) {
            wrapper.ge(Task::getDeferCount, Math.max(0, safeRequest.getDeferCountAtLeast()));
        }
        if (safeRequest.getQueuedAfter() != null) {
            wrapper.ge(Task::getQueuedTime, safeRequest.getQueuedAfter());
        }
        if (safeRequest.getQueuedBefore() != null) {
            wrapper.le(Task::getQueuedTime, safeRequest.getQueuedBefore());
        }
        if (safeRequest.getQueuedOlderThanSeconds() != null) {
            long safeSeconds = Math.max(1L, safeRequest.getQueuedOlderThanSeconds());
            wrapper.le(Task::getQueuedTime, LocalDateTime.now().minusSeconds(safeSeconds));
        }
        if (includeOrdering) {
            /*
             * 排序策略说明：
             * 1. attentionRequired=true 的任务排在最前，帮助运营人员先处理明确风险；
             * 2. queuedTime 越早说明排队或延迟越久，优先展示；
             * 3. updateTime 倒序兜底，让同一 queuedTime 下最近变化的任务更容易被看到。
             */
            wrapper.orderByDesc(Task::getAttentionRequired)
                    .orderByAsc(Task::getQueuedTime)
                    .orderByDesc(Task::getUpdateTime)
                    .orderByDesc(Task::getId);
        }
        return wrapper;
    }

    /**
     * 把任务主表快照转换为队列运营视图。
     *
     * <p>这个转换方法承担的是“解释任务风险”的职责。
     * Task 实体只告诉我们 status、queuedTime、leaseExpireTime、deferCount 等原始事实；
     * TaskQueueItemView 则进一步告诉运营人员：
     * - 已经排队多久；
     * - 延迟还有多久到期；
     * - 租约是否已经过期；
     * - 为什么值得关注；
     * - 建议下一步做什么。
     */
    private TaskQueueItemView toQueueItemView(Task task, LocalDateTime now) {
        TaskQueueItemView view = new TaskQueueItemView();
        view.setId(task.getId());
        view.setName(task.getName());
        view.setType(task.getType());
        view.setStatus(task.getStatus());
        view.setPriority(task.getPriority());
        view.setProgress(task.getProgress());
        view.setCurrentExecutorId(task.getCurrentExecutorId());
        view.setCurrentExecutionRunId(task.getCurrentExecutionRunId());
        view.setQueuedTime(task.getQueuedTime());
        view.setQueueAgeSeconds(resolveQueueAgeSeconds(task, now));
        view.setQueuedDelayRemainingSeconds(resolveQueuedDelayRemainingSeconds(task, now));
        view.setHeartbeatTime(task.getHeartbeatTime());
        view.setHeartbeatAgeSeconds(resolveHeartbeatAgeSeconds(task, now));
        view.setLeaseExpireTime(task.getLeaseExpireTime());
        view.setLeaseRemainingSeconds(resolveLeaseRemainingSeconds(task, now));
        view.setAttentionRequired(task.getAttentionRequired());
        view.setDeferCount(safeDeferCount(task));
        view.setMaxDeferCount(normalizeMaxDeferCount(task.getMaxDeferCount()));
        view.setResult(task.getResult());
        applyRiskExplanation(task, view);
        return view;
    }

    /**
     * 计算已排队秒数。
     *
     * <p>如果 queuedTime 在未来，说明任务还处于 DEFERRED 延迟期，此时“已排队”按 0 处理，
     * 未来剩余时间由 queuedDelayRemainingSeconds 表达。
     */
    private Long resolveQueueAgeSeconds(Task task, LocalDateTime now) {
        if (task.getQueuedTime() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(task.getQueuedTime(), now).getSeconds());
    }

    /**
     * 计算延迟队列剩余秒数。
     */
    private Long resolveQueuedDelayRemainingSeconds(Task task, LocalDateTime now) {
        if (task.getQueuedTime() == null || !task.getQueuedTime().isAfter(now)) {
            return 0L;
        }
        return Duration.between(now, task.getQueuedTime()).getSeconds();
    }

    /**
     * 计算心跳年龄。
     */
    private Long resolveHeartbeatAgeSeconds(Task task, LocalDateTime now) {
        if (task.getHeartbeatTime() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(task.getHeartbeatTime(), now).getSeconds());
    }

    /**
     * 计算租约剩余秒数。
     *
     * <p>返回负数表示租约已经过期，这比单纯 true/false 更利于运营页面展示过期多久。
     */
    private Long resolveLeaseRemainingSeconds(Task task, LocalDateTime now) {
        if (task.getLeaseExpireTime() == null) {
            return null;
        }
        return Duration.between(now, task.getLeaseExpireTime()).getSeconds();
    }

    /**
     * 给队列项补充风险解释。
     *
     * <p>风险解释的优先级从“必须人工处理”到“可观察”依次降低。
     * 这里先采用确定性规则，而不是引入复杂评分模型，是为了让学习和排障都足够透明。
     */
    private void applyRiskExplanation(Task task, TaskQueueItemView view) {
        if (TaskStatus.DEAD_LETTER.equals(task.getStatus())) {
            view.setRiskLevel("CRITICAL");
            view.setRiskReason("任务已进入 DEAD_LETTER，系统已停止自动调度，通常意味着连续退避超过上限或需要人工介入。");
            view.setRecommendedAction("检查执行日志、容量配额和下游依赖；确认问题修复后由管理员 forceRetry，或取消/暂停任务。");
            return;
        }
        if (Boolean.TRUE.equals(task.getAttentionRequired())) {
            view.setRiskLevel("CRITICAL");
            view.setRiskReason(defaultText(task.getResult(), "任务已被标记为需要人工关注。"));
            view.setRecommendedAction("查看任务日志和执行记录，判断是重试、取消、暂停还是调整执行资源。");
            return;
        }
        if (TaskStatus.RUNNING.equals(task.getStatus())
                && view.getLeaseRemainingSeconds() != null
                && view.getLeaseRemainingSeconds() < 0) {
            view.setRiskLevel("CRITICAL");
            view.setRiskReason("任务仍显示 RUNNING，但执行租约已经过期，可能存在执行器失联或心跳中断。");
            view.setRecommendedAction("调用租约超时恢复接口或检查执行器实例状态，避免任务长期卡在 RUNNING。");
            return;
        }
        if (TaskStatus.DEFERRED.equals(task.getStatus())) {
            view.setRiskLevel("WARNING");
            if (view.getQueuedDelayRemainingSeconds() != null && view.getQueuedDelayRemainingSeconds() > 0) {
                view.setRiskReason("任务处于 DEFERRED 延迟期，暂时不会被执行器重新认领。");
                view.setRecommendedAction("观察延迟到期后是否恢复；如频繁发生，请检查执行器容量、租户配额或数据源配额。");
            } else {
                view.setRiskReason("任务处于 DEFERRED 且已经到期，正在等待执行器重新认领。");
                view.setRecommendedAction("如果持续未被认领，请检查执行器是否在线、taskType 是否匹配、队列是否存在更高优先级积压。");
            }
            return;
        }
        if (TaskStatus.FAILED.equals(task.getStatus())) {
            view.setRiskLevel("WARNING");
            view.setRiskReason(defaultText(task.getResult(), "任务执行失败，等待重试或人工复盘。"));
            view.setRecommendedAction("查看失败原因和执行日志；如果外部依赖已恢复，可按重试策略 retry 或由管理员 forceRetry。");
            return;
        }
        if (isNearDeferLimit(task)) {
            view.setRiskLevel("WARNING");
            view.setRiskReason("任务连续退避次数已接近最大允许值，继续退避可能进入 DEAD_LETTER。");
            view.setRecommendedAction("提前检查执行器容量、租户配额和数据源并发限制，避免任务进入死信。");
            return;
        }
        if (TaskStatus.PENDING.equals(task.getStatus())
                && view.getQueueAgeSeconds() != null
                && view.getQueueAgeSeconds() >= QUEUE_AGING_WARNING_SECONDS) {
            view.setRiskLevel("WARNING");
            view.setRiskReason("任务处于 PENDING 且排队时间较长，可能存在执行器不足或队列积压。");
            view.setRecommendedAction("检查执行器在线情况、任务类型匹配、优先级配置和队列汇总指标。");
            return;
        }
        if (TaskStatus.PAUSED.equals(task.getStatus())) {
            view.setRiskLevel("INFO");
            view.setRiskReason("任务已暂停，不会继续自动推进。");
            view.setRecommendedAction("确认暂停原因是否仍然成立；如可恢复，由管理员 resume 或 forceRetry。");
            return;
        }
        view.setRiskLevel("NORMAL");
        view.setRiskReason("未发现明确队列风险。");
        view.setRecommendedAction("继续观察任务状态和队列汇总指标。");
    }

    /**
     * 判断任务是否接近连续退避上限。
     *
     * <p>这里采用 80% 阈值作为第一版预警。
     * 如果 maxDeferCount 为 0，说明第一次 defer 就会死信，因此不在这里重复预警。
     */
    private boolean isNearDeferLimit(Task task) {
        int maxDeferCount = normalizeMaxDeferCount(task.getMaxDeferCount());
        if (maxDeferCount <= 0) {
            return false;
        }
        return safeDeferCount(task) >= Math.ceil(maxDeferCount * 0.8D);
    }

    /**
     * 应用队列运营视图的状态过滤规则。
     *
     * <p>如果调用方显式指定 status，就尊重调用方；
     * 如果没有指定且 includeTerminal=false，就使用默认运营状态集合；
     * 如果 includeTerminal=true，则不加状态条件，让事故复盘能看到所有状态。
     */
    private void applyQueueStatusFilter(LambdaQueryWrapper<Task> wrapper, String status, boolean includeTerminal) {
        if (status != null) {
            wrapper.eq(Task::getStatus, status.toUpperCase(Locale.ROOT));
            return;
        }
        if (!includeTerminal) {
            wrapper.in(Task::getStatus, DEFAULT_OPERATIONAL_QUEUE_STATUSES);
        }
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
        task.setCurrentExecutorId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        finishCurrentRunIfPresent(task, TaskExecutionRunState.SUCCESS, null);

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
        task.setCurrentExecutorId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setAttentionRequired(true);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);
        finishCurrentRunIfPresent(task, TaskExecutionRunState.FAILED, errorMessage);

        saveExecutionLog(taskId, "FAIL", previousStatus, TaskStatus.FAILED, "任务执行失败", errorMessage);
        log.error("任务执行失败，taskId={}, errorMessage={}", taskId, errorMessage);
        return true;
    }

    /**
     * 执行器主动延迟任务。
     *
     * <p>这是任务中心进入生产级调度系统非常关键的一步：容量不足不应该被记录成业务失败。
     * 例如 data-quality 实例发现当前全局/租户/数据源并发已满时，它已经成功认领了任务，
     * 但继续执行会放大源库压力或拖垮本实例线程池。此时最合理的行为是：
     * 1. 结束当前 task_execution_run，状态记为 DEFERRED；
     * 2. 清空当前 executor、心跳和租约字段，释放这次认领；
     * 3. 把任务主表设置为 DEFERRED，并把 queued_time 推到未来；
     * 4. 后续 claim 查询只在 queued_time 到期后重新认领它。
     *
     * <p>注意：这里不增加 retryCount，也不设置 attentionRequired。
     * 因为 defer 代表系统背压，不代表任务逻辑失败；如果把它计入重试次数，会让容量波动消耗用户可恢复次数。
     */
    @Override
    @Transactional
    public boolean deferTask(Long taskId, String reason, Integer delaySeconds) {
        Task task = getRequiredTask(taskId);
        ensureStatus(task, TaskStatus.RUNNING, "只有运行中任务才能延迟回队列");

        String previousStatus = task.getStatus();
        int safeDelaySeconds = normalizeDeferSeconds(delaySeconds);
        LocalDateTime nextQueuedTime = LocalDateTime.now().plusSeconds(safeDelaySeconds);
        String safeReason = defaultText(reason, "执行器主动退避，任务延迟回队列");
        int nextDeferCount = safeDeferCount(task) + 1;
        int maxDeferCount = normalizeMaxDeferCount(task.getMaxDeferCount());

        finishCurrentRunIfPresent(task, TaskExecutionRunState.DEFERRED,
                safeReason + ", deferCount=" + nextDeferCount + ", maxDeferCount=" + maxDeferCount);

        if (nextDeferCount > maxDeferCount) {
            moveToDeadLetterAfterExcessiveDefers(task, previousStatus, safeReason, nextDeferCount, maxDeferCount);
            return true;
        }

        task.setStatus(TaskStatus.DEFERRED);
        task.setQueuedTime(nextQueuedTime);
        task.setDeferCount(nextDeferCount);
        task.setMaxDeferCount(maxDeferCount);
        task.setResult("任务已延迟回队列: " + safeReason);
        task.setCurrentExecutorId(null);
        task.setCurrentExecutionRunId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setStartTime(null);
        task.setEndTime(null);
        task.setAttentionRequired(false);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(taskId, "DEFER", previousStatus, TaskStatus.DEFERRED,
                "执行器主动退避，任务已延迟回队列",
                "delaySeconds=" + safeDelaySeconds
                        + ", nextQueuedTime=" + nextQueuedTime
                        + ", deferCount=" + nextDeferCount
                        + ", maxDeferCount=" + maxDeferCount
                        + ", reason=" + safeReason);
        log.info("任务延迟回队列成功，taskId={}, delaySeconds={}, nextQueuedTime={}, deferCount={}, maxDeferCount={}, reason={}",
                taskId, safeDelaySeconds, nextQueuedTime, nextDeferCount, maxDeferCount, safeReason);
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
     * 执行器认领下一条任务。
     *
     * <p>这一步是任务系统走向“可调度”的关键：
     * 1. 执行器不是自己随便挑任务，而是向任务中心申请；
     * 2. 任务中心按优先级和创建时间挑选候选任务；
     * 3. 通过条件更新把任务从 PENDING 改成 RUNNING，形成数据库层面的轻量锁；
     * 4. 创建 task_execution_run，记录这一次执行尝试；
     * 5. 执行器后续通过 runId 心跳续租。
     */
    @Override
    @Transactional
    public TaskExecutionClaimResult claimNextTask(TaskExecutionClaimRequest request, TaskActorContext actorContext) {
        validateExecutorOperationPermission(actorContext);
        long leaseSeconds = request.getLeaseSeconds() == null ? DEFAULT_LEASE_SECONDS : request.getLeaseSeconds();
        Task candidate = baseMapper.selectNextClaimCandidate(trimToNull(request.getTaskType()));
        if (candidate == null) {
            return new TaskExecutionClaimResult(false, "当前没有可认领任务", null, null);
        }

        String previousStatus = candidate.getStatus();
        int claimed = baseMapper.claimTask(candidate.getId(), request.getExecutorId(), leaseSeconds);
        if (claimed == 0) {
            return new TaskExecutionClaimResult(false, "候选任务已被其他执行器抢先认领，请稍后重试", null, null);
        }

        Task claimedTask = getRequiredTask(candidate.getId());
        TaskExecutionRun run = createExecutionRun(claimedTask, request.getExecutorId(), leaseSeconds, actorContext);
        claimedTask.setCurrentExecutionRunId(run.getId());
        updateById(claimedTask);

        saveExecutionLog(claimedTask.getId(), "EXECUTOR_CLAIM", previousStatus, TaskStatus.RUNNING,
                "执行器认领任务", "executorId=" + request.getExecutorId() + ", runId=" + run.getId()
                        + ", leaseSeconds=" + leaseSeconds
                        + ", previousStatus=" + previousStatus
                        + ", traceId=" + nullSafe(actorContext.traceId()),
                actorLabel(actorContext));
        return new TaskExecutionClaimResult(true, "任务认领成功", getRequiredTask(claimedTask.getId()), run);
    }

    /**
     * 执行器心跳续租。
     *
     * <p>心跳会同步更新 task 主表和 execution_run：
     * 主表用于任务列表快速展示当前进度；run 表用于保存本次执行尝试的持续轨迹。
     */
    @Override
    @Transactional
    public TaskExecutionRun heartbeatExecution(Long runId,
                                               TaskExecutionHeartbeatRequest request,
                                               TaskActorContext actorContext) {
        validateExecutorOperationPermission(actorContext);
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

        Task task = getRequiredTask(run.getTaskId());
        long leaseSeconds = request.getLeaseSeconds() == null ? DEFAULT_LEASE_SECONDS : request.getLeaseSeconds();
        Integer progress = request.getProgress() == null ? task.getProgress() : request.getProgress();
        String checkpoint = request.getCheckpoint() == null ? task.getCheckpoint() : request.getCheckpoint();
        int updated = baseMapper.heartbeatLease(task.getId(), request.getExecutorId(), progress,
                checkpoint, leaseSeconds);
        if (updated == 0) {
            throw new IllegalStateException("任务租约续期失败，可能任务已结束、已超时恢复或执行器不匹配");
        }

        run.setProgress(progress);
        run.setCheckpoint(checkpoint);
        run.setHeartbeatAt(LocalDateTime.now());
        run.setLeaseExpireTime(LocalDateTime.now().plusSeconds(leaseSeconds));
        run.setUpdateTime(LocalDateTime.now());
        taskExecutionRunMapper.updateById(run);

        saveExecutionLog(task.getId(), "EXECUTOR_HEARTBEAT", TaskStatus.RUNNING, TaskStatus.RUNNING,
                "执行器心跳续租", "executorId=" + request.getExecutorId() + ", runId=" + runId
                        + ", progress=" + progress + ", checkpoint=" + defaultText(checkpoint, "")
                        + ", leaseSeconds=" + leaseSeconds, actorLabel(actorContext));
        return taskExecutionRunMapper.selectById(runId);
    }

    /**
     * 恢复执行器租约超时的任务。
     *
     * <p>当前基线选择把超时任务标记为 FAILED，并设置 attentionRequired=true。
     * 这样做比自动回到 PENDING 更保守：
     * 如果执行器已经失联，盲目自动重跑可能造成重复写入、重复发送通知、重复调用外部系统。
     * 后续可以在任务类型具备幂等保证后，再支持自动重入队策略。
     */
    @Override
    @Transactional
    public TaskLeaseRecoveryResult recoverTimedOutExecutions(Integer limit, TaskActorContext actorContext) {
        validateAdminOperationPermission(actorContext);
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<TaskExecutionRun> timedOutRuns = taskExecutionRunMapper.selectTimedOutRuns(safeLimit);
        int recovered = 0;
        for (TaskExecutionRun run : timedOutRuns) {
            Task task = getById(run.getTaskId());
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
            updateById(task);

            saveExecutionLog(task.getId(), "LEASE_TIMEOUT_RECOVER", previousStatus, TaskStatus.FAILED,
                    "执行器租约超时，任务已标记失败并需要运营关注",
                    "runId=" + run.getId() + ", executorId=" + run.getExecutorId()
                            + ", leaseExpireTime=" + nullSafe(run.getLeaseExpireTime())
                            + ", traceId=" + nullSafe(actorContext.traceId()),
                    actorLabel(actorContext));
            recovered++;
        }
        return new TaskLeaseRecoveryResult(timedOutRuns.size(), recovered, "租约超时恢复扫描完成");
    }

    /**
     * 查询任务执行记录。
     */
    @Override
    public List<TaskExecutionRun> listExecutionRuns(Long taskId) {
        getRequiredTask(taskId);
        return taskExecutionRunMapper.selectList(new LambdaQueryWrapper<TaskExecutionRun>()
                .eq(TaskExecutionRun::getTaskId, taskId)
                .orderByDesc(TaskExecutionRun::getRunNo)
                .orderByDesc(TaskExecutionRun::getId));
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
     * 校验任务运维动作权限。
     *
     * <p>gateway 的路由权限是第一道门，但业务服务仍需要第二道服务内防线。
     * 原因是：
     * 1. 某些内部调用可能绕过 gateway；
     * 2. gateway 配置错误时，服务层仍应保护高风险状态变更；
     * 3. 执行日志需要可信操作者上下文。
     */
    private void validateAdminOperationPermission(TaskActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new IllegalStateException("缺少可信操作者角色，不能执行任务运维动作");
        }
        String actorRole = actorContext.actorRole().trim().toUpperCase(Locale.ROOT);
        if (!ADMIN_OPERATION_ROLES.contains(actorRole)) {
            throw new IllegalStateException("当前角色无权执行任务运维动作，actorRole=" + actorRole);
        }
    }

    /**
     * 校验执行器相关动作权限。
     */
    private void validateExecutorOperationPermission(TaskActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new IllegalStateException("缺少可信操作者角色，不能执行任务执行器动作");
        }
        String actorRole = actorContext.actorRole().trim().toUpperCase(Locale.ROOT);
        if (!EXECUTOR_OPERATION_ROLES.contains(actorRole)) {
            throw new IllegalStateException("当前角色无权执行任务认领或心跳动作，actorRole=" + actorRole);
        }
    }

    /**
     * 创建执行记录。
     */
    private TaskExecutionRun createExecutionRun(Task task,
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
     * 结束当前执行记录。
     *
     * <p>任务主表和执行记录表必须保持语义一致：
     * 如果任务已经 SUCCESS、FAILED 或 CANCELLED，但 execution_run 仍是 RUNNING，
     * 运营后台就会看到互相矛盾的数据，超时恢复也可能误处理已结束任务。
     */
    private void finishCurrentRunIfPresent(Task task, String state, String errorMessage) {
        if (task.getCurrentExecutionRunId() == null) {
            return;
        }
        taskExecutionRunMapper.finishRunningRun(task.getCurrentExecutionRunId(), state, errorMessage);
    }

    /**
     * 连续退避超过上限后，把任务移入死信状态。
     *
     * <p>这里没有继续设置 queuedTime，也不会让 claim 查询再次认领该任务。
     * 这是一个很重要的生产保护：如果任务一直因为容量不足被退避，继续自动回队列只会制造无意义的轮询、
     * 日志噪音和调度压力，还会掩盖真正需要运营处理的容量问题。
     *
     * <p>进入 DEAD_LETTER 后，任务需要管理员查看执行日志、run 历史和容量指标，再决定：
     * - 扩容 data-quality / data-sync 执行器；
     * - 调整租户或数据源并发配额；
     * - 降低任务优先级或暂停任务；
     * - 使用 forceRetry 将任务重新放回 PENDING。
     */
    private void moveToDeadLetterAfterExcessiveDefers(Task task,
                                                      String previousStatus,
                                                      String reason,
                                                      int deferCount,
                                                      int maxDeferCount) {
        task.setStatus(TaskStatus.DEAD_LETTER);
        task.setQueuedTime(null);
        task.setDeferCount(deferCount);
        task.setMaxDeferCount(maxDeferCount);
        task.setResult("任务连续延迟回队列超过上限，已进入死信状态: " + reason);
        task.setCurrentExecutorId(null);
        task.setCurrentExecutionRunId(null);
        task.setHeartbeatTime(null);
        task.setLeaseExpireTime(null);
        task.setEndTime(LocalDateTime.now());
        task.setAttentionRequired(true);
        task.setUpdateTime(LocalDateTime.now());
        updateById(task);

        saveExecutionLog(task.getId(), "DEFER_DEAD_LETTER", previousStatus, TaskStatus.DEAD_LETTER,
                "任务连续退避超过上限，已进入死信状态",
                "deferCount=" + deferCount
                        + ", maxDeferCount=" + maxDeferCount
                        + ", reason=" + reason);
        log.warn("任务连续退避超过上限，进入死信状态，taskId={}, deferCount={}, maxDeferCount={}, reason={}",
                task.getId(), deferCount, maxDeferCount, reason);
    }

    /**
     * 归一化执行器退避秒数。
     *
     * <p>外部执行器传来的 delaySeconds 属于跨服务输入，不能完全信任：
     * - 为空时使用默认值；
     * - 小于 1 时压到 1 秒，避免立即反复认领；
     * - 大于 MAX_DEFER_SECONDS 时压到上限，避免任务被错误隐藏太久。
     */
    private int normalizeDeferSeconds(Integer delaySeconds) {
        if (delaySeconds == null) {
            return DEFAULT_DEFER_SECONDS;
        }
        return Math.max(1, Math.min(delaySeconds, MAX_DEFER_SECONDS));
    }

    /**
     * 归一化最大连续退避次数。
     *
     * <p>允许调用方配置为 0，表示“只要发生第一次 defer 就立即进入 DEAD_LETTER”。
     * 这适合某些高敏感任务，例如金融结算、强一致数据同步、监管报送等，不希望系统自动反复尝试。
     */
    private int normalizeMaxDeferCount(Integer maxDeferCount) {
        if (maxDeferCount == null) {
            return DEFAULT_MAX_DEFER_COUNT;
        }
        return Math.max(0, Math.min(maxDeferCount, 10_000));
    }

    /**
     * 安全读取任务当前连续退避次数。
     */
    private int safeDeferCount(Task task) {
        return task.getDeferCount() == null ? 0 : Math.max(0, task.getDeferCount());
    }

    /**
     * 空白字符串归一为空。
     */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 生成管理员动作详情。
     *
     * <p>执行日志 details 目前是文本字段，先用易读的 key=value 形式记录上下文。
     * 后续如果日志查询和分析需求增强，可以升级为 JSON，并增加 tenantId、actorId、traceId 的独立索引字段。
     */
    private String adminDetails(String reason, TaskActorContext actorContext) {
        return "reason=" + defaultText(reason, "未填写原因")
                + ", tenantId=" + nullSafe(actorContext.tenantId())
                + ", actorId=" + nullSafe(actorContext.actorId())
                + ", actorRole=" + nullSafe(actorContext.actorRole())
                + ", traceId=" + nullSafe(actorContext.traceId());
    }

    /**
     * 生成执行日志 operator 字段。
     */
    private String actorLabel(TaskActorContext actorContext) {
        if (actorContext == null) {
            return "unknown";
        }
        return defaultText(actorContext.actorRole(), "unknown") + ":" + nullSafe(actorContext.actorId());
    }

    /**
     * 文本默认值。
     */
    private String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    /**
     * 空值安全字符串。
     */
    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 写入结构化执行日志。
     * 与普通应用日志相比，数据库执行日志更适合按任务维度做结构化查询，
     * 后续任务监控页、审计报表、失败分析都可以直接复用这些数据。
     */
    private void saveExecutionLog(Long taskId, String action, String fromStatus, String toStatus,
                                  String message, String details) {
        saveExecutionLog(taskId, action, fromStatus, toStatus, message, details, "system");
    }

    /**
     * 写入结构化执行日志。
     *
     * <p>这个重载允许显式指定 operator，主要用于管理员动作。
     * 普通系统动作仍使用 system，避免旧接口行为发生变化。
     */
    private void saveExecutionLog(Long taskId, String action, String fromStatus, String toStatus,
                                  String message, String details, String operator) {
        TaskExecutionLog executionLog = new TaskExecutionLog();
        executionLog.setTaskId(taskId);
        executionLog.setAction(action);
        executionLog.setFromStatus(fromStatus);
        executionLog.setToStatus(toStatus);
        executionLog.setMessage(message);
        executionLog.setOperator(operator);
        executionLog.setDetails(details);
        executionLog.setCreateTime(LocalDateTime.now());
        taskExecutionLogMapper.insert(executionLog);
    }
}
