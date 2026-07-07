/**
 * @Author : Cui
 * @Date: 2026/07/07 23:08
 * @Description DataSmart Govern Backend - DataSyncTaskScheduleServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.datasync.config.DataSyncTaskSchedulerProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncTaskScheduleService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskScheduleConfigSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskScheduleConfigSupport.SyncTaskMisfirePolicy;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskScheduleConfigSupport.SyncTaskScheduleDispatchPlan;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * data-sync 任务级定时调度服务实现。
 *
 * <p>本类补齐的是“定时全量/定时批量”的上游触发闭环：它把到期的 {@code SyncTask} 转换为
 * {@code SyncExecution(triggerType=SCHEDULED)}。注意它不是执行器，不直接访问源库或目标库，也不拼接 SQL。</p>
 *
 * <p>核心流程：</p>
 * <p>1. 扫描 {@code schedule_enabled=true && current_state=SCHEDULED && next_fire_time<=now} 的任务；</p>
 * <p>2. 解析任务的 scheduleConfig，计算本轮是否应该补偿、跳过或等待；</p>
 * <p>3. 如果不允许并发且已有 QUEUED/RUNNING/RETRYING/PAUSED execution，则按 misfirePolicy 处理；</p>
 * <p>4. 使用 task.schedule_version 做数据库原子抢占，抢占成功后创建 SCHEDULED execution；</p>
 * <p>5. 后续真实执行仍由 worker loop 认领 execution 并调用 datasource-management。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncTaskScheduleServiceImpl implements DataSyncTaskScheduleService {

    private static final String ISSUE_INVALID_CONFIG = "SCHEDULE_CONFIG_INVALID";
    private static final String ISSUE_CONCURRENT_RUN_BLOCKED = "SCHEDULE_CONCURRENT_RUN_BLOCKED";
    private static final String ISSUE_MISFIRE_SKIPPED = "SCHEDULE_MISFIRE_SKIPPED";
    private static final String ISSUE_RACE_LOST = "SCHEDULE_VERSION_RACE_LOST";

    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncTaskScheduleConfigSupport scheduleConfigSupport;
    private final SyncAuditSupport auditSupport;
    private final DataSyncTaskSchedulerProperties properties;

    /**
     * 执行一轮到期任务派发。
     *
     * <p>方法使用事务包住“推进调度游标 + 创建 execution + 回写 lastExecutionId”。
     * 如果中途创建 execution 失败，游标推进也会回滚，避免出现“任务显示已触发，但执行历史没有记录”的断裂状态。</p>
     */
    @Override
    @Transactional
    public SyncTaskScheduleDispatchResult dispatchDueTasks(SyncTaskScheduleDispatchRequest request,
                                                           SyncActorContext actorContext) {
        SyncTaskScheduleDispatchRequest safeRequest = request == null ? new SyncTaskScheduleDispatchRequest() : request;
        LocalDateTime now = LocalDateTime.now();
        boolean dryRun = Boolean.TRUE.equals(safeRequest.getDryRun());
        Long tenantId = safeRequest.getTenantId() == null ? properties.getTenantId() : safeRequest.getTenantId();
        int limit = properties.effectiveBatchSize(safeRequest.getLimit());
        List<SyncTask> dueTasks = taskMapper.selectDueScheduledTasks(tenantId, now, limit);

        DispatchAccumulator accumulator = new DispatchAccumulator(dryRun);
        for (SyncTask task : dueTasks) {
            dispatchOneTask(task, now, dryRun, actorContext, accumulator);
        }
        return accumulator.toResult(dueTasks.size());
    }

    /**
     * 处理单个到期任务。
     *
     * <p>这里故意逐个任务处理，而不是批量 SQL 一次性生成 execution。原因是每个任务的 scheduleConfig、
     * misfirePolicy、allowConcurrentRuns 和 catch-up 上限都可能不同，且每个 execution 都需要写独立审计。</p>
     */
    private void dispatchOneTask(SyncTask task,
                                 LocalDateTime now,
                                 boolean dryRun,
                                 SyncActorContext actorContext,
                                 DispatchAccumulator accumulator) {
        SyncTaskScheduleDispatchPlan plan;
        try {
            plan = scheduleConfigSupport.buildDispatchPlan(
                    task,
                    now,
                    properties.effectiveMaxCatchUpRunsPerTask()
            );
        } catch (RuntimeException exception) {
            log.warn("定时同步任务 scheduleConfig 解析失败，本轮跳过: taskId={}, exceptionType={}",
                    task.getId(), exception.getClass().getSimpleName());
            accumulator.skippedInvalidConfigCount++;
            accumulator.issueCodes.add(ISSUE_INVALID_CONFIG);
            return;
        }

        long activeExecutionCount = activeExecutionCount(task);
        if (!plan.definition().allowConcurrentRuns() && activeExecutionCount > 0L) {
            handleConcurrencyBlocked(task, now, dryRun, actorContext, accumulator, plan);
            return;
        }

        if (plan.fireTimes().isEmpty()) {
            handleNoExecutionPlan(task, now, dryRun, actorContext, accumulator, plan);
            return;
        }

        if (dryRun) {
            accumulator.dispatchedTaskCount++;
            accumulator.createdExecutionCount += plan.fireTimes().size();
            return;
        }

        int reserved = reserveScheduleCursor(task, now, plan, null);
        if (reserved == 0) {
            accumulator.skippedByRaceCount++;
            accumulator.issueCodes.add(ISSUE_RACE_LOST);
            return;
        }

        List<Long> executionIds = new ArrayList<>();
        for (LocalDateTime ignoredFireTime : plan.fireTimes()) {
            /*
             * execution 表当前没有单独的 scheduled_fire_time 字段，因此计划触发时间记录在 task.last_fire_time。
             * 如果后续运营台需要逐条 execution 展示“原计划几点触发”，可以在 execution 表追加 scheduled_fire_time，
             * 但当前不为了展示字段扩大迁移范围。
             */
            SyncExecution execution = executionCreationSupport.createQueuedExecution(task, actorContext, SyncTriggerType.SCHEDULED);
            executionIds.add(execution.getId());
        }
        if (!executionIds.isEmpty()) {
            Long latestExecutionId = executionIds.get(executionIds.size() - 1);
            taskMapper.markScheduledExecutionCreated(task.getId(), latestExecutionId);
            auditSupport.saveAudit(task.getTenantId(), task.getId(), latestExecutionId,
                    SyncAuditActionType.RUN_TASK, actorContext,
                    "scheduledDispatch,executionCount=" + executionIds.size()
                            + ",nextFireTime=" + plan.nextFireTime()
                            + ",misfireIncrement=" + plan.misfireIncrement());
        }
        accumulator.dispatchedTaskCount++;
        accumulator.createdExecutionCount += executionIds.size();
        accumulator.executionIds.addAll(executionIds);
    }

    /**
     * 处理已有活跃 execution 时的并发冲突。
     *
     * <p>默认策略是不再创建新的 execution。如果配置了 SKIP，说明用户接受跳过当前窗口，此时推进游标并累计 misfire；
     * 如果不是 SKIP，则保持 nextFireTime 不变，等当前活跃 execution 完成后由下一轮调度补发一次。</p>
     */
    private void handleConcurrencyBlocked(SyncTask task,
                                          LocalDateTime now,
                                          boolean dryRun,
                                          SyncActorContext actorContext,
                                          DispatchAccumulator accumulator,
                                          SyncTaskScheduleDispatchPlan plan) {
        accumulator.skippedByConcurrencyCount++;
        accumulator.issueCodes.add(ISSUE_CONCURRENT_RUN_BLOCKED);
        if (plan.definition().misfirePolicy() == SyncTaskMisfirePolicy.SKIP) {
            if (!dryRun) {
                reserveScheduleCursor(task, now, plan, null);
                auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                        SyncAuditActionType.RUN_TASK, actorContext,
                        "scheduledSkippedByConcurrency,nextFireTime=" + plan.nextFireTime()
                                + ",misfireIncrement=" + plan.misfireIncrement());
            }
            accumulator.skippedByMisfirePolicyCount++;
            accumulator.issueCodes.add(ISSUE_MISFIRE_SKIPPED);
        }
    }

    /**
     * 处理计划本身不需要创建 execution 的情况。
     *
     * <p>最常见的是 misfirePolicy=SKIP：调度器只把 nextFireTime 推进到未来，不把错过的窗口补成 execution。
     * 这种策略适合“只关心最新批次，不关心历史每个窗口都必须跑”的报表或临时同步任务。</p>
     */
    private void handleNoExecutionPlan(SyncTask task,
                                       LocalDateTime now,
                                       boolean dryRun,
                                       SyncActorContext actorContext,
                                       DispatchAccumulator accumulator,
                                       SyncTaskScheduleDispatchPlan plan) {
        if (!dryRun) {
            int updated = reserveScheduleCursor(task, now, plan, null);
            if (updated == 0) {
                accumulator.skippedByRaceCount++;
                accumulator.issueCodes.add(ISSUE_RACE_LOST);
                return;
            }
            auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                    SyncAuditActionType.RUN_TASK, actorContext,
                    "scheduledMisfireSkipped,nextFireTime=" + plan.nextFireTime()
                            + ",misfireIncrement=" + plan.misfireIncrement());
        }
        accumulator.skippedByMisfirePolicyCount++;
        accumulator.issueCodes.add(ISSUE_MISFIRE_SKIPPED);
    }

    private int reserveScheduleCursor(SyncTask task,
                                      LocalDateTime now,
                                      SyncTaskScheduleDispatchPlan plan,
                                      Long lastExecutionId) {
        LocalDateTime lastFireTime = plan.fireTimes().isEmpty()
                ? null
                : plan.fireTimes().get(plan.fireTimes().size() - 1);
        return taskMapper.advanceScheduledTaskAfterDispatch(
                task.getId(),
                safeVersion(task.getScheduleVersion()),
                now,
                lastFireTime,
                plan.nextFireTime(),
                lastExecutionId,
                plan.fireTimes().size(),
                plan.misfireIncrement()
        );
    }

    private long activeExecutionCount(SyncTask task) {
        Long count = executionMapper.countActiveExecutionsForTask(task.getId());
        return count == null ? 0L : count;
    }

    private Long safeVersion(Long scheduleVersion) {
        return scheduleVersion == null ? 0L : scheduleVersion;
    }

    /**
     * 本轮调度累积器。
     *
     * <p>使用一个小型内部对象比在循环里维护一长串局部变量更清晰，也避免多个计数在方法间传递时写错顺序。</p>
     */
    private static class DispatchAccumulator {
        private int dispatchedTaskCount;
        private int createdExecutionCount;
        private int skippedByConcurrencyCount;
        private int skippedByMisfirePolicyCount;
        private int skippedByRaceCount;
        private int skippedInvalidConfigCount;
        private final boolean dryRun;
        private final List<Long> executionIds = new ArrayList<>();
        private final Set<String> issueCodes = new LinkedHashSet<>();

        private DispatchAccumulator(boolean dryRun) {
            this.dryRun = dryRun;
        }

        private SyncTaskScheduleDispatchResult toResult(int scannedTaskCount) {
            return new SyncTaskScheduleDispatchResult(
                    scannedTaskCount,
                    dispatchedTaskCount,
                    createdExecutionCount,
                    skippedByConcurrencyCount,
                    skippedByMisfirePolicyCount,
                    skippedByRaceCount,
                    skippedInvalidConfigCount,
                    dryRun,
                    List.copyOf(executionIds),
                    List.copyOf(issueCodes),
                    "定时同步任务调度完成，scanned=" + scannedTaskCount
                            + ", dispatchedTasks=" + dispatchedTaskCount
                            + ", createdExecutions=" + createdExecutionCount
                            + ", skippedByConcurrency=" + skippedByConcurrencyCount
                            + ", skippedByMisfirePolicy=" + skippedByMisfirePolicyCount
                            + ", skippedByRace=" + skippedByRaceCount
                            + ", skippedInvalidConfig=" + skippedInvalidConfigCount
                            + ", dryRun=" + dryRun
                            + ", at=" + Instant.now()
            );
        }
    }
}
