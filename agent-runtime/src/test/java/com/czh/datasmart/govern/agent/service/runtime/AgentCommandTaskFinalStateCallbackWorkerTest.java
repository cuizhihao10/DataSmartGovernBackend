/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorkerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentCommandTaskFinalStateCallbackWorkerProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent command 最终态自动 callback worker 测试。
 *
 * <p>这组测试把无人值守收敛的两个风险固定下来：真实执行 receipt 只能投递一次；下游短暂不可用时
 * 必须按有限重试进入死信/人工补偿，而不是无限重试或丢失记录。网络调用本身由既有 dispatch service 测试覆盖，
 * 这里专注验证 durable job、租约领取、状态推进和历史记录。</p>
 */
class AgentCommandTaskFinalStateCallbackWorkerTest {

    /**
     * 同一条真实执行成功 receipt 被重复扫描时，只允许持久化一个 callback job 并向下游投递一次。
     */
    @Test
    void shouldPersistAndDispatchRealExecutionReceiptOnlyOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
        InMemoryAgentCommandTaskFinalStateCallbackJobStore jobStore =
                new InMemoryAgentCommandTaskFinalStateCallbackJobStore();
        AgentToolActionWorkerReceiptIndexService receiptIndexService = receiptIndexService(successReceipt());
        AgentCommandTaskFinalStateCallbackDispatcher dispatcher = mock(AgentCommandTaskFinalStateCallbackDispatcher.class);
        when(dispatcher.dispatch(any(), any(), any(), any())).thenReturn(dispatchedResponse(successReceipt()));
        AgentCommandTaskFinalStateCallbackWorker worker = worker(clock, jobStore, receiptIndexService, dispatcher, 3);
        jobStore.addCandidate(successReceipt());

        AgentCommandTaskFinalStateCallbackWorker.RunSummary first = worker.runOnce();
        AgentCommandTaskFinalStateCallbackWorker.RunSummary second = worker.runOnce();

