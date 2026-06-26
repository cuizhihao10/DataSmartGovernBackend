/**
 * @Author : Cui
 * @Date: 2026/06/27 02:33
 * @Description DataSmart Govern Backend - SyncExecutionCallbackControlSignalSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 同步执行器回调控制信号支撑组件。
 *
 * <p>本组件处理的是 worker 在 checkpoint、complete、fail 等“写入型回调”阶段遇到控制面暂停/取消时的语义。
 * 前一阶段 heartbeat 已经能返回 {@code STOP_FOR_PAUSE}/{@code STOP_FOR_CANCEL}，但真实 worker 可能存在网络延迟、
 * 本地批处理缓冲或最后一次回调重试，因此即使 heartbeat 已经补齐，checkpoint/complete/fail 仍必须再次 fail-closed。
 *
 * <p>为什么不直接复用 heartbeat DTO：
 * 1. checkpoint/complete/fail 现有成功返回值分别是 checkpoint、execution、errorSample，贸然统一返回 DTO 会破坏现有 API；
 * 2. 这些接口在“成功写入”时仍应返回原业务对象，在“已暂停/已取消”时通过业务异常表达停止语义即可；
 * 3. 异常消息只包含低敏控制动作、状态和回调动作，不返回 checkpointValue、错误样本、SQL、连接串、凭据或内部端点。
 *
 * <p>安全原则：
 * 1. 只有当前 execution 的 executorId 持有者才能读取暂停/取消控制信号；
 * 2. executorId 不匹配时返回 FORBIDDEN，不透露 execution 是否暂停或取消，避免通过 executionId 枚举探测状态；
 * 3. 状态是 PAUSED/CANCELLED 时不允许再写 checkpoint、成功态或失败样本，防止用户已停止任务后仍有数据继续落地。
 */
@Component
public class SyncExecutionCallbackControlSignalSupport {

    private static final Set<String> ACTIVE_EXECUTION_STATES = Set.of(
            SyncExecutionState.RUNNING.name(),
            SyncExecutionState.RETRYING.name()
    );

    /**
     * 检查当前 execution 是否已经被控制面暂停或取消。
     *
     * <p>该方法应放在幂等判断之前。
     * 这样做是为了避免 worker 使用重复 idempotencyKey 时，服务端因为命中旧幂等记录而返回旧结果，
     * 让 worker 误以为仍可继续写入。控制面停止信号的优先级应高于普通重复请求。
     *
     * @param execution 当前执行记录
     * @param executorId worker 上报的执行器 ID
     * @param callbackAction 当前回调动作，例如 CHECKPOINT、COMPLETE、FAIL
     */
    public void assertNoStoppedControlSignal(SyncExecution execution, String executorId, String callbackAction) {
        if (SyncExecutionState.PAUSED.name().equals(execution.getExecutionState())) {
            requireExecutorForControlSignal(execution, executorId);
            throw stoppedControlException(execution, callbackAction, "STOP_FOR_PAUSE",
                    "同步任务已被暂停，执行器应停止当前回调写入并等待后续恢复");
        }
        if (SyncExecutionState.CANCELLED.name().equals(execution.getExecutionState())) {
            requireExecutorForControlSignal(execution, executorId);
            throw stoppedControlException(execution, callbackAction, "STOP_FOR_CANCEL",
                    "同步任务已被取消，执行器应停止当前回调写入并释放本地资源");
        }
    }

    /**
     * 校验 checkpoint/complete 这类“只能在活跃执行中写入”的回调。
     *
     * <p>checkpoint 和 complete 都会改变执行进度或终态。
     * 如果 execution 已经不在 RUNNING/RETRYING，就说明它已经被暂停、取消、完成、失败或仍未启动，
     * 此时继续写入会破坏状态机一致性。
     */
    public void assertActiveCallbackAllowed(SyncExecution execution, String executorId, String callbackAction) {
        requireActiveExecution(execution, callbackAction);
        requireExecutor(execution, executorId, callbackAction);
    }

    /**
     * 校验 fail 回调是否允许写入。
     *
     * <p>fail 比 checkpoint/complete 特殊：QUEUED 阶段也可能出现执行前失败，
     * 例如 worker 做预检时发现连接器配置缺失、凭据不可用或依赖容量不足。
     * 因此 QUEUED 可以写失败；但 PAUSED/CANCELLED 已由 {@link #assertNoStoppedControlSignal} 处理，
     * 其它非活跃状态仍然拒绝。
     */
    public void assertFailureCallbackAllowed(SyncExecution execution, String executorId) {
        if (SyncExecutionState.QUEUED.name().equals(execution.getExecutionState())) {
            return;
        }
        assertActiveCallbackAllowed(execution, executorId, "FAIL");
    }

    private void requireActiveExecution(SyncExecution execution, String callbackAction) {
        if (!ACTIVE_EXECUTION_STATES.contains(execution.getExecutionState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前执行状态不允许写入执行器回调，callbackAction=" + callbackAction
                            + ", executionState=" + execution.getExecutionState());
        }
    }

    private void requireExecutorForControlSignal(SyncExecution execution, String executorId) {
        if (!matchesExecutor(execution, executorId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "executorId 不匹配，不能读取同步执行控制信号或写入执行器回调");
        }
    }

    private void requireExecutor(SyncExecution execution, String executorId, String callbackAction) {
        if (!matchesExecutor(execution, executorId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "executorId 不匹配，不能写入执行器回调，callbackAction=" + callbackAction);
        }
    }

    private PlatformBusinessException stoppedControlException(SyncExecution execution,
                                                              String callbackAction,
                                                              String controlAction,
                                                              String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                message
                        + "，callbackAction=" + callbackAction
                        + ", controlAction=" + controlAction
                        + ", executionState=" + execution.getExecutionState()
                        + ", shouldContinue=false");
    }

    private boolean matchesExecutor(SyncExecution execution, String executorId) {
        return executorId != null
                && !executorId.isBlank()
                && execution.getExecutorId() != null
                && executorId.trim().equals(execution.getExecutorId());
    }
}
