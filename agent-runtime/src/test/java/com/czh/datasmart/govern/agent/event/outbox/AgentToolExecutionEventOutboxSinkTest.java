/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxSinkTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxQueryResponse;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionStateChangedEvent;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.outbox.AgentToolExecutionEventOutboxQueryService;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具执行事件 outbox sink 测试。
 *
 * <p>这组测试保护 4.15 的可靠性底座：同一条工具状态事件应先进入 outbox，
 * outbox 应具备幂等写入、状态流转、重试筛选和安全阻断能力。
 * 这样后续即使 Kafka、WebSocket 或审计中心暂时不可用，Java 控制面也有一个可查询、可补偿的事件事实入口。</p>
 */
class AgentToolExecutionEventOutboxSinkTest {

    @Test
    void sinkShouldAppendPendingOutboxRecordWithSafePayload() {
        AgentToolExecutionEventOutboxProperties properties = new AgentToolExecutionEventOutboxProperties();
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        AgentToolExecutionEventOutboxSink sink = new AgentToolExecutionEventOutboxSink(
                properties,
                new AgentRuntimePersistenceProperties(),
                store,
                objectMapper()
        );
        AgentToolExecutionAuditRecord audit = auditRecord("atea-outbox-pending", AgentToolExecutionState.PLANNED);
        audit.startExecution("工具开始执行，准备调用受控适配器");
        AgentToolExecutionStateChangedEvent event = AgentToolExecutionStateChangedEvent.from(
                "agent-runtime",
                AgentToolExecutionState.PLANNED,
                audit
        );

        sink.accept(AgentToolExecutionState.PLANNED, audit, event);

        List<AgentToolExecutionEventOutboxRecord> records = store.list("run-outbox-001", null, 10);
        assertEquals(1, records.size());
        AgentToolExecutionEventOutboxRecord record = records.getFirst();
        assertEquals("outbox:" + event.eventId(), record.outboxId());
        assertEquals(AgentToolExecutionEventOutboxStatus.PENDING, record.status());
        assertEquals(event.partitionKey(), record.partitionKey());
        assertEquals("EXECUTING", record.currentState());
        assertTrue(record.payloadJson().contains("agent.tool_execution.state_changed"));
        assertTrue(record.payloadJson().contains("planArgumentKeys"));
        assertFalse(record.payloadJson().contains("包含敏感业务目标"));
        assertEquals(1, store.diagnostics().pendingRecords());
    }

    @Test
    void storeShouldDeduplicateAndSupportDispatchStateTransitions() {
        AgentToolExecutionEventOutboxProperties properties = new AgentToolExecutionEventOutboxProperties();
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        AgentToolExecutionEventOutboxSink sink = new AgentToolExecutionEventOutboxSink(
                properties,
                new AgentRuntimePersistenceProperties(),
                store,
                objectMapper()
        );
        AgentToolExecutionAuditRecord audit = auditRecord("atea-outbox-deduplicate", AgentToolExecutionState.WAITING_APPROVAL);
        AgentToolExecutionStateChangedEvent event = AgentToolExecutionStateChangedEvent.from(
                "agent-runtime",
                null,
                audit
        );

        sink.accept(null, audit, event);
        sink.accept(null, audit, event);

        AgentToolExecutionEventOutboxRecord record = store.list("run-outbox-001", null, 10).getFirst();
        assertEquals(1, store.diagnostics().totalRecords());

        AgentToolExecutionEventOutboxRecord publishing = store.markPublishing(record.outboxId(), Instant.parse("2026-05-28T10:00:00Z"))
                .orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.PUBLISHING, publishing.status());
        assertEquals(1, publishing.attemptCount());

