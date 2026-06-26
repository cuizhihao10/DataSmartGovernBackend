/**
 * @Author : Cui
 * @Date: 2026/06/27 02:33
 * @Description DataSmart Govern Backend - SyncExecutionCallbackControlSignalSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 执行器回调控制信号支撑组件测试。
 *
 * <p>本测试覆盖的是 checkpoint/complete/fail 在控制台暂停/取消之后的协议语义。
 * heartbeat 已经能返回停止指令，但 worker 仍可能因为批处理尾部、网络重试或本地缓存提交最后一次回调。
 * 因此这里要保证回调写入入口也能识别停止信号，并给出低敏、明确、不可继续的业务异常。
 */
class SyncExecutionCallbackControlSignalSupportTest {

    private final SyncExecutionCallbackControlSignalSupport support = new SyncExecutionCallbackControlSignalSupport();

    /**
     * PAUSED 状态下，原租约持有者写 checkpoint 应收到 STOP_FOR_PAUSE。
     */
    @Test
    void pausedExecutionShouldReturnStopForPauseControlMessage() {
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> support.assertNoStoppedControlSignal(execution(SyncExecutionState.PAUSED, "worker-1"),
                        "worker-1", "CHECKPOINT"));

        assertThat(exception.getMessage())
                .contains("STOP_FOR_PAUSE")
                .contains("CHECKPOINT")
                .contains("shouldContinue=false")
                .doesNotContain("checkpointValue")
                .doesNotContain("samplePayload");
    }

    /**
     * CANCELLED 状态下，原租约持有者写 complete 应收到 STOP_FOR_CANCEL。
     */
    @Test
    void cancelledExecutionShouldReturnStopForCancelControlMessage() {
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> support.assertNoStoppedControlSignal(execution(SyncExecutionState.CANCELLED, "worker-1"),
                        "worker-1", "COMPLETE"));

        assertThat(exception.getMessage())
                .contains("STOP_FOR_CANCEL")
                .contains("COMPLETE")
                .contains("shouldContinue=false");
    }

    /**
     * executorId 不匹配时不能读取停止信号。
     *
     * <p>这里特意验证消息中不包含 STOP_FOR_PAUSE，避免错误 worker 通过猜 executionId 探测状态。
     */
    @Test
    void differentExecutorShouldNotSeeStoppedControlAction() {
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> support.assertNoStoppedControlSignal(execution(SyncExecutionState.PAUSED, "worker-owner"),
                        "worker-other", "CHECKPOINT"));

        assertThat(exception.getMessage())
                .contains("executorId 不匹配")
                .doesNotContain("STOP_FOR_PAUSE")
                .doesNotContain("PAUSED");
    }

    /**
     * RUNNING 状态且 executor 匹配时，checkpoint/complete 这类活跃回调应允许继续。
     */
    @Test
    void runningExecutionShouldAllowActiveCallbackForOwner() {
        assertDoesNotThrow(() -> support.assertActiveCallbackAllowed(
                execution(SyncExecutionState.RUNNING, "worker-1"), "worker-1", "CHECKPOINT"));
    }

    /**
     * QUEUED 阶段允许 fail 回调表达执行前失败。
     *
     * <p>这个规则保留了既有行为：worker 在真正开始前发现预检失败时，仍可以把 execution 标记为失败。
     */
    @Test
    void queuedExecutionShouldAllowFailureCallback() {
        assertDoesNotThrow(() -> support.assertFailureCallbackAllowed(
                execution(SyncExecutionState.QUEUED, null), "worker-1"));
    }

    /**
     * 已成功的 execution 不允许再写 complete。
     */
    @Test
    void succeededExecutionShouldRejectActiveCallback() {
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> support.assertActiveCallbackAllowed(
                        execution(SyncExecutionState.SUCCEEDED, "worker-1"), "worker-1", "COMPLETE"));

        assertThat(exception.getMessage())
                .contains("callbackAction=COMPLETE")
                .contains("executionState=SUCCEEDED");
    }

    private SyncExecution execution(SyncExecutionState state, String executorId) {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(1L);
        execution.setExecutionState(state.name());
        execution.setExecutorId(executorId);
        return execution;
    }
}
