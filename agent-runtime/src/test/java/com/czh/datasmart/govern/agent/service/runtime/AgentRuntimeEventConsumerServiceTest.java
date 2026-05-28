/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumerServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent runtime event 消费服务单元测试。
 *
 * <p>这组测试不启动 Kafka broker，也不启动 Spring 容器。
 * 原因是当前最需要保护的是业务契约本身：
 * Python 发布的 JSON 是否能被 Java 解析？重复投递是否会被幂等跳过？
 * 缺少关键关联 ID 的消息是否会被拒绝？投影窗口是否会限制内存增长？
 * 这些规则比 Kafka 网络联通更基础，应优先用低成本单测固定下来。</p>
 */
class AgentRuntimeEventConsumerServiceTest {

    @Test
    void consumeShouldAcceptPythonRuntimeEventAndCreateRunProjection() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);

        AgentRuntimeEventConsumeResult result = service.consume(runtimeEventPayload("run-001", 7));

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        assertEquals(1, store.size());
        List<AgentRuntimeEventProjectionRecord> records = store.listByRunId("run-001");
        assertEquals(1, records.size());
        AgentRuntimeEventProjectionRecord record = records.getFirst();
        assertEquals("agent-runtime-event.v1", record.schemaVersion());
        assertEquals("python-ai-runtime", record.source());
        assertEquals("tool_planned", record.eventType());
        assertEquals("audit", record.severity());
        assertEquals("tenant-a", record.tenantId());
        assertEquals("project-a", record.projectId());
        assertEquals("actor-a", record.actorId());
        assertEquals(7L, record.sequence());
        assertEquals(128, record.attributes().get("tokens"));
        assertTrue(store.findByIdentityKey(result.identityKey()).isPresent());
        assertEquals(1, stats.snapshot().acceptedCount());
    }

    @Test
    void consumeShouldTreatSameEventAsDuplicate() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);
        String payload = runtimeEventPayload("run-duplicate", 1);

        AgentRuntimeEventConsumeResult first = service.consume(payload);
        AgentRuntimeEventConsumeResult second = service.consume(payload);

        assertTrue(first.accepted());
        assertTrue(second.duplicate());
        assertEquals(first.identityKey(), second.identityKey());
        assertEquals(1, store.size());
        assertEquals(1, stats.snapshot().acceptedCount());
        assertEquals(1, stats.snapshot().duplicateCount());
    }

    @Test
    void consumeShouldRejectMalformedOrUnsupportedPayload() {
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100),
                new AgentRuntimeEventConsumerStats()
        );

        assertEquals("EMPTY_PAYLOAD", service.consume(" ").reason());
        assertEquals("MALFORMED_JSON", service.consume("{not-json").reason());
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", service.consume("""
                {"schemaVersion":"agent-runtime-event.v2","eventType":"tool_planned","runId":"run-a"}
                """).reason());
        assertEquals("MISSING_EVENT_TYPE", service.consume("""
                {"schemaVersion":"agent-runtime-event.v1","runId":"run-a"}
                """).reason());
        assertEquals("MISSING_CORRELATION_ID", service.consume("""
                {"schemaVersion":"agent-runtime-event.v1","eventType":"tool_planned"}
                """).reason());
    }

    @Test
    void projectionStoreShouldTrimPerRunAndGlobalWindows() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(2, 3);
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                store,
                new AgentRuntimeEventConsumerStats()
        );

        service.consume(runtimeEventPayload("run-window", 1));
        service.consume(runtimeEventPayload("run-window", 2));
        service.consume(runtimeEventPayload("run-window", 3));

        List<AgentRuntimeEventProjectionRecord> runWindow = store.listByRunId("run-window");
        assertEquals(2, runWindow.size());
        assertEquals(2L, runWindow.get(0).sequence());
        assertEquals(3L, runWindow.get(1).sequence());

        service.consume(runtimeEventPayload("run-a", 1));
        service.consume(runtimeEventPayload("run-b", 1));

        assertEquals(3, store.size());
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private String runtimeEventPayload(String runId, long sequence) {
        return """
                {
                  "schemaVersion": "agent-runtime-event.v1",
                  "source": "python-ai-runtime",
                  "publishedAt": "2026-05-27T00:00:01Z",
                  "eventType": "tool_planned",
                  "stage": "plan_tools",
                  "message": "已生成工具调用计划",
                  "severity": "audit",
                  "tenantId": "tenant-a",
                  "projectId": "project-a",
                  "actorId": "actor-a",
                  "requestId": "request-a",
                  "runId": "%s",
                  "sessionId": "session-a",
                  "sequence": %d,
                  "attributes": {"tokens": 128, "toolName": "datasource.metadata.read"},
                  "createdAt": "2026-05-27T00:00:%02dZ"
                }
                """.formatted(runId, sequence, sequence);
    }
}
