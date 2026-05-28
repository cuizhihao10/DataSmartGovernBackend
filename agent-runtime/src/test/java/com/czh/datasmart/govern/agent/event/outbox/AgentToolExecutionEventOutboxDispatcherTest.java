/**
 * @Author : Cui
 * @Date: 2026/05/28 20:10
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxDispatcherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具事件 outbox dispatcher 测试。
 *
 * <p>这组测试保护 4.20 的最小投递闭环。dispatcher 的风险点不是“能不能循环列表”，而是状态是否正确推进：
 * PENDING/FAILED 记录必须先被领取为 PUBLISHING；所有目标成功后才能标记 PUBLISHED；
 * 任一目标失败或未配置目标时必须写回 FAILED 和 nextRetryAt，避免事件凭据被误吞。</p>
 */
class AgentToolExecutionEventOutboxDispatcherTest {

    @Test
    void dispatcherShouldPublishPendingRecordAndMarkPublished() {
        AgentToolExecutionEventOutboxProperties properties = properties();
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        AgentToolExecutionEventOutboxRecord record = outboxRecord("outbox-dispatch-success", AgentToolExecutionEventOutboxStatus.PENDING, 0, null);
        store.append(record);
        CollectingDispatchTarget target = new CollectingDispatchTarget();
        AgentToolExecutionEventOutboxDispatcher dispatcher = new AgentToolExecutionEventOutboxDispatcher(
                properties,
                store,
                List.of(target)
        );

        AgentToolExecutionEventOutboxDispatcher.AgentToolExecutionEventOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.scanned());
        assertEquals(1, summary.published());
        assertEquals(List.of("outbox-dispatch-success"), target.dispatchedOutboxIds);
        AgentToolExecutionEventOutboxRecord published = store.findByOutboxId("outbox-dispatch-success").orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.PUBLISHED, published.status());
        assertNotNull(published.publishedAt());
    }

    @Test
    void dispatcherShouldMarkFailedWhenTargetFails() {
        AgentToolExecutionEventOutboxProperties properties = properties();
        properties.setRetryBackoffSeconds(5);
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(outboxRecord("outbox-dispatch-failed", AgentToolExecutionEventOutboxStatus.PENDING, 0, null));
        AgentToolExecutionEventOutboxDispatchTarget failingTarget = new AgentToolExecutionEventOutboxDispatchTarget() {
            @Override
            public String targetName() {
                return "failing-target";
            }

            @Override
            public void dispatch(AgentToolExecutionEventOutboxRecord record) {
                throw new IllegalStateException("模拟 Kafka broker 暂时不可用");
            }
        };
        AgentToolExecutionEventOutboxDispatcher dispatcher = new AgentToolExecutionEventOutboxDispatcher(
                properties,
                store,
                List.of(failingTarget)
        );

        AgentToolExecutionEventOutboxDispatcher.AgentToolExecutionEventOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.failed());
        AgentToolExecutionEventOutboxRecord failed = store.findByOutboxId("outbox-dispatch-failed").orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.FAILED, failed.status());
        assertEquals(1, failed.attemptCount());
        assertNotNull(failed.nextRetryAt());
        assertTrue(failed.lastError().contains("模拟 Kafka broker 暂时不可用"));
    }

    @Test
    void dispatcherShouldNotMarkPublishedWhenNoTargetsConfigured() {
        AgentToolExecutionEventOutboxProperties properties = properties();
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(outboxRecord("outbox-dispatch-no-target", AgentToolExecutionEventOutboxStatus.PENDING, 0, null));
        AgentToolExecutionEventOutboxDispatcher dispatcher = new AgentToolExecutionEventOutboxDispatcher(
                properties,
                store,
                List.of()
        );

        AgentToolExecutionEventOutboxDispatcher.AgentToolExecutionEventOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.failed());
        AgentToolExecutionEventOutboxRecord failed = store.findByOutboxId("outbox-dispatch-no-target").orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.FAILED, failed.status());
        assertTrue(failed.lastError().contains("未配置任何投递目标"));
    }

    @Test
    void dispatcherShouldIgnoreFailedRecordBeforeRetryTime() {
        AgentToolExecutionEventOutboxProperties properties = properties();
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(outboxRecord(
                "outbox-dispatch-wait-retry",
                AgentToolExecutionEventOutboxStatus.FAILED,
                1,
                Instant.now().plusSeconds(60)
        ));
        CollectingDispatchTarget target = new CollectingDispatchTarget();
        AgentToolExecutionEventOutboxDispatcher dispatcher = new AgentToolExecutionEventOutboxDispatcher(
                properties,
                store,
                List.of(target)
        );

        AgentToolExecutionEventOutboxDispatcher.AgentToolExecutionEventOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(0, summary.scanned());
        assertTrue(target.dispatchedOutboxIds.isEmpty());
    }

    @Test
    void dispatcherShouldSkipRecordAlreadyClaimedByAnotherWorker() {
        AgentToolExecutionEventOutboxProperties properties = properties();
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(outboxRecord("outbox-dispatch-concurrent-claim", AgentToolExecutionEventOutboxStatus.PENDING, 0, null));

        /*
         * 这里先手动把记录领取为 PUBLISHING，用来模拟“另一个应用实例已经抢先领取同一条 outbox 记录”的生产场景。
         * 4.21 之前内存 store 和 JDBC store 的 markPublishing 都是无条件更新，第二个 worker 仍可能把同一条记录再次领取；
         * 现在 markPublishing 会要求记录仍处于 PENDING/FAILED 且 retry 时间已到，因此 dispatcher 只能跳过这条记录。
         */
        assertTrue(store.markPublishing("outbox-dispatch-concurrent-claim", Instant.now()).isPresent());
        AgentToolExecutionEventOutboxDispatcher dispatcher = new AgentToolExecutionEventOutboxDispatcher(
                properties,
                store,
                List.of(new CollectingDispatchTarget())
        );

        AgentToolExecutionEventOutboxDispatcher.AgentToolExecutionEventOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(0, summary.scanned());
        AgentToolExecutionEventOutboxRecord current = store.findByOutboxId("outbox-dispatch-concurrent-claim").orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.PUBLISHING, current.status());
        assertEquals(1, current.attemptCount());
    }

    @Test
    void dispatcherShouldRecoverStalePublishingRecordAndRetry() {
        AgentToolExecutionEventOutboxProperties properties = properties();
        properties.setDispatcherPublishingTimeoutSeconds(60);
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(outboxRecord(
                "outbox-dispatch-stale-publishing",
                AgentToolExecutionEventOutboxStatus.PUBLISHING,
                1,
                null,
                Instant.now().minusSeconds(120)
        ));
        CollectingDispatchTarget target = new CollectingDispatchTarget();
        AgentToolExecutionEventOutboxDispatcher dispatcher = new AgentToolExecutionEventOutboxDispatcher(
                properties,
                store,
                List.of(target)
        );

        AgentToolExecutionEventOutboxDispatcher.AgentToolExecutionEventOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.recovered());
        assertEquals(1, summary.scanned());
        assertEquals(1, summary.published());
        assertEquals(List.of("outbox-dispatch-stale-publishing"), target.dispatchedOutboxIds);
        AgentToolExecutionEventOutboxRecord current = store.findByOutboxId("outbox-dispatch-stale-publishing").orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.PUBLISHED, current.status());
        assertEquals(2, current.attemptCount());
    }

    @Test
    void dispatcherShouldBlockRecordWhenMaxAttemptsExceeded() {
        AgentToolExecutionEventOutboxProperties properties = properties();
        properties.setDispatcherMaxAttempts(3);
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(outboxRecord("outbox-dispatch-blocked", AgentToolExecutionEventOutboxStatus.FAILED, 3, null));
        AgentToolExecutionEventOutboxDispatcher dispatcher = new AgentToolExecutionEventOutboxDispatcher(
                properties,
                store,
                List.of(new CollectingDispatchTarget())
        );

        AgentToolExecutionEventOutboxDispatcher.AgentToolExecutionEventOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.scanned());
        assertEquals(1, summary.blocked());
        AgentToolExecutionEventOutboxRecord blocked = store.findByOutboxId("outbox-dispatch-blocked").orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.BLOCKED, blocked.status());
        assertEquals(4, blocked.attemptCount());
        assertTrue(blocked.lastError().contains("最大投递尝试次数"));
    }

    private AgentToolExecutionEventOutboxProperties properties() {
        AgentToolExecutionEventOutboxProperties properties = new AgentToolExecutionEventOutboxProperties();
        properties.setDispatcherBatchSize(10);
        properties.setDispatcherMaxAttempts(3);
        properties.setDispatcherMaxRetryBackoffSeconds(60);
        return properties;
    }

    private AgentToolExecutionEventOutboxRecord outboxRecord(String outboxId,
                                                             AgentToolExecutionEventOutboxStatus status,
                                                             int attemptCount,
                                                             Instant nextRetryAt) {
        return outboxRecord(outboxId, status, attemptCount, nextRetryAt, Instant.now());
    }

    private AgentToolExecutionEventOutboxRecord outboxRecord(String outboxId,
                                                             AgentToolExecutionEventOutboxStatus status,
                                                             int attemptCount,
                                                             Instant nextRetryAt,
                                                             Instant updatedAt) {
        Instant now = Instant.now();
        return new AgentToolExecutionEventOutboxRecord(
                outboxId,
                "event-" + outboxId,
                "agent.tool_execution.state_changed",
                "agent-tool-execution-event.v1",
                "agent-runtime",
                "run-dispatch-001",
                "10",
                "20",
                "30",
                "actor-dispatch",
                "session-dispatch-001",
                "run-dispatch-001",
                "audit-dispatch-001",
                "task.draft.persist",
                "SUCCEEDED",
                status,
                attemptCount,
                now,
                now,
                updatedAt,
                nextRetryAt,
                null,
                "",
                64,
                false,
                "{\"eventType\":\"agent.tool_execution.state_changed\"}"
        );
    }

    private static class CollectingDispatchTarget implements AgentToolExecutionEventOutboxDispatchTarget {

        private final List<String> dispatchedOutboxIds = new ArrayList<>();

        @Override
        public String targetName() {
            return "collecting-target";
        }

        @Override
        public void dispatch(AgentToolExecutionEventOutboxRecord record) {
            dispatchedOutboxIds.add(record.outboxId());
        }
    }
}