        AgentToolExecutionEventOutboxRecord failed = store.markFailed(
                record.outboxId(),
                "模拟 Kafka 临时不可用",
                Instant.parse("2026-05-28T10:00:10Z"),
                Instant.parse("2026-05-28T10:00:30Z")
        ).orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.FAILED, failed.status());
        assertEquals(0, store.listPublishable(10, Instant.parse("2026-05-28T10:00:20Z")).size());
        assertEquals(1, store.listPublishable(10, Instant.parse("2026-05-28T10:00:31Z")).size());

        AgentToolExecutionEventOutboxRecord published = store.markPublished(record.outboxId(), Instant.parse("2026-05-28T10:01:00Z"))
                .orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.PUBLISHED, published.status());
        assertEquals("", published.lastError());
    }

    @Test
    void oversizedPayloadShouldBecomeBlockedRecord() {
        AgentToolExecutionEventOutboxProperties properties = new AgentToolExecutionEventOutboxProperties();
        properties.setMaxPayloadBytes(32);
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        AgentToolExecutionEventOutboxSink sink = new AgentToolExecutionEventOutboxSink(
                properties,
                new AgentRuntimePersistenceProperties(),
                store,
                objectMapper()
        );
        AgentToolExecutionAuditRecord audit = auditRecord("atea-outbox-blocked", AgentToolExecutionState.PLANNED);
        AgentToolExecutionStateChangedEvent event = AgentToolExecutionStateChangedEvent.from(
                "agent-runtime",
                null,
                audit
        );

        sink.accept(null, audit, event);

        AgentToolExecutionEventOutboxRecord record = store.list("run-outbox-001", null, 10).getFirst();
        assertEquals(AgentToolExecutionEventOutboxStatus.BLOCKED, record.status());
        assertTrue(record.payloadTruncated());
        assertTrue(record.lastError().contains("payload 超过 outbox 最大字节数限制"));
        assertEquals(0, store.listPublishable(10, Instant.now()).size());
        assertEquals(1, store.diagnostics().blockedRecords());
    }

    @Test
    void queryServiceShouldFilterStatusAndRejectUnknownStatus() {
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        AgentToolExecutionEventOutboxSink sink = new AgentToolExecutionEventOutboxSink(
                new AgentToolExecutionEventOutboxProperties(),
                new AgentRuntimePersistenceProperties(),
                store,
                objectMapper()
        );
        AgentToolExecutionAuditRecord audit = auditRecord("atea-outbox-query", AgentToolExecutionState.PLANNED);
        AgentToolExecutionStateChangedEvent event = AgentToolExecutionStateChangedEvent.from(
                "agent-runtime",
                null,
                audit
        );
        sink.accept(null, audit, event);
        AgentToolExecutionEventOutboxQueryService queryService = new AgentToolExecutionEventOutboxQueryService(store);

        AgentToolExecutionEventOutboxQueryResponse response = queryService.query("run-outbox-001", "pending", 10);

        assertEquals(1, response.count());
        assertEquals("PENDING", response.status());
        assertEquals("atea-outbox-query", response.records().getFirst().auditId());
        assertThrows(PlatformBusinessException.class, () -> queryService.query(null, "UNKNOWN", 10));
    }

    @Test
    void sinkShouldBecomeRequiredWhenAuditAndOutboxAreBothMysql() {
        AgentRuntimePersistenceProperties persistenceProperties = new AgentRuntimePersistenceProperties();
        persistenceProperties.setDatabaseEnabled(true);
        persistenceProperties.setAuditStore("mysql");
        persistenceProperties.setOutboxStore("mysql");
        AgentToolExecutionEventOutboxSink sink = new AgentToolExecutionEventOutboxSink(
                new AgentToolExecutionEventOutboxProperties(),
                persistenceProperties,
                new InMemoryAgentToolExecutionEventOutboxStore(10, 100),
                objectMapper()
        );

        assertTrue(sink.requiredForStateCommit());
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private AgentToolExecutionAuditRecord auditRecord(String auditId, AgentToolExecutionState state) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-outbox-001",
                "run-outbox-001",
                "binding-outbox-001",
                "task.draft.persist",
                "TASK_MANAGEMENT",
                "task-management",
                "/task-drafts",
                1001L,
                10L,
                20L,
                30L,
                "actor-outbox",
                AgentToolRiskLevel.HIGH.name(),
                AgentToolExecutionMode.APPROVAL_REQUIRED.name(),
                true,
                false,
                false,
                List.of("CREATE"),
                "模型认为需要把治理目标保存为任务草稿，等待人工复核后再提交真实任务。",
                Map.of(
                        "objective", "包含敏感业务目标",
                        "datasourceId", 1001L
                ),
                Map.of(
                        "memoryWritePolicy", "EPISODIC",
                        "sensitiveFields", List.of("objective")
                ),
                Map.of(
                        "missingRequiredFields", List.of(),
                        "filledFromContext", List.of("datasourceId")
                ),
                state,
                "trace-outbox-001",
                "工具计划已进入 Java 控制面，等待 outbox 捕获状态事件。",
                LocalDateTime.of(2026, 5, 28, 18, 0)
        );
    }
}
