/**
 * @Author : Cui
 * @Date: 2026/06/27 22:10
 * @Description DataSmart Govern Backend - SyncTaskRecoveryOperationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionRecoveryPlanMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 同步任务恢复类操作支撑组件。
 *
 * <p>本组件专门承接 replay/backfill 两类“恢复性运行”能力：
 * 1. replay 关注“从哪一次 execution / checkpoint 重新回放”，常用于失败恢复、下游重建、错误写入修复；
 * 2. backfill 关注“补哪个时间窗口、分区窗口或业务分片”，常用于历史缺口补齐、晚到数据补偿、客户指定区间重刷。
 *
 * <p>为什么不继续放在 DataSyncServiceImpl：
 * replay/backfill 同时涉及任务状态机、来源执行记录校验、checkpoint 解析、恢复计划持久化、新 execution 创建、任务主状态更新和审计。
 * 如果全部堆进主 Service，后续再接真实 worker、连接器能力矩阵、审批流或容量策略时会快速变成一个难维护的大文件。
 * 这里把恢复类动作独立出来，让主 Service 只负责“读取任务并委托领域组件”，文件层次更清楚，也更适合后续扩展。
 *
 * <p>当前边界：
 * 当前版本只落地“控制面契约”，也就是创建 QUEUED execution 与恢复计划，不直接读取源端或写入目标端数据。
 * 真正的数据搬运应由后续 data-sync worker 根据 executionId 查询恢复计划，再结合连接器能力、checkpoint 和幂等写入策略执行。
 */
@Component
@RequiredArgsConstructor
public class SyncTaskRecoveryOperationSupport {

    /**
     * replay 允许从稳定历史状态发起。
     *
     * <p>这里刻意不允许 QUEUED/RUNNING/RETRYING/PAUSED：
     * 这些状态说明任务仍处于活跃执行或暂停窗口，直接 replay 会制造并发写入、checkpoint 竞争和审计解释歧义。
     * 如果用户确实想处理活跃任务，应先 pause/cancel 或等待其进入终态，再发起恢复性运行。
     */
    private static final Set<SyncTaskState> REPLAY_ALLOWED_TASK_STATES = Set.of(
            SyncTaskState.FAILED,
            SyncTaskState.PARTIALLY_SUCCEEDED,
            SyncTaskState.SUCCEEDED,
            SyncTaskState.CANCELLED
    );

    /**
     * backfill 允许从配置完成或稳定历史状态发起。
     *
     * <p>CONFIGURED 没有历史 execution 也可以补数，因为补数窗口本身已经表达了执行范围。
     * SCHEDULED 不放开，是为了避免计划调度和人工补数并行触发，后续可以通过“调度暂停后补数”或“运维窗口补数”来解决。
     */
    private static final Set<SyncTaskState> BACKFILL_ALLOWED_TASK_STATES = Set.of(
            SyncTaskState.CONFIGURED,
            SyncTaskState.FAILED,
            SyncTaskState.PARTIALLY_SUCCEEDED,
            SyncTaskState.SUCCEEDED,
            SyncTaskState.CANCELLED
    );

    /**
     * replay 来源 execution 必须是已经形成历史事实的状态。
     *
     * <p>如果来源 execution 仍在 RUNNING 或 RETRYING，把它当作回放来源会造成“源 execution 还没结束，
     * 新 execution 已经基于它的 checkpoint 重新跑”的竞态，因此这里 fail-closed。
     */
    private static final Set<String> REPLAYABLE_SOURCE_EXECUTION_STATES = Set.of(
            SyncExecutionState.FAILED.name(),
            SyncExecutionState.PARTIALLY_SUCCEEDED.name(),
            SyncExecutionState.SUCCEEDED.name(),
            SyncExecutionState.CANCELLED.name()
    );

    /**
     * 低敏字段最大长度。
     *
     * <p>这些字段会进入业务表、审计摘要和未来运营台列表。
     * 限长不是为了替代字段校验，而是避免把大段 SQL、JSON、样本数据或异常堆栈误塞进控制面表。
     */
    private static final int WINDOW_BOUNDARY_MAX_LENGTH = 128;
    private static final int SHARD_SELECTOR_MAX_LENGTH = 256;
    private static final int REASON_MAX_LENGTH = 500;

