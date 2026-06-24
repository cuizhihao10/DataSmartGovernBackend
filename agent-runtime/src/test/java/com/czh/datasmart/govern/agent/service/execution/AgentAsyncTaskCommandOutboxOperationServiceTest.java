/**
 * @Author : Cui
 * @Date: 2026/06/24 23:50
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxOperationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxOperationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxOperationResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.InMemoryAgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 异步命令 outbox 人工补偿服务测试。
 *
 * <p>这组测试保护的是商业化运行中非常重要的“失败出口”：
 * 如果命令投递失败或被阻断，平台不能只有自动重试，也必须支持死信、重排、忽略和备注。
 * 同时，补偿入口不能绕过状态机，不能重放已经成功的命令，不能把敏感 reason 写入 outbox 最近摘要。</p>
 */
class AgentAsyncTaskCommandOutboxOperationServiceTest {

    @Test
    void deadLetterShouldStopAutomaticDispatchForFailedCommand() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T15:40:00Z"));
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("command-dead-letter", AgentAsyncTaskCommandOutboxStatus.FAILED, clock.instant()));
        AgentAsyncTaskCommandOutboxOperationService service = service(store, clock);

        AgentAsyncTaskCommandOutboxOperationResponse response = service.deadLetter(
                "async-command-outbox:command-dead-letter",
                request("下游 task-management topic ACL 尚未修复，先转入死信等待运维处理", "operator-001", null),
                null
        );

        assertEquals("DEAD_LETTER", response.action());
        assertEquals(AgentAsyncTaskCommandOutboxStatus.FAILED.name(), response.previousStatus());
        assertEquals(AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER.name(), response.currentStatus());
        assertEquals("operator-001", response.operatorId());
        assertNull(response.nextRetryAt());
        AgentAsyncTaskCommandOutboxRecord current =
                store.findByCommandId("command-dead-letter").orElseThrow();
        assertEquals(AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER, current.status());
        assertTrue(current.lastError().contains("人工补偿动作=DEAD_LETTER"));
        assertTrue(store.listPublishable(10, clock.instant()).isEmpty());
        assertEquals(1, store.diagnostics().deadLetterRecords());
    }

    @Test
    void requeueShouldMoveDeadLetterBackToPendingWithOptionalDelay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T15:45:00Z"));
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("command-requeue", AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER, clock.instant()));
        AgentAsyncTaskCommandOutboxOperationService service = service(store, clock);

        AgentAsyncTaskCommandOutboxOperationResponse response = service.requeue(
                "async-command-outbox:command-requeue",
                request("下游 ACL 已修复，延迟 30 秒后恢复自动投递", "operator-002", 30),
                null
        );

        Instant expectedRetryAt = clock.instant().plusSeconds(30);
        assertEquals("REQUEUE", response.action());
        assertEquals(AgentAsyncTaskCommandOutboxStatus.PENDING.name(), response.currentStatus());
        assertEquals(expectedRetryAt, response.nextRetryAt());
        AgentAsyncTaskCommandOutboxRecord current =
                store.findByCommandId("command-requeue").orElseThrow();
        assertEquals(AgentAsyncTaskCommandOutboxStatus.PENDING, current.status());
        assertEquals(expectedRetryAt, current.nextRetryAt());
        assertTrue(store.listPublishable(10, clock.instant()).isEmpty());
        clock.advanceSeconds(31);
        assertEquals(1, store.listPublishable(10, clock.instant()).size());
    }

    @Test
    void ignoreShouldArchiveDeadLetterWithoutMarkingPublished() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T15:50:00Z"));
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("command-ignore", AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER, clock.instant()));
        AgentAsyncTaskCommandOutboxOperationService service = service(store, clock);

        AgentAsyncTaskCommandOutboxOperationResponse response = service.ignore(
                "async-command-outbox:command-ignore",
                request("客户确认该历史命令无需补发，人工忽略归档", "operator-003", null),
                null
        );

        assertEquals("IGNORE", response.action());
        assertEquals(AgentAsyncTaskCommandOutboxStatus.IGNORED.name(), response.currentStatus());
        AgentAsyncTaskCommandOutboxRecord current =
                store.findByCommandId("command-ignore").orElseThrow();
        assertEquals(AgentAsyncTaskCommandOutboxStatus.IGNORED, current.status());
        assertNull(current.publishedAt());
        assertFalse(store.listPublishable(10, clock.instant()).contains(current));
    }

    @Test
    void operationShouldRejectPublishedCommand() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T15:55:00Z"));
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("command-published", AgentAsyncTaskCommandOutboxStatus.PUBLISHED, clock.instant()));
        AgentAsyncTaskCommandOutboxOperationService service = service(store, clock);

        assertThrows(PlatformBusinessException.class,
                () -> service.requeue(
                        "async-command-outbox:command-published",
                        request("已成功命令不允许补偿重排", "operator-004", null),
                        null
                ));
        assertThrows(PlatformBusinessException.class,
                () -> service.appendNote(
                        "async-command-outbox:command-published",
                        request("已成功命令不允许追加补偿备注", "operator-004", null),
                        null
                ));
    }

    @Test
    void operationShouldRejectSensitiveReason() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T16:00:00Z"));
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        store.append(record("command-sensitive", AgentAsyncTaskCommandOutboxStatus.FAILED, clock.instant()));
        AgentAsyncTaskCommandOutboxOperationService service = service(store, clock);

        assertThrows(PlatformBusinessException.class,
                () -> service.deadLetter(
                        "async-command-outbox:command-sensitive",
                        request("失败原因包含 select * from user，不应进入 outbox 摘要", "operator-005", null),
                        null
                ));
    }

    private AgentAsyncTaskCommandOutboxOperationService service(InMemoryAgentAsyncTaskCommandOutboxStore store,
                                                                Clock clock) {
        return new AgentAsyncTaskCommandOutboxOperationService(store, store, clock);
    }

    private AgentAsyncTaskCommandOutboxOperationRequest request(String reason,
                                                                String operatorId,
                                                                Integer retryDelaySeconds) {
        return new AgentAsyncTaskCommandOutboxOperationRequest(reason, operatorId, retryDelaySeconds);
    }

    private AgentAsyncTaskCommandOutboxRecord record(String commandId,
                                                     AgentAsyncTaskCommandOutboxStatus status,
                                                     Instant now) {
        return new AgentAsyncTaskCommandOutboxRecord(
                "async-command-outbox:" + commandId,
                commandId,
                "agent-tool-async:session:run:" + commandId,
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
                "run-command-operation",
                "datasmart.agent.tool.async.commands",
                "task-management",
                "session-command-operation",
                "run-command-operation",
                "audit-command-operation",
                "command.run-program",
                "agent-runtime",
                null,
                10L,
                20L,
                30L,
                "actor-command",
                "trace-command",
                "agent-tool-audit://session-command-operation/run-command-operation/audit-command-operation/plan-arguments",
                status,
                3,
                now,
                now,
                now,
                null,
                "原始失败原因已被低敏摘要替代",
                256,
                false,
                "{\"schemaVersion\":\"datasmart.agent.async-task-command.v1\",\"argumentNames\":[\"safeArg\"]}"
        );
    }

    /**
     * 可控测试时钟。
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
