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
     * 允许手工结束的任务状态。
     *
     * <p>手工结束主要面向“任务已经进入或接近执行窗口”的场景，因此允许 QUEUED/RUNNING/RETRYING/PAUSED。
     * 对 SCHEDULED 等待调度的长期任务，如果用户想让它不再自动触发，应使用 offline 下线；
     * 对 FAILED/SUCCEEDED 这类已经有执行结论的任务，不应该再把历史结果改写成人工结束。</p>
     */
    private static final Set<SyncTaskState> MANUAL_TERMINATE_ALLOWED_STATES = Set.of(
            SyncTaskState.QUEUED,
            SyncTaskState.RUNNING,
            SyncTaskState.RETRYING,
            SyncTaskState.PAUSED
    );

    /**
     * 允许下线的任务状态。
     *
     * <p>下线是“停止后续调度与管理面删除前置”的动作，不负责强杀运行中的 execution。
     * 因此 QUEUED/RUNNING/RETRYING 这类活跃状态必须先 pause/cancel/terminate，避免后台仍有执行器写入时把任务从正常列表移走。</p>
     */
    private static final Set<SyncTaskState> OFFLINE_ALLOWED_STATES = Set.of(
            SyncTaskState.DRAFT,
            SyncTaskState.CONFIGURED,
            SyncTaskState.PENDING_APPROVAL,
            SyncTaskState.SCHEDULED,
            SyncTaskState.PAUSED,
            SyncTaskState.PARTIALLY_SUCCEEDED,
            SyncTaskState.SUCCEEDED,
            SyncTaskState.FAILED,
            SyncTaskState.AWAITING_OPERATOR_ACTION,
            SyncTaskState.CANCELLED,
            SyncTaskState.MANUALLY_TERMINATED,
            SyncTaskState.ARCHIVED
    );

    /**
     * 允许进入回收站的任务状态。
     *
     * <p>删除前必须先下线，是为了让用户和系统都明确经历“停止调度 -> 移入回收站 -> 彻底删除”的三段式流程。
     * 这比直接删除更适合商用产品，因为它给误操作、审计复核和配置克隆留下安全窗口。</p>
     */
    private static final Set<SyncTaskState> RECYCLE_ALLOWED_STATES = Set.of(SyncTaskState.OFFLINE);

    /**
     * 允许彻底删除的任务状态。
     *
     * <p>当前彻底删除采用逻辑 DELETED。只有 RECYCLED 可以进入 DELETED，避免用户绕过回收站直接让任务从普通列表消失。</p>
     */
    private static final Set<SyncTaskState> HARD_DELETE_ALLOWED_STATES = Set.of(SyncTaskState.RECYCLED);

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
     * 校验任务是否允许手工结束。
     */
    public void assertCanManualTerminate(String currentState) {
        assertAllowed(currentState, MANUAL_TERMINATE_ALLOWED_STATES, "当前状态不允许手工结束同步任务");
    }

    /**
     * 校验任务是否允许下线。
     */
    public void assertCanOffline(String currentState) {
        assertAllowed(currentState, OFFLINE_ALLOWED_STATES, "当前状态不允许下线同步任务");
    }

    /**
     * 校验任务是否允许进入回收站。
     */
    public void assertCanRecycle(String currentState) {
        assertAllowed(currentState, RECYCLE_ALLOWED_STATES, "同步任务必须先下线后才能删除进回收站");
    }

    /**
     * 校验任务是否允许彻底删除。
     */
    public void assertCanHardDelete(String currentState) {
        assertAllowed(currentState, HARD_DELETE_ALLOWED_STATES, "同步任务必须位于回收站后才能彻底删除");
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