    /**
     * 基础敏感关键词。
     *
     * <p>这里是服务层兜底，不是完整 DLP。它的目标是挡住最明显的密码、token、连接串、SQL、prompt、payload 和样本数据。
     * 生产环境还应在 gateway、前端表单、审计管道和安全扫描中继续增强敏感信息识别。
     */
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password",
            "passwd",
            "token",
            "secret",
            "credential",
            "access_key",
            "private_key",
            "jdbc:",
            "select ",
            "insert ",
            "update ",
            "delete ",
            "where ",
            "sql",
            "prompt",
            "payload",
            "sample",
            "密码",
            "密钥",
            "令牌",
            "凭证",
            "样本",
            "连接串"
    );

    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncCheckpointMapper checkpointMapper;
    private final SyncExecutionRecoveryPlanMapper recoveryPlanMapper;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 从历史 execution 或 checkpoint 发起回放。
     *
     * @param task 已经由 DataSyncServiceImpl 完成租户、项目、SELF 数据范围校验的同步任务
     * @param request 回放请求，可为空；为空时默认使用 task.lastExecutionId 与该 execution 最新 checkpoint
     * @param actorContext 操作者上下文，用于 execution.triggeredBy 与审计 actor 字段
     * @return 面向前端的低敏操作结果，只包含任务 ID、目标状态和新 executionId 摘要
     */
    public SyncTaskOperationResult replayTask(SyncTask task,
                                              SyncTaskRecoveryOperationRequest request,
                                              SyncActorContext actorContext) {
        assertTaskStateAllowed(task, REPLAY_ALLOWED_TASK_STATES, "当前状态不允许发起同步回放");
        SyncExecution sourceExecution = resolveReplaySourceExecution(task, request);
        SyncCheckpoint sourceCheckpoint = resolveReplaySourceCheckpoint(task, sourceExecution, request);
        SyncExecution newExecution = executionCreationSupport.createQueuedExecution(task, actorContext, SyncTriggerType.REPLAY);
        SyncExecutionRecoveryPlan plan = createPlan(
                task,
                newExecution,
                SyncTriggerType.REPLAY,
                sourceExecution,
                sourceCheckpoint,
                null,
                null,
                null,
                sanitizeReason(request, "用户发起同步回放"));
        markTaskQueued(task, SyncTriggerType.REPLAY, newExecution);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), newExecution.getId(), SyncAuditActionType.REPLAY_TASK,
                actorContext, auditPayload(plan));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.QUEUED.name(),
                "同步回放计划已创建并进入待执行队列，executionId=" + newExecution.getId()
                        + "，recoveryPlanId=" + plan.getId());
    }

    /**
     * 按时间窗口、分区窗口或业务分片发起补数。
     *
     * <p>backfill 至少要提供一个窗口边界或分片选择器，否则它会退化成一次普通 run。
     * 该规则能让审计员和运维人员明确知道“这次补数到底补了什么范围”，也便于后续 worker 做容量预估和分片并行。
     */
    public SyncTaskOperationResult backfillTask(SyncTask task,
                                                SyncTaskRecoveryOperationRequest request,
                                                SyncActorContext actorContext) {
        assertTaskStateAllowed(task, BACKFILL_ALLOWED_TASK_STATES, "当前状态不允许发起同步补数");
        BackfillWindow window = resolveBackfillWindow(request);
        SyncExecution sourceExecution = resolveOptionalSourceExecution(task, request);
        SyncCheckpoint sourceCheckpoint = resolveOptionalSourceCheckpoint(task, sourceExecution, request);
        SyncExecution newExecution = executionCreationSupport.createQueuedExecution(task, actorContext, SyncTriggerType.BACKFILL);
        SyncExecutionRecoveryPlan plan = createPlan(
                task,
                newExecution,
                SyncTriggerType.BACKFILL,
                sourceExecution,
                sourceCheckpoint,
                window.windowStart(),
                window.windowEnd(),
                window.shardOrPartition(),
                sanitizeReason(request, "用户发起历史补数"));
        markTaskQueued(task, SyncTriggerType.BACKFILL, newExecution);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), newExecution.getId(), SyncAuditActionType.BACKFILL_TASK,
                actorContext, auditPayload(plan));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.QUEUED.name(),
                "同步补数计划已创建并进入待执行队列，executionId=" + newExecution.getId()
                        + "，recoveryPlanId=" + plan.getId());
    }

    /**
     * 校验任务主状态是否允许发起恢复性动作。
     *
     * <p>这里不复用普通 runTask 的 assertCanQueue，因为 replay/backfill 的语义更强：
     * runTask 可以从 PAUSED 或 FAILED 等状态发起普通运行，但 replay/backfill 必须明确避开活跃执行窗口，
     * 并且要区分“回放必须有历史 execution”“补数可以只有窗口边界”。
     */
    private void assertTaskStateAllowed(SyncTask task, Set<SyncTaskState> allowedStates, String message) {
        SyncTaskState state = resolveTaskState(task == null ? null : task.getCurrentState());
        if (!allowedStates.contains(state)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    message + ": " + state.name());
        }
    }

    /**
     * 解析 replay 来源 execution。
     *
     * <p>优先使用 request.sourceExecutionId；如果调用方没有传，则使用任务主表 lastExecutionId。
     * 这种默认行为便于“从最近失败执行回放”的常见操作，同时仍然允许运营人员显式指定更早的历史执行。
     */
    private SyncExecution resolveReplaySourceExecution(SyncTask task, SyncTaskRecoveryOperationRequest request) {
        Long sourceExecutionId = request == null ? null : request.getSourceExecutionId();
        if (sourceExecutionId == null) {
            sourceExecutionId = task.getLastExecutionId();
        }
        if (sourceExecutionId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "同步回放必须指定 sourceExecutionId，或任务必须存在 lastExecutionId");
        }
        SyncExecution sourceExecution = loadExecutionForTask(task, sourceExecutionId);
        if (!REPLAYABLE_SOURCE_EXECUTION_STATES.contains(sourceExecution.getExecutionState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "来源执行记录仍处于不可回放状态: " + sourceExecution.getExecutionState());
        }
        return sourceExecution;
    }

    /**
     * 解析 replay 来源 checkpoint。
     *
     * <p>显式 checkpointId 表示用户要从某个已知恢复点回放；
     * 未传 checkpointId 时读取来源 execution 最新 checkpoint；
     * 如果没有 checkpoint，则允许从来源 execution 起点回放，计划中的 sourceCheckpointId 保持为空。
     */
    private SyncCheckpoint resolveReplaySourceCheckpoint(SyncTask task,
                                                         SyncExecution sourceExecution,
                                                         SyncTaskRecoveryOperationRequest request) {
        Long requestedCheckpointId = request == null ? null : request.getSourceCheckpointId();
        if (requestedCheckpointId != null) {
            return loadCheckpointForTask(task, sourceExecution, requestedCheckpointId);
        }
        return checkpointMapper.selectOne(new LambdaQueryWrapper<SyncCheckpoint>()
                .eq(SyncCheckpoint::getSyncTaskId, task.getId())
                .eq(SyncCheckpoint::getExecutionId, sourceExecution.getId())
                .orderByDesc(SyncCheckpoint::getCheckpointTime)
                .orderByDesc(SyncCheckpoint::getId)
                .last("LIMIT 1"));
    }

    /**
     * backfill 可选来源 execution。
     *
     * <p>补数主要由窗口决定，不强制绑定历史 execution。
     * 但在“按某次运行的上下文补一个分区”场景中，调用方可以提供 sourceExecutionId 作为审计锚点。
     */
    private SyncExecution resolveOptionalSourceExecution(SyncTask task, SyncTaskRecoveryOperationRequest request) {
        Long sourceExecutionId = request == null ? null : request.getSourceExecutionId();
        if (sourceExecutionId == null) {
            return null;
        }
        return loadExecutionForTask(task, sourceExecutionId);
    }

    /**
     * backfill 可选来源 checkpoint。
     *
     * <p>如果只传 checkpointId 而不传 sourceExecutionId，也可以通过 checkpoint 自身校验任务归属；
     * 如果两者都传，则额外校验 checkpoint.executionId 与 sourceExecution.id 一致，避免跨执行记录拼接恢复上下文。
     */
    private SyncCheckpoint resolveOptionalSourceCheckpoint(SyncTask task,
                                                           SyncExecution sourceExecution,
                                                           SyncTaskRecoveryOperationRequest request) {
        Long checkpointId = request == null ? null : request.getSourceCheckpointId();
        if (checkpointId == null) {
            return null;
        }
        return loadCheckpointForTask(task, sourceExecution, checkpointId);
    }

    private SyncExecution loadExecutionForTask(SyncTask task, Long executionId) {
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步执行记录不存在: " + executionId);
        }
        if (!task.getId().equals(execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步执行记录不属于当前任务，taskId=" + task.getId() + ", executionId=" + executionId);
        }
        return execution;
    }

    private SyncCheckpoint loadCheckpointForTask(SyncTask task,
                                                 SyncExecution sourceExecution,
                                                 Long checkpointId) {
        SyncCheckpoint checkpoint = checkpointMapper.selectById(checkpointId);
        if (checkpoint == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步 checkpoint 不存在: " + checkpointId);
        }
        if (!task.getId().equals(checkpoint.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步 checkpoint 不属于当前任务，taskId=" + task.getId() + ", checkpointId=" + checkpointId);
        }
        if (sourceExecution != null && !sourceExecution.getId().equals(checkpoint.getExecutionId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步 checkpoint 与来源 execution 不一致，executionId=" + sourceExecution.getId()
                            + ", checkpointExecutionId=" + checkpoint.getExecutionId());
        }
        return checkpoint;
    }

    private BackfillWindow resolveBackfillWindow(SyncTaskRecoveryOperationRequest request) {
        String windowStart = sanitizeBoundary(request == null ? null : request.getWindowStart(), "windowStart",
                WINDOW_BOUNDARY_MAX_LENGTH);
        String windowEnd = sanitizeBoundary(request == null ? null : request.getWindowEnd(), "windowEnd",
                WINDOW_BOUNDARY_MAX_LENGTH);
        String shardOrPartition = sanitizeBoundary(request == null ? null : request.getShardOrPartition(), "shardOrPartition",
                SHARD_SELECTOR_MAX_LENGTH);
        if (windowStart == null && windowEnd == null && shardOrPartition == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "同步补数必须至少提供 windowStart、windowEnd 或 shardOrPartition 之一");
        }
        return new BackfillWindow(windowStart, windowEnd, shardOrPartition);
    }

    private SyncExecutionRecoveryPlan createPlan(SyncTask task,
                                                 SyncExecution execution,
                                                 SyncTriggerType recoveryType,
                                                 SyncExecution sourceExecution,
                                                 SyncCheckpoint sourceCheckpoint,
                                                 String windowStart,
                                                 String windowEnd,
                                                 String shardOrPartition,
                                                 String reason) {
        SyncExecutionRecoveryPlan plan = new SyncExecutionRecoveryPlan();
        plan.setTenantId(task.getTenantId());
        plan.setProjectId(task.getProjectId());
        plan.setWorkspaceId(task.getWorkspaceId());
        plan.setSyncTaskId(task.getId());
        plan.setExecutionId(execution.getId());
        plan.setRecoveryType(recoveryType.name());
        plan.setSourceExecutionId(sourceExecution == null ? null : sourceExecution.getId());
        plan.setSourceCheckpointId(sourceCheckpoint == null ? null : sourceCheckpoint.getId());
        plan.setWindowStart(windowStart);
        plan.setWindowEnd(windowEnd);
        plan.setShardOrPartition(shardOrPartition);
        plan.setReason(reason);
        plan.setPlanState("CREATED");
        plan.setCreateTime(LocalDateTime.now());
        plan.setUpdateTime(LocalDateTime.now());
        recoveryPlanMapper.insert(plan);
        return plan;
    }

    /**
     * 将任务主状态推进到 QUEUED。
     *
     * <p>replay/backfill 创建的是新 execution，因此任务运营视图统一回到 QUEUED。
     * triggerType 写 REPLAY/BACKFILL，用于列表筛选、审计统计、worker 调度策略和后续容量报表。
     */
    private void markTaskQueued(SyncTask task, SyncTriggerType triggerType, SyncExecution execution) {
        int updated = taskMapper.markLifecycleState(
                task.getId(),
                SyncTaskState.QUEUED.name(),
                triggerType.name(),
                execution.getId());
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步恢复计划创建后更新任务状态失败，taskId=" + task.getId()
                            + ", executionId=" + execution.getId());
        }
    }

    private SyncTaskState resolveTaskState(String state) {
        if (state == null || state.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "同步任务状态不能为空");
        }
        try {
            return SyncTaskState.valueOf(state.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未知同步任务状态: " + state);
        }
    }

    private String sanitizeBoundary(String value, String fieldName, int maxLength) {
        String compact = compact(value);
        if (compact == null) {
            return null;
        }
        assertLowSensitive(compact, fieldName);
        return truncate(compact, maxLength);
    }

    private String sanitizeReason(SyncTaskRecoveryOperationRequest request, String defaultReason) {
        String compact = compact(request == null ? null : request.getReason());
        if (compact == null) {
            return defaultReason;
        }
        if (containsSensitiveKeyword(compact)) {
            return "操作原因包含敏感关键词，已按审计低敏策略脱敏";
        }
        return truncate(compact, REASON_MAX_LENGTH);
    }

    private void assertLowSensitive(String value, String fieldName) {
        if (containsSensitiveKeyword(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 包含敏感关键词，不能写入同步恢复计划");
        }
    }

    private boolean containsSensitiveKeyword(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String compact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String auditPayload(SyncExecutionRecoveryPlan plan) {
        return "recoveryType=" + plan.getRecoveryType()
                + ",recoveryPlanId=" + plan.getId()
                + ",executionId=" + plan.getExecutionId()
                + ",sourceExecutionId=" + plan.getSourceExecutionId()
                + ",sourceCheckpointId=" + plan.getSourceCheckpointId()
                + ",windowStart=" + plan.getWindowStart()
                + ",windowEnd=" + plan.getWindowEnd()
                + ",shardOrPartition=" + plan.getShardOrPartition()
                + ",reason=" + plan.getReason();
    }

    /**
     * backfill 窗口值对象。
     *
     * <p>使用 record 可以让三段窗口参数作为一个整体在私有方法间传递，避免出现多个 String 参数顺序写错。
     */
    private record BackfillWindow(String windowStart, String windowEnd, String shardOrPartition) {
    }
}
