/**
 * @Author : Cui
 * @Date: 2026/05/07 21:30
 * @Description DataSmart Govern Backend - SyncTaskStateMachineSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 同步任务状态机支撑组件。
 *
 * <p>状态机独立出来，是为了防止 ServiceImpl 里散落大量 if/else。
 * 对数据同步这种任务型产品来说，状态流转是核心业务规则：哪些状态能运行、哪些状态能暂停、失败后如何重试、
 * 部分成功是否允许补偿，都应该有统一入口。
 */
@Component
public class SyncTaskStateMachineSupport {

    private static final Set<SyncTaskState> RUN_ALLOWED_STATES = Set.of(
            SyncTaskState.CONFIGURED,
            SyncTaskState.SCHEDULED,
            SyncTaskState.PAUSED,
            SyncTaskState.FAILED,
            SyncTaskState.PARTIALLY_SUCCEEDED
    );

    /**
     * 允许被用户主动暂停的任务状态。
     *
     * <p>暂停不是“删除任务”，而是一个可恢复的运营控制动作：
     * 1. SCHEDULED 表示任务还没入队，暂停后调度器不应继续推入队列；
     * 2. QUEUED 表示 execution 已经等待 worker 认领，暂停后应把最近 execution 标记为 PAUSED，避免继续被认领；
     * 3. RUNNING/RETRYING 表示任务已经被执行器处理，当前版本先写入控制面暂停信号，后续 worker 需要在心跳或 checkpoint 阶段协作停止。
     */
    private static final Set<SyncTaskState> PAUSE_ALLOWED_STATES = Set.of(
            SyncTaskState.SCHEDULED,
            SyncTaskState.QUEUED,
            SyncTaskState.RUNNING,
            SyncTaskState.RETRYING
    );

    /**
     * 允许恢复的任务状态。
     *
     * <p>按照状态机文档，恢复只从 PAUSED 出发。FAILED 应走 retry，AWAITING_OPERATOR_ACTION 应走人工介入处理入口，
     * 这样可以让前端按钮、审计动作和运营语义保持清晰。
     */
    private static final Set<SyncTaskState> RESUME_ALLOWED_STATES = Set.of(SyncTaskState.PAUSED);

    /**
     * 允许普通用户重试的任务状态。
     *
     * <p>FAILED 和 PARTIALLY_SUCCEEDED 是典型可重试状态：
     * 1. FAILED 表示上次执行未达到目标结果；
     * 2. PARTIALLY_SUCCEEDED 表示已有部分数据落地，后续需要依赖 checkpoint、幂等写入或去重策略补齐。
     *
     * <p>AWAITING_OPERATOR_ACTION 不放在这里，是因为它已经进入运营介入流程，必须由 attention 接口显式确认后再重跑，
     * 避免用户绕过“多次退避/租约恢复失败”这类高风险问题。
     */
    private static final Set<SyncTaskState> RETRY_ALLOWED_STATES = Set.of(
            SyncTaskState.FAILED,
            SyncTaskState.PARTIALLY_SUCCEEDED
    );

    /**
     * 允许取消的任务状态。
     *
     * <p>取消是终止后续执行意图，不等同于归档：
     * 仍处于活跃、可运行或失败待处理窗口的任务可以取消；SUCCEEDED、CANCELLED、ARCHIVED 不再允许取消，
     * AWAITING_OPERATOR_ACTION 则保留给 attention 专用取消入口，保证运营闭环可审计。
     */
    private static final Set<SyncTaskState> CANCEL_ALLOWED_STATES = Set.of(
            SyncTaskState.DRAFT,
            SyncTaskState.CONFIGURED,
            SyncTaskState.PENDING_APPROVAL,
            SyncTaskState.SCHEDULED,
            SyncTaskState.QUEUED,
            SyncTaskState.RUNNING,
            SyncTaskState.PAUSED,
            SyncTaskState.RETRYING,
            SyncTaskState.PARTIALLY_SUCCEEDED,
            SyncTaskState.FAILED
    );

    /**
     * 校验任务是否允许进入队列。
     */
    public void assertCanQueue(String currentState) {
        SyncTaskState state = resolveState(currentState);
        if (!RUN_ALLOWED_STATES.contains(state)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前状态不允许进入同步队列: " + state.name());
        }
    }

    /**
     * 校验任务是否允许暂停。
     *
     * <p>Controller 不直接判断状态，是为了让状态规则集中在一个地方：
     * 后续如果增加“维护窗口批量暂停”“连接器禁用导致自动暂停”等能力，只需要复用这里的规则或新增专门方法。
     */
    public void assertCanPause(String currentState) {
        assertAllowed(currentState, PAUSE_ALLOWED_STATES, "当前状态不允许暂停同步任务");
    }

    /**
     * 校验任务是否允许恢复。
     */
    public void assertCanResume(String currentState) {
        assertAllowed(currentState, RESUME_ALLOWED_STATES, "当前状态不允许恢复同步任务");
    }

    /**
     * 校验任务是否允许普通重试。
     */
    public void assertCanRetry(String currentState) {
        assertAllowed(currentState, RETRY_ALLOWED_STATES, "当前状态不允许重试同步任务");
    }

    /**
     * 校验任务是否允许取消。
     */
    public void assertCanCancel(String currentState) {
        assertAllowed(currentState, CANCEL_ALLOWED_STATES, "当前状态不允许取消同步任务");
    }

    /**
     * 解析状态编码。
     */
    public SyncTaskState resolveState(String state) {
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

    /**
     * 通用状态准入校验。
     *
     * <p>所有 assertCanXxx 方法都复用这一段，是为了保证错误码、错误信息和大小写解析逻辑完全一致。
     * 这样前端或调用方在处理生命周期按钮禁用、错误提示、批量操作失败明细时，不需要为不同动作适配多套异常格式。
     */
    private void assertAllowed(String currentState, Set<SyncTaskState> allowedStates, String message) {
        SyncTaskState state = resolveState(currentState);
        if (!allowedStates.contains(state)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    message + ": " + state.name());
        }
    }
}