        assertEquals(1, first.registered());
        assertEquals(1, first.delivered());
        assertEquals(0, second.claimed());
        assertEquals(AgentCommandTaskFinalStateCallbackJobStatus.DELIVERED,
                jobStore.findBySourceReceiptIdentityKey("receipt-success").orElseThrow().status());
        assertTrue(jobStore.historyFor("receipt-success").stream()
                .anyMatch(history -> "CALLBACK_DELIVERED".equals(history.eventType())));
        verify(dispatcher, times(1)).dispatch(any(), any(), any(), any());
    }

    /**
     * 下游不可用只能在有限次数内退避；超过上限后要留下死信和人工补偿记录。
     */
    @Test
    void shouldRetryUnavailableCallbackThenMoveItToDeadLetter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
        InMemoryAgentCommandTaskFinalStateCallbackJobStore jobStore =
                new InMemoryAgentCommandTaskFinalStateCallbackJobStore();
        AgentToolActionWorkerReceiptIndexService receiptIndexService = receiptIndexService(successReceipt());
        AgentCommandTaskFinalStateCallbackDispatcher dispatcher = mock(AgentCommandTaskFinalStateCallbackDispatcher.class);
        when(dispatcher.dispatch(any(), any(), any(), any())).thenReturn(unavailableResponse());
        AgentCommandTaskFinalStateCallbackWorker worker = worker(clock, jobStore, receiptIndexService, dispatcher, 2);
        jobStore.addCandidate(successReceipt());

        worker.runOnce();
        assertEquals(AgentCommandTaskFinalStateCallbackJobStatus.RETRY_WAIT,
                jobStore.findBySourceReceiptIdentityKey("receipt-success").orElseThrow().status());

        clock.advanceSeconds(31);
        AgentCommandTaskFinalStateCallbackWorker.RunSummary second = worker.runOnce();

        assertEquals(1, second.deadLettered());
        assertEquals(AgentCommandTaskFinalStateCallbackJobStatus.DEAD_LETTER,
                jobStore.findBySourceReceiptIdentityKey("receipt-success").orElseThrow().status());
        assertTrue(jobStore.historyFor("receipt-success").stream()
                .anyMatch(history -> "CALLBACK_DEAD_LETTERED".equals(history.eventType())));
        verify(dispatcher, times(2)).dispatch(any(), any(), any(), any());
    }

    /**
     * task-management 明确拒绝当前 run/executor 时，重复请求不会自愈，必须立即转人工补偿。
     */
    @Test
    void shouldNotRetryDeterministicDownstreamRejection() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
        InMemoryAgentCommandTaskFinalStateCallbackJobStore jobStore =
                new InMemoryAgentCommandTaskFinalStateCallbackJobStore();
        AgentToolActionWorkerReceiptIndexService receiptIndexService = receiptIndexService(successReceipt());
        AgentCommandTaskFinalStateCallbackDispatcher dispatcher = mock(AgentCommandTaskFinalStateCallbackDispatcher.class);
        when(dispatcher.dispatch(any(), any(), any(), any())).thenReturn(rejectedResponse());
        AgentCommandTaskFinalStateCallbackWorker worker = worker(clock, jobStore, receiptIndexService, dispatcher, 3);
        jobStore.addCandidate(successReceipt());

        AgentCommandTaskFinalStateCallbackWorker.RunSummary summary = worker.runOnce();

        assertEquals(0, summary.retried());
        assertEquals(1, summary.compensationRequired());
        assertEquals(AgentCommandTaskFinalStateCallbackJobStatus.COMPENSATION_REQUIRED,
                jobStore.findBySourceReceiptIdentityKey("receipt-success").orElseThrow().status());
        verify(dispatcher, times(1)).dispatch(any(), any(), any(), any());
    }

    /**
     * 进程在领取后崩溃时，租约到期的 DISPATCHING job 必须能被下一轮重新领取，而不是永久卡住。
     */
    @Test
    void shouldReclaimExpiredLeaseAfterWorkerCrash() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
        InMemoryAgentCommandTaskFinalStateCallbackJobStore jobStore =
                new InMemoryAgentCommandTaskFinalStateCallbackJobStore();
        AgentToolActionWorkerReceiptIndexRecord receipt = staleReceipt();
        AgentToolActionWorkerReceiptIndexService receiptIndexService = receiptIndexService(receipt);
        AgentCommandTaskFinalStateCallbackDispatcher dispatcher = mock(AgentCommandTaskFinalStateCallbackDispatcher.class);
        when(dispatcher.dispatch(any(), any(), any(), any())).thenReturn(dispatchedResponse(receipt));
        AgentCommandTaskFinalStateCallbackWorker worker = worker(clock, jobStore, receiptIndexService, dispatcher, 3);

        AgentCommandTaskFinalStateCallbackJob pending = AgentCommandTaskFinalStateCallbackJobFactory
                .create(receipt, clock.instant()).orElseThrow();
        jobStore.append(pending, "CALLBACK_DISCOVERED", "JAVA_TERMINAL_WORKER_RECEIPT", clock.instant());
        jobStore.claimDue("crashed-worker", "stale-token", clock.instant(), clock.instant().plusSeconds(60), 1);
        clock.advanceSeconds(61);

        // 旧 worker 的 token 即使仍然存在，也不能在租约过期后续租或写终态。
        assertFalse(jobStore.heartbeat(
                pending.jobId(), "crashed-worker", "stale-token", clock.instant().plusSeconds(60), clock.instant()));
        assertFalse(jobStore.markDelivered(
                pending.jobId(), "crashed-worker", "stale-token", false, clock.instant()));

        AgentCommandTaskFinalStateCallbackWorker.RunSummary recovered = worker.runOnce();

        assertEquals(1, recovered.claimed());
        assertEquals(1, recovered.delivered());
        verify(dispatcher, times(1)).dispatch(any(), any(), any(), any());
    }

    /**
     * 审批已自动通过或 command 已发布都不是执行成功，自动 worker 不能为它们创建 callback job。
     */
    @Test
    void shouldIgnoreAutoApprovedAndPublishedFactsWhenDiscoveringCallbackJobs() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
        InMemoryAgentCommandTaskFinalStateCallbackJobStore jobStore =
                new InMemoryAgentCommandTaskFinalStateCallbackJobStore();
        AgentToolActionWorkerReceiptIndexService receiptIndexService = receiptIndexService(autoApprovedReceipt());
        AgentCommandTaskFinalStateCallbackDispatcher dispatcher = mock(AgentCommandTaskFinalStateCallbackDispatcher.class);
        AgentCommandTaskFinalStateCallbackWorker worker = worker(clock, jobStore, receiptIndexService, dispatcher, 3);
        jobStore.addCandidate(autoApprovedReceipt());
        jobStore.addCandidate(publishedReceipt());

        AgentCommandTaskFinalStateCallbackWorker.RunSummary summary = worker.runOnce();

        assertEquals(0, summary.registered());
        assertEquals(0, summary.claimed());
        assertFalse(jobStore.findBySourceReceiptIdentityKey("receipt-auto-approved").isPresent());
        assertFalse(jobStore.findBySourceReceiptIdentityKey("receipt-published").isPresent());
        verify(dispatcher, times(0)).dispatch(any(), any(), any(), any());
    }

    /**
     * 组装受测 worker，使测试能够固定时间和低敏 callback 结果。
     */
    private AgentCommandTaskFinalStateCallbackWorker worker(MutableClock clock,
                                                            InMemoryAgentCommandTaskFinalStateCallbackJobStore jobStore,
                                                            AgentToolActionWorkerReceiptIndexService receiptIndexService,
                                                            AgentCommandTaskFinalStateCallbackDispatcher dispatcher,
                                                            int maxAttempts) {
        AgentCommandTaskFinalStateCallbackWorkerProperties properties =
                new AgentCommandTaskFinalStateCallbackWorkerProperties();
        properties.setBatchSize(10);
        properties.setMaxAttempts(maxAttempts);
        properties.setInitialBackoffSeconds(30);
        properties.setMaxBackoffSeconds(300);
        properties.setVisibilityTimeoutSeconds(60);
        properties.setWorkerId("test-final-state-callback-worker");
        return new AgentCommandTaskFinalStateCallbackWorker(
                properties,
                jobStore,
                new AgentCommandTaskFinalStateReconciliationService(
                        receiptIndexService,
                        new AgentRuntimeEventProjectionAccessSupport()),
                dispatcher,
                clock,
                new SimpleMeterRegistry()
        );
    }

    /**
     * 将低敏 worker receipt 放入真实索引服务，确保 worker 的候选再次经过 Java reconciliation。
     */
    private AgentToolActionWorkerReceiptIndexService receiptIndexService(
            AgentToolActionWorkerReceiptIndexRecord receipt) {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt);
        return new AgentToolActionWorkerReceiptIndexService(store);
    }

    /**
     * 构造已由 Java receipt 证实副作用成功的低敏终态事实。
     */
    private AgentToolActionWorkerReceiptIndexRecord successReceipt() {
        return new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-success",
                "cmd-final-worker-001",
                9001L,
                9101L,
                "worker-final-001",
                "audit-final-001",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                "command.run-program",
                "SUCCEEDED",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED",
                12L,
                Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-19T00:00:00Z")
        );
    }

    /** 构造另一条真实终态 receipt，避免与前面幂等测试使用同一个 source identity。 */
    private AgentToolActionWorkerReceiptIndexRecord staleReceipt() {
        return new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-stale-lease",
                "cmd-stale-lease",
                9004L,
                9104L,
                "worker-stale-lease",
                "audit-stale-lease",
                "10",
                "20",
                "1001",
                "run-stale-lease",
                "session-stale-lease",
                "command.run-program",
                "SUCCEEDED",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED",
                15L,
                Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-19T00:00:00Z")
        );
    }

    /**
     * 构造仅代表审批自动通过、未进入真实副作用区的 receipt。
     */
    private AgentToolActionWorkerReceiptIndexRecord autoApprovedReceipt() {
        return new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-auto-approved",
                "cmd-final-worker-auto-approved",
                9002L,
                9102L,
                "worker-final-002",
                "audit-final-002",
                "10",
                "20",
                "1001",
                "run-final-auto-approved",
                "session-final-auto-approved",
                "command.run-program",
                "RUNNING",
                "AUTO_APPROVED",
                true,
                false,
                "AGENT_COMMAND_AUTO_APPROVED",
                13L,
                Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-19T00:00:00Z")
        );
    }

    /**
     * 构造仅代表 command outbox 已发布、未收到执行回执的 receipt。
     */
    private AgentToolActionWorkerReceiptIndexRecord publishedReceipt() {
        return new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-published",
                "cmd-final-worker-published",
                9003L,
                9103L,
                "worker-final-003",
                "audit-final-003",
                "10",
                "20",
                "1001",
                "run-final-published",
                "session-final-published",
                "command.run-program",
                "RUNNING",
                "PUBLISHED",
                true,
                false,
                "AGENT_COMMAND_OUTBOX_PUBLISHED",
                14L,
                Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-19T00:00:00Z")
        );
    }

    /**
     * 构造 task-management 已接受回调的低敏响应。
     */
    private AgentCommandTaskFinalStateCallbackDispatchResponse dispatchedResponse(
            AgentToolActionWorkerReceiptIndexRecord receipt) {
        return new AgentCommandTaskFinalStateCallbackDispatchResponse(
                "LOW_SENSITIVE_TEST",
                receipt.commandId(),
                false,
                true,
                true,
                "DISPATCHED",
                receipt.taskId(),
                receipt.taskRunId(),
                receipt.executorId(),
                "SUCCEEDED",
                "TASK_COMPLETE",
                AgentCommandTaskFinalStateCallbackJobFactory.callbackIdempotencyKey(
                        receipt.commandId(), "SUCCEEDED", receipt.replaySequence()),
                true,
                "accepted",
                null,
                List.of(),
                List.of()
        );
    }

    /**
     * 构造下游暂时不可用的低敏响应，worker 应把它当作可退避的可恢复失败。
     */
    private AgentCommandTaskFinalStateCallbackDispatchResponse unavailableResponse() {
        return new AgentCommandTaskFinalStateCallbackDispatchResponse(
                "LOW_SENSITIVE_TEST",
                "cmd-final-worker-001",
                false,
                true,
                false,
                "FAILED_DOWNSTREAM_UNAVAILABLE",
                9001L,
                9101L,
                "worker-final-001",
                "SUCCEEDED",
                "TASK_COMPLETE",
                "agent-command-final-state:CMD-FINAL-WORKER-001:SUCCEEDED:12",
                false,
                "unavailable",
                null,
                List.of(),
                List.of()
        );
    }

    /** 构造确定性的业务拒绝；worker 不应把它误当成网络暂态故障反复调用。 */
    private AgentCommandTaskFinalStateCallbackDispatchResponse rejectedResponse() {
        return new AgentCommandTaskFinalStateCallbackDispatchResponse(
                "LOW_SENSITIVE_TEST",
                "cmd-final-worker-001",
                false,
                true,
                false,
                "FAILED_DOWNSTREAM_REJECTED",
                9001L,
                9101L,
                "worker-final-001",
                "SUCCEEDED",
                "TASK_COMPLETE",
                "agent-command-final-state:CMD-FINAL-WORKER-001:SUCCEEDED:12",
                false,
                "rejected",
                null,
                List.of(),
                List.of()
        );
    }

    /**
     * 供重试测试推进时间的可变时钟，避免真实 sleep 使单元测试变慢或不稳定。
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        /** 返回测试当前的 UTC 时刻。 */
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        /** 测试不需要改变时区，因此返回当前时钟。 */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 返回 worker 读取到的当前时刻。 */
        @Override
        public Instant instant() {
            return now;
        }

        /** 将测试时间向前推进指定秒数。 */
        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
