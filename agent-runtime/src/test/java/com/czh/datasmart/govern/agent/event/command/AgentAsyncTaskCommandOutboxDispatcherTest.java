/**
 * @Author : Cui
 * @Date: 2026/05/31 17:28
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxDispatcherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 异步命令 outbox dispatcher 测试。
 *
 * <p>该测试保护 command 投递状态机：成功必须标记 PUBLISHED；目标失败必须标记 FAILED 并设置重试时间；
 * 没有目标时不能误标成功；卡在 PUBLISHING 的记录可以被恢复后重新投递。</p>
 */
class AgentAsyncTaskCommandOutboxDispatcherTest {

    @Test
    void dispatcherShouldPublishPendingCommandAndMarkPublished() {
        AgentAsyncTaskCommandOutboxProperties properties = properties();
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("async-command-success", AgentAsyncTaskCommandOutboxStatus.PENDING, 0, null, Instant.now()));
        CollectingTarget target = new CollectingTarget();
        AgentAsyncTaskCommandOutboxDispatcher dispatcher = new AgentAsyncTaskCommandOutboxDispatcher(
                properties,
                store,
                List.of(target)
        );

        AgentAsyncTaskCommandOutboxDispatcher.AgentAsyncTaskCommandOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.scanned());
        assertEquals(1, summary.published());
        assertEquals(List.of("async-command-success"), target.commandIds);
        AgentAsyncTaskCommandOutboxRecord published =
                store.findByCommandId("async-command-success").orElseThrow();
        assertEquals(AgentAsyncTaskCommandOutboxStatus.PUBLISHED, published.status());
        assertNotNull(published.publishedAt());
    }

    @Test
    void dispatcherShouldMarkFailedWhenTargetThrows() {
        AgentAsyncTaskCommandOutboxProperties properties = properties();
        properties.setRetryBackoffSeconds(5);
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("async-command-fail", AgentAsyncTaskCommandOutboxStatus.PENDING, 0, null, Instant.now()));
        AgentAsyncTaskCommandDispatchTarget failingTarget = new AgentAsyncTaskCommandDispatchTarget() {
            @Override
            public String targetName() {
                return "failing-target";
            }

            @Override
            public void dispatch(AgentAsyncTaskCommandOutboxRecord record) {
                throw new IllegalStateException("模拟 task-management 暂时不可用");
            }
        };
        AgentAsyncTaskCommandOutboxDispatcher dispatcher = new AgentAsyncTaskCommandOutboxDispatcher(
                properties,
                store,
                List.of(failingTarget)
        );

        AgentAsyncTaskCommandOutboxDispatcher.AgentAsyncTaskCommandOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.failed());
        AgentAsyncTaskCommandOutboxRecord failed = store.findByCommandId("async-command-fail").orElseThrow();
        assertEquals(AgentAsyncTaskCommandOutboxStatus.FAILED, failed.status());
        assertEquals(1, failed.attemptCount());
        assertNotNull(failed.nextRetryAt());
        assertTrue(failed.lastError().contains("模拟 task-management 暂时不可用"));
    }

    @Test
    void dispatcherShouldNotMarkPublishedWithoutTarget() {
        AgentAsyncTaskCommandOutboxProperties properties = properties();
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("async-command-no-target", AgentAsyncTaskCommandOutboxStatus.PENDING, 0, null, Instant.now()));
        AgentAsyncTaskCommandOutboxDispatcher dispatcher = new AgentAsyncTaskCommandOutboxDispatcher(
                properties,
                store,
                List.of()
        );

        AgentAsyncTaskCommandOutboxDispatcher.AgentAsyncTaskCommandOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.failed());
        assertTrue(store.findByCommandId("async-command-no-target").orElseThrow()
                .lastError()
                .contains("未配置异步命令投递目标"));
    }

    @Test
    void dispatcherShouldRecoverStalePublishingAndRetry() {
        AgentAsyncTaskCommandOutboxProperties properties = properties();
        properties.setDispatcherPublishingTimeoutSeconds(60);
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record(
                "async-command-stale",
                AgentAsyncTaskCommandOutboxStatus.PUBLISHING,
                1,
                null,
                Instant.now().minusSeconds(120)
        ));
        CollectingTarget target = new CollectingTarget();
        AgentAsyncTaskCommandOutboxDispatcher dispatcher = new AgentAsyncTaskCommandOutboxDispatcher(
                properties,
                store,
                List.of(target)
        );

        AgentAsyncTaskCommandOutboxDispatcher.AgentAsyncTaskCommandOutboxDispatchSummary summary =
                dispatcher.dispatchOnce();

        assertEquals(1, summary.recovered());
        assertEquals(1, summary.published());
        AgentAsyncTaskCommandOutboxRecord current = store.findByCommandId("async-command-stale").orElseThrow();
        assertEquals(AgentAsyncTaskCommandOutboxStatus.PUBLISHED, current.status());
        assertEquals(2, current.attemptCount());
    }

    private AgentAsyncTaskCommandOutboxProperties properties() {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        properties.setDispatcherBatchSize(10);
        properties.setDispatcherMaxAttempts(3);
        properties.setDispatcherMaxRetryBackoffSeconds(60);
        return properties;
    }

    private AgentAsyncTaskCommandOutboxRecord record(String commandId,
                                                     AgentAsyncTaskCommandOutboxStatus status,
                                                     int attemptCount,
                                                     Instant nextRetryAt,
                                                     Instant updatedAt) {
        Instant now = Instant.now();
        return new AgentAsyncTaskCommandOutboxRecord(
                "async-command-outbox:" + commandId,
                commandId,
                "agent-tool-async:session:run:" + commandId,
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ASYNC_TASK_REQUESTED",
                "run-dispatch-command",
                "datasmart.agent.tool.async.commands",
                "task-management",
                "session-dispatch-command",
                "run-dispatch-command",
                "audit-dispatch-command",
                "data-sync.execute",
                "data-sync",
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-command",
                "trace-command",
                "agent-tool-audit://session/run/audit/plan-arguments",
                status,
                attemptCount,
                now,
                updatedAt,
                nextRetryAt,
                null,
                "",
                256,
                false,
                "{\"schemaVersion\":\"datasmart.agent.async-task-command.v1\"}"
        );
    }

    private static class CollectingTarget implements AgentAsyncTaskCommandDispatchTarget {

        private final List<String> commandIds = new ArrayList<>();

        @Override
        public String targetName() {
            return "collecting-target";
        }

        @Override
        public void dispatch(AgentAsyncTaskCommandOutboxRecord record) {
            commandIds.add(record.commandId());
        }
    }
}
