/**
 * @Author : Cui
 * @Date: 2026/08/14 22:26
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryRetryReceiptTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 data-sync 重排队回执在原 execution 重试与 checkpoint replay 两种状态机分支中的边界。
 *
 * <p>恢复链路不能只看 HTTP 成功或单个状态文本。测试固定同一个任务和可信触发事件，再分别改变预期
 * execution 与 {@code taskState}，证明回执只有在范围和状态机语义同时吻合时才会被后续 Specialist
 * 复核接受。这样可以防止新 execution 被误当成旧 execution 重试，也可以防止状态不完整的下游响应
 * 穿透失败关闭检查。</p>
 */
class AgentAutopilotRecoveryRetryReceiptTest {

    /**
     * 原 execution 重排队必须保留 RETRYING 任务状态，QUEUED 不能被当作同一执行的重试成功。
     *
     * <p>此处使用单参数入口，表示调用方期望回执 execution 与触发 execution 完全一致。虽然 execution
     * 已经回到 {@code QUEUED} 等待 worker 获取，但任务仍处于重试流程，所以只有 {@code RETRYING} 才符合
     * data-sync 的原 execution 状态机；把任务也写成 {@code QUEUED} 必须失败关闭。</p>
     */
    @Test
    void acceptsSameExecutionReceiptOnlyWhenTaskStateIsRetrying() {
        AgentAutopilotRecoveryTriggerEvent event = trigger();
        AgentAutopilotRecoveryRetryReceipt accepted = new AgentAutopilotRecoveryRetryReceipt(
                31L, 41L, 2, "QUEUED", "RETRYING");
        AgentAutopilotRecoveryRetryReceipt rejected = new AgentAutopilotRecoveryRetryReceipt(
                31L, 41L, 2, "QUEUED", "QUEUED");

        assertThat(accepted.matchesRequeuedScope(event)).isTrue();
        assertThat(rejected.matchesRequeuedScope(event)).isFalse();
    }

    /**
     * checkpoint replay 的新 execution 必须使用 QUEUED 任务状态，并且仍要绑定原任务与执行范围。
     *
     * <p>预期 execution 为 {@code 42}，而事件中的失败 execution 为 {@code 41}，因此这是一个新建
     * checkpoint execution。新执行尚未开始，任务状态应为 {@code QUEUED}；沿用原 execution 的
     * {@code RETRYING} 会被拒绝。最后一个断言同时确认即使状态正确，跨任务回执也不能通过范围校验。</p>
     */
    @Test
    void acceptsCheckpointReplayReceiptOnlyWhenTaskStateIsQueuedAndScopeMatches() {
        AgentAutopilotRecoveryTriggerEvent event = trigger();
        AgentAutopilotRecoveryRetryReceipt accepted = new AgentAutopilotRecoveryRetryReceipt(
                31L, 42L, 2, "QUEUED", "QUEUED");
        AgentAutopilotRecoveryRetryReceipt retryingTaskState = new AgentAutopilotRecoveryRetryReceipt(
                31L, 42L, 2, "QUEUED", "RETRYING");
        AgentAutopilotRecoveryRetryReceipt wrongTask = new AgentAutopilotRecoveryRetryReceipt(
                32L, 42L, 2, "QUEUED", "QUEUED");

        assertThat(accepted.matchesRequeuedScope(event, 42L)).isTrue();
        assertThat(retryingTaskState.matchesRequeuedScope(event, 42L)).isFalse();
        assertThat(wrongTask.matchesRequeuedScope(event, 42L)).isFalse();
    }

    /**
     * 构造已通过上游认证的最小恢复事件，其中 task 和当前 execution 是回执需要重新绑定的范围事实。
     *
     * <p>该测试不验证 session、授权摘要或 Kafka 消费流程，只保留回执方法需要的 taskId 与
     * currentExecutionId。其余字段使用合法固定值，避免测试因无关前置条件而掩盖状态分支错误。</p>
     */
    private AgentAutopilotRecoveryTriggerEvent trigger() {
        return new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1",
                "event-1",
                "session-1",
                "run-1",
                11L,
                12L,
                13L,
                "user-1",
                "user-1",
                "recovery-agent",
                "delegation-1",
                31L,
                40L,
                41L,
                1,
                5,
                "2099-01-01T00:00:00Z",
                "a".repeat(64),
                0,
                null,
                List.of("OBJECT_TRANSFER_FAILED"),
                Map.of(),
                "sha256:" + "b".repeat(64),
                "2026-08-14T00:00:00Z");
    }
}
