/**
 * @Author : Cui
 * @Date: 2026/06/01 00:08
 * @Description DataSmart Govern Backend - AgentRuntimeEventReplayServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeEventConsumerProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventReplayAckRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * runtime event replay 与 ack cursor 服务测试。
 *
 * <p>这些测试保护 WebSocket/replay 的底层语义，而不是测试真正的长连接：
 * 1. replaySequence 必须可以作为 Java 控制面 source cursor；
 * 2. 客户端 ack 后，断线重连可以从 ack 之后继续读取；
 * 3. 旧 ack 不允许让 cursor 回退；
 * 4. replay 必须绑定 run/session 范围，避免无边界拉取事件热窗口。</p>
 */
class AgentRuntimeEventReplayServiceTest {

    @Test
    void replayShouldUseSavedAckCursorWhenAfterSequenceIsMissing() {
        TestFixture fixture = fixtureWithRunEvents();
        fixture.replayService.acknowledge(
                new AgentRuntimeEventReplayAckRequest("web-tab-1", "run-replay", null, 1L, Instant.now()),
                projectOwnerContext()
        );

        var response = fixture.replayService.replay(
                new AgentRuntimeEventProjectionQuery(null, null, null, null,
                        "run-replay", null, null, null, 10),
                projectOwnerContext(),
                "web-tab-1"
        );

        assertEquals(1L, response.effectiveAfterSequence());
        assertEquals(2, response.totalMatched());
        assertEquals(2L, response.events().getFirst().replaySequence());
        assertEquals("CURSOR_FOUND", response.cursor().reason());
        assertEquals(1L, response.cursor().acknowledgedReplaySequence());
    }

    @Test
    void ackShouldAdvanceCursorAndIgnoreOlderSequence() {
        TestFixture fixture = fixtureWithRunEvents();

        var firstAck = fixture.replayService.acknowledge(
                new AgentRuntimeEventReplayAckRequest("python-ws-bridge", "run-replay", null, 3L, Instant.now()),
                projectOwnerContext()
        );
        var staleAck = fixture.replayService.acknowledge(
                new AgentRuntimeEventReplayAckRequest("python-ws-bridge", "run-replay", null, 2L, Instant.now()),
                projectOwnerContext()
        );

        assertTrue(firstAck.advanced());
        assertEquals("ACK_ADVANCED", firstAck.reason());
        assertFalse(staleAck.advanced());
        assertEquals("STALE_ACK_IGNORED", staleAck.reason());
        assertEquals(3L, staleAck.acknowledgedReplaySequence());
        assertEquals(3L, staleAck.previousAcknowledgedReplaySequence());
    }

    @Test
    void replayShouldRejectUnboundedQuery() {
        TestFixture fixture = fixtureWithRunEvents();

        assertThrows(PlatformBusinessException.class, () -> fixture.replayService.replay(
                new AgentRuntimeEventProjectionQuery(null, null, null, null,
                        null, null, null, null, 10),
                projectOwnerContext(),
                "web-tab-1"
        ));
    }

    private TestFixture fixtureWithRunEvents() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        projectionStore.append(eventRecord("event-1", "run-replay", 1));
        projectionStore.append(eventRecord("event-2", "run-replay", 2));
        projectionStore.append(eventRecord("event-3", "run-replay", 3));
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                projectionStore,
                new AgentRuntimeEventConsumerStats(),
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport(),
                new AgentRuntimeEventDisplaySupport()
        );
        AgentRuntimeEventReplayService replayService = new AgentRuntimeEventReplayService(
                queryService,
                new AgentRuntimeEventProjectionAccessSupport(),
                new InMemoryAgentRuntimeEventReplayCursorStore()
        );
        return new TestFixture(replayService);
    }

    private AgentRuntimeEventProjectionRecord eventRecord(String identityKey, String runId, long producerSequence) {
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "tool_completed",
                "execute_tool",
                "工具执行完成",
                "audit",
                "10",
                "20",
                "1001",
                "request-replay",
                runId,
                "session-replay",
                producerSequence,
                Instant.parse("2026-05-31T10:00:0" + producerSequence + "Z"),
                Instant.parse("2026-05-31T10:00:0" + producerSequence + "Z"),
                Instant.parse("2026-05-31T10:00:0" + producerSequence + "Z"),
                Map.of("safeCounter", producerSequence)
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-replay-test",
                "PROJECT",
                List.of(20L)
        );
    }

    private AgentRuntimeEventConsumerProperties properties() {
        AgentRuntimeEventConsumerProperties properties = new AgentRuntimeEventConsumerProperties();
        properties.setEnabled(false);
        properties.setTopic("datasmart.agent-runtime.events");
        properties.setGroupId("datasmart-agent-runtime-control-plane");
        properties.setMaxEventsPerRun(1000);
        properties.setMaxTotalEvents(10000);
        return properties;
    }

    private record TestFixture(AgentRuntimeEventReplayService replayService) {
    }
}
