/**
 * @Author : Cui
 * @Date: 2026/06/24 02:20
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * command worker lease 服务测试。
 *
 * <p>这组测试把“副作用执行资格”当成独立业务能力验证，而不是只依赖 receipt 服务间接覆盖。
 * 对真实 Agent Host 来说，工具调用、命令执行、artifact 写入这类副作用都可能被队列重试、worker 暂停、
 * 多实例竞争或网络抖动放大；lease + fencing token 的价值，就是让旧 worker 不能在失去资格后继续写事实。</p>
 */
class AgentCommandWorkerLeaseServiceTest {

    private static final String SESSION_ID = "session-command";
    private static final String RUN_ID = "run-command";
    private static final String COMMAND_ID = "cmd-worker-001";
    private static final String EXECUTOR_ID = "agent-command-worker";

    @Test
    void claimShouldReturnFencingTokenAndValidateMatchingReceipt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T01:00:00Z"));
        AgentCommandWorkerLeaseService service =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);

        AgentCommandWorkerLeaseClaimResult result = service.claim(SESSION_ID, RUN_ID,
                claimRequest(EXECUTOR_ID, 120));

        assertTrue(result.acquired());
        assertEquals(AgentCommandWorkerLeaseState.ACQUIRED, result.state());
        assertTrue(result.tokenVisible());
        assertNotNull(result.record().fencingToken());
        assertEquals(1L, result.record().leaseVersion());
        assertEquals(clock.instant().plusSeconds(120), result.record().leaseExpiresAt());

        service.validateReceiptLease(SESSION_ID, RUN_ID, receipt(result.record(), EXECUTOR_ID), true);
        assertTrue(service.tokenDigest(result.record().fencingToken()).startsWith("sha256:"));
    }

    @Test
    void sameExecutorClaimShouldBeIdempotentAndKeepCurrentToken() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T01:00:00Z"));
        AgentCommandWorkerLeaseService service =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);

        AgentCommandWorkerLeaseRecord first = service.claim(SESSION_ID, RUN_ID,
                claimRequest(EXECUTOR_ID, 120)).record();
        AgentCommandWorkerLeaseClaimResult retry = service.claim(SESSION_ID, RUN_ID,
                claimRequest(EXECUTOR_ID, 120));

        assertTrue(retry.acquired());
        assertEquals(AgentCommandWorkerLeaseState.ALREADY_HELD_BY_CALLER, retry.state());
        assertTrue(retry.tokenVisible());
        assertEquals(first.fencingToken(), retry.record().fencingToken());
        assertEquals(first.leaseVersion(), retry.record().leaseVersion());
    }

    @Test
    void differentExecutorShouldBeBlockedAndTokenShouldNotBeVisible() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T01:00:00Z"));
        AgentCommandWorkerLeaseService service =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);

        AgentCommandWorkerLeaseRecord first = service.claim(SESSION_ID, RUN_ID,
                claimRequest(EXECUTOR_ID, 120)).record();
        AgentCommandWorkerLeaseClaimResult blocked = service.claim(SESSION_ID, RUN_ID,
                claimRequest("agent-command-worker-other", 120));

        assertFalse(blocked.acquired());
        assertEquals(AgentCommandWorkerLeaseState.ALREADY_HELD_BY_OTHER, blocked.state());
        assertFalse(blocked.tokenVisible());
        assertEquals(first.fencingToken(), blocked.record().fencingToken());
    }

    @Test
    void expiredLeaseShouldIncrementVersionAndRejectOldReceipt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T01:00:00Z"));
        AgentCommandWorkerLeaseService service =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);

        AgentCommandWorkerLeaseRecord oldLease = service.claim(SESSION_ID, RUN_ID,
                claimRequest(EXECUTOR_ID, 30)).record();
        clock.advanceSeconds(31);
        AgentCommandWorkerLeaseRecord newLease = service.claim(SESSION_ID, RUN_ID,
                claimRequest("agent-command-worker-new", 60)).record();

        assertEquals(2L, newLease.leaseVersion());
        assertNotEquals(oldLease.fencingToken(), newLease.fencingToken());
        assertThrows(PlatformBusinessException.class,
                () -> service.validateReceiptLease(SESSION_ID, RUN_ID, receipt(oldLease, EXECUTOR_ID), true));
        service.validateReceiptLease(SESSION_ID, RUN_ID,
                receipt(newLease, "agent-command-worker-new"), true);
    }

    @Test
    void receiptShouldRejectTokenThatWasNeverClaimedFromStore() {
        AgentCommandWorkerLeaseService service =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore());
        AgentToolActionCommandWorkerReceiptRequest forgedRequest = new AgentToolActionCommandWorkerReceiptRequest(
                COMMAND_ID,
                9101L,
                9201L,
                EXECUTOR_ID,
                10L,
                20L,
                30L,
                "SUCCEEDED",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                true,
                "cmd-lease:1:abcdef1234567890",
                1L,
                1900000000000L,
                "ALLOW_CONTROLLED_EXECUTION",
                "command-safety-policy.v1",
                List.of(),
                30,
                4096,
                "MINIO_OBJECT",
                "agent-artifact:run-command/receipt-001",
                true,
                null,
                "audit-command-worker-001",
                "command.run-program",
                "task-management-worker",
                "EXECUTION_RESULT",
                "受控命令 worker 已写回低敏执行事实。",
                List.of("确认任务中心状态与 artifact 元数据已经对账"),
                "command-worker:cmd-worker-001:forged-token"
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.validateReceiptLease(SESSION_ID, RUN_ID, forgedRequest, true));
    }

    private AgentCommandWorkerLeaseClaimRequest claimRequest(String executorId, int ttlSeconds) {
        return new AgentCommandWorkerLeaseClaimRequest(COMMAND_ID, executorId, 10L, 20L, 30L, ttlSeconds);
    }

    private AgentToolActionCommandWorkerReceiptRequest receipt(AgentCommandWorkerLeaseRecord lease, String executorId) {
        return new AgentToolActionCommandWorkerReceiptRequest(
                COMMAND_ID,
                9101L,
                9201L,
                executorId,
                10L,
                20L,
                30L,
                "SUCCEEDED",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                true,
                lease.fencingToken(),
                lease.leaseVersion(),
                lease.leaseExpiresAt().toEpochMilli(),
                "ALLOW_CONTROLLED_EXECUTION",
                "command-safety-policy.v1",
                List.of(),
                30,
                4096,
                "MINIO_OBJECT",
                "agent-artifact:run-command/receipt-001",
                true,
                null,
                "audit-command-worker-001",
                "command.run-program",
                "task-management-worker",
                "EXECUTION_RESULT",
                "受控命令 worker 已写回低敏执行事实。",
                List.of("确认任务中心状态与 artifact 元数据已经对账"),
                "command-worker:cmd-worker-001:" + lease.leaseVersion()
        );
    }

    /**
     * 可推进时间的测试 Clock。
     *
     * <p>lease 过期逻辑不能依赖 Thread.sleep，否则测试会变慢且不稳定。这里用可变 Clock 精确推进时间，
     * 让“旧 worker 过期后被新 worker 抢占”的状态流转完全可重复。</p>
     */
    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }
    }
}
