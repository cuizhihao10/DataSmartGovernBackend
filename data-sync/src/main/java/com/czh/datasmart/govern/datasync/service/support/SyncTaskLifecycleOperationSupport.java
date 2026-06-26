/**
 * @Author : Cui
 * @Date: 2026/06/27 16:35
 * @Description DataSmart Govern Backend - SyncTaskLifecycleOperationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
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
 * 同步任务生命周期操作支撑组件。
 *
 * <p>这个组件负责用户侧任务控制面动作：暂停、恢复、重试、取消。
 * 它被从 DataSyncServiceImpl 中拆出来，是为了保持主 Service 聚焦“对外契约编排”，避免继续堆积状态机、审计、
 * execution 更新和低敏原因清洗等细节。
 *
 * <p>状态模型需要分两层理解：
 * 1. SyncTask.currentState 是运营视图，回答“这个任务现在应该在列表里显示为什么状态”；
 * 2. SyncExecution.executionState 是某一次运行的 worker 队列/回调状态，回答“这条执行记录是否还能被认领、写 checkpoint 或写结果”。
 *
 * <p>暂停和取消运行中任务时，当前版本采用“控制面协作信号”：
 * 服务端会把最近 execution 改为 PAUSED 或 CANCELLED，使后续 heartbeat/checkpoint/complete/fail 回调被状态校验拒绝；
 * 但已经在 worker 进程里的真实执行逻辑还需要在后续版本读取控制信号并主动停止。
 * 这样比直接假装已经物理杀死执行器更诚实，也更符合分布式任务系统的常见设计。
 */
@Component
@RequiredArgsConstructor
public class SyncTaskLifecycleOperationSupport {

    /**
     * 暂停动作可以同步改写的 execution 状态集合。
     *
     * <p>QUEUED 暂停后不再被 worker 认领；RUNNING/RETRYING 暂停后后续执行器回调会被拒绝；
     * 其他状态例如 SUCCEEDED/FAILED/CANCELLED 已经不属于可暂停的活跃 execution，不应为了按钮操作篡改历史事实。
     */
    private static final Set<String> PAUSABLE_EXECUTION_STATES = Set.of(
            SyncExecutionState.QUEUED.name(),
            SyncExecutionState.RUNNING.name(),
            SyncExecutionState.RETRYING.name()
    );

    /**
     * 取消动作可以同步改写的 execution 状态集合。
     *
     * <p>取消比暂停更接近终止意图，因此 PAUSED 也可以被推进到 CANCELLED；
     * 但 SUCCEEDED 和 FAILED 这类已经形成历史结果的 execution 不在这里改写，任务主状态可以取消，历史执行事实仍保持原样。
     */
    private static final Set<String> CANCELLABLE_EXECUTION_STATES = Set.of(
            SyncExecutionState.QUEUED.name(),
            SyncExecutionState.RUNNING.name(),
            SyncExecutionState.RETRYING.name(),
            SyncExecutionState.PAUSED.name()
    );

    /**
     * 基础敏感词集合。
     *
     * <p>reason 是用户输入，最终会进入审计摘要。这里做轻量兜底，不追求替代完整 DLP，
     * 但可以避免明显的密码、token、连接串、SQL、prompt 或样本数据被原样落库。
     */
    private static final Set<String> SENSITIVE_REASON_KEYWORDS = Set.of(
            "password",
            "token",
            "secret",
            "credential",
            "access_key",
            "private_key",
            "jdbc:",
            "sql",
            "prompt",
            "payload",
            "sample",
            "密码",
            "密钥",
            "令牌",
            "凭证",
            "样本"
    );

    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncTaskStateMachineSupport stateMachineSupport;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 暂停同步任务。
     *
     * @param task 已通过租户、项目、SELF 范围校验的任务对象
     * @param request 生命周期操作请求，可为空；为空时使用默认审计原因
     * @param actorContext 操作者上下文，用于写入审计 actor、role、traceId
     * @return 面向前端或调用方的低敏操作结果
     */
    public SyncTaskOperationResult pauseTask(SyncTask task,
                                             SyncTaskLifecycleOperationRequest request,
                                             SyncActorContext actorContext) {
        stateMachineSupport.assertCanPause(task.getCurrentState());
        String reason = sanitizeReason(request, "用户主动暂停同步任务");
        SyncExecution execution = markLatestExecutionState(
                task,
                PAUSABLE_EXECUTION_STATES,
                SyncExecutionState.PAUSED,
                false,
                reason);
        markTaskState(task, SyncTaskState.PAUSED, null, latestExecutionId(task, execution));
        auditSupport.saveAudit(task.getTenantId(), task.getId(), latestExecutionId(task, execution), SyncAuditActionType.PAUSE_TASK,
                actorContext, auditPayload("pause", latestExecutionId(task, execution), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.PAUSED.name(),
                "同步任务已暂停；如果最近 execution 正在运行，后续执行器回调会被状态校验拒绝，worker 侧需协作停止");
    }

    /**
     * 恢复同步任务。
     *
     * <p>恢复不是把旧 execution 从 PAUSED 改回 QUEUED，而是创建一条新的 execution：
     * 1. 旧 execution 保留暂停历史，便于排障和审计；
     * 2. 新 execution 从 QUEUED 开始，沿用现有租约认领协议；
     * 3. 后续如果实现 checkpoint resume，新 execution 可以读取旧 execution 的 checkpointRef 继续推进。
     */
    public SyncTaskOperationResult resumeTask(SyncTask task,
                                              SyncTaskLifecycleOperationRequest request,
                                              SyncActorContext actorContext) {
        stateMachineSupport.assertCanResume(task.getCurrentState());
        String reason = sanitizeReason(request, "用户恢复已暂停同步任务");
        SyncExecution execution = executionCreationSupport.createQueuedExecution(task, actorContext);
        markTaskState(task, SyncTaskState.QUEUED, SyncTriggerType.MANUAL, execution.getId());
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RESUME_TASK,
                actorContext, auditPayload("resume", execution.getId(), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.QUEUED.name(),
                "同步任务已恢复并重新进入待执行队列，执行记录 ID=" + execution.getId());
    }

    /**
     * 重试同步任务。
     *
     * <p>普通重试只允许 FAILED 或 PARTIALLY_SUCCEEDED。
     * 如果任务已经进入 AWAITING_OPERATOR_ACTION，说明系统认为继续自动重试不安全，必须走人工介入专用接口。
     */
    public SyncTaskOperationResult retryTask(SyncTask task,
                                             SyncTaskLifecycleOperationRequest request,
                                             SyncActorContext actorContext) {
        stateMachineSupport.assertCanRetry(task.getCurrentState());
        String reason = sanitizeReason(request, "用户发起失败同步任务重试");
        SyncExecution execution = executionCreationSupport.createQueuedExecution(task, actorContext);
        markTaskState(task, SyncTaskState.RETRYING, SyncTriggerType.MANUAL, execution.getId());
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RETRY_TASK,
                actorContext, auditPayload("retry", execution.getId(), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.RETRYING.name(),
                "同步任务已创建重试执行记录，executionId=" + execution.getId() + "；等待执行器按租约协议认领");
    }

    /**
     * 取消同步任务。
     *
     * <p>取消会把任务主状态推进到 CANCELLED，并尽可能把最近活跃 execution 也推进到 CANCELLED。
     * 如果最近 execution 已经成功或失败，则不回写历史 execution，只记录任务层面的取消意图。
     */
    public SyncTaskOperationResult cancelTask(SyncTask task,
                                              SyncTaskLifecycleOperationRequest request,
                                              SyncActorContext actorContext) {
        stateMachineSupport.assertCanCancel(task.getCurrentState());
        String reason = sanitizeReason(request, "用户取消同步任务");
        SyncExecution execution = markLatestExecutionState(
                task,
                CANCELLABLE_EXECUTION_STATES,
                SyncExecutionState.CANCELLED,
                true,
                reason);
        markTaskState(task, SyncTaskState.CANCELLED, null, latestExecutionId(task, execution));
        auditSupport.saveAudit(task.getTenantId(), task.getId(), latestExecutionId(task, execution), SyncAuditActionType.CANCEL_TASK,
                actorContext, auditPayload("cancel", latestExecutionId(task, execution), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.CANCELLED.name(),
                "同步任务已取消；如最近 execution 仍处于队列或运行窗口，已同步写入取消控制信号");
    }

    /**
     * 尝试改写最近 execution 状态。
     *
     * <p>该方法只操作 task.lastExecutionId 指向的最近执行记录，不扫描历史 execution：
     * 1. 生命周期按钮表达的是“当前任务控制”，不应批量篡改历史运行记录；
     * 2. 历史 execution 是审计事实，只有当前仍可能被认领或写回的 execution 才需要控制信号；
     * 3. 高并发下如果 worker 已经把 execution 推进到终态，这里会跳过 execution 更新，只改变任务主状态或让状态机拒绝。
     */
    private SyncExecution markLatestExecutionState(SyncTask task,
                                                   Set<String> allowedExecutionStates,
                                                   SyncExecutionState targetState,
                                                   boolean finishExecution,
                                                   String reason) {
        SyncExecution execution = loadLatestExecution(task);
        if (execution == null || !allowedExecutionStates.contains(execution.getExecutionState())) {
            return null;
        }
        execution.setExecutionState(targetState.name());
        execution.setErrorSummary(reason);
        if (finishExecution) {
            execution.setFinishedAt(LocalDateTime.now());
        }
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.updateById(execution);
        return execution;
    }

    /**
     * 读取最近 execution，并校验它确实属于当前任务。
     *
     * <p>lastExecutionId 是任务主表的加速字段，理论上应与 execution.syncTaskId 一致。
     * 如果出现不一致，说明数据库事实已经损坏或存在越权风险，生命周期动作必须 fail-closed。
     */
    private SyncExecution loadLatestExecution(SyncTask task) {
        if (task.getLastExecutionId() == null) {
            return null;
        }
        SyncExecution execution = executionMapper.selectById(task.getLastExecutionId());
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务最近执行记录不存在，taskId=" + task.getId() + ", executionId=" + task.getLastExecutionId());
        }
        if (!task.getId().equals(execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务最近执行记录归属异常，taskId=" + task.getId() + ", executionId=" + execution.getId());
        }
        return execution;
    }

    /**
     * 更新任务主状态，并检查数据库更新结果。
     *
     * <p>如果 update 返回 0，说明任务可能被删除、并发修改或主键异常。
     * 这里选择抛出状态冲突，而不是静默返回成功，避免前端误以为控制动作已经生效。
     */
    private void markTaskState(SyncTask task,
                               SyncTaskState targetState,
                               SyncTriggerType triggerType,
                               Long lastExecutionId) {
        int updated = taskMapper.markLifecycleState(
                task.getId(),
                targetState.name(),
                triggerType == null ? null : triggerType.name(),
                lastExecutionId);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务生命周期状态更新失败，taskId=" + task.getId() + ", targetState=" + targetState.name());
        }
    }

    /**
     * 解析审计和响应要使用的 executionId。
     *
     * <p>如果本次动作真实改写了 execution，则优先使用改写后的 executionId；
     * 否则继续使用 task.lastExecutionId，表示这次任务动作仍然和最近一次执行历史相关联。
     */
    private Long latestExecutionId(SyncTask task, SyncExecution execution) {
        return execution == null ? task.getLastExecutionId() : execution.getId();
    }

    /**
     * 生成低敏审计摘要。
     *
     * <p>审计摘要只放动作、executionId 和清洗后的原因，不放源端/目标端连接信息、SQL、样本数据或 worker 内部细节。
     */
    private String auditPayload(String action, Long executionId, String reason) {
        return "action=" + action
                + ",executionId=" + executionId
                + ",reason=" + reason;
    }

    /**
     * 清洗用户填写的操作原因。
     *
     * <p>这里做的是“基础低敏兜底”，不是完整 DLP：
     * 1. 压缩空白，避免审计日志被换行或大段文本污染；
     * 2. 命中明显敏感词时整段替换为固定说明，避免密码、token、SQL 或样本数据落库；
     * 3. 限制长度，避免审计表承载过大的自由文本。
     */
    private String sanitizeReason(SyncTaskLifecycleOperationRequest request, String defaultReason) {
        String reason = request == null ? null : request.getReason();
        if (reason == null || reason.isBlank()) {
            return defaultReason;
        }
        String compact = reason.trim().replaceAll("\\s+", " ");
        String lower = compact.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_REASON_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "操作原因包含敏感关键词，已按审计低敏策略脱敏";
            }
        }
        return truncate(compact, 500);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
