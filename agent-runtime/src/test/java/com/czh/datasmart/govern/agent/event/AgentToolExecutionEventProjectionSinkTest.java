/**
 * @Author : Cui
 * @Date: 2026/05/28 02:10
 * @Description DataSmart Govern Backend - AgentToolExecutionEventProjectionSinkTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventProperties;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentRuntimeEventProjectionStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 工具执行状态事件写入 runtime-event 投影的测试。
 *
 * <p>这组测试保护 4.12 的关键集成边界：工具状态事件不只是被 Kafka 异步发出去，
 * 也会进入 Java 控制面的 runtime-event 热投影。这样前端、gateway 和 Python Runtime 后续都可以
 * 通过同一套事件查询/回放能力获取工具执行轨迹，而不是一部分事件查 Python、一部分事件查 Java 审计接口。</p>
 *
 * <p>测试重点不是 Spring 容器装配，而是业务契约：</p>
 * <p>1. 统一发布器会把工具事件扇出到投影 sink。</p>
 * <p>2. 投影里保留 run/session/tenant/project/actor 等关键关联字段。</p>
 * <p>3. 投影 attributes 不泄漏完整工具入参，只暴露参数键名和治理摘要。</p>
 * <p>4. 某个 sink 失败不会阻断其他 sink，这为 Kafka、WebSocket、outbox 多通道并存打基础。</p>
 */
class AgentToolExecutionEventProjectionSinkTest {

    @Test
    void publisherShouldProjectToolExecutionEventIntoRuntimeEventStore() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        DefaultAgentToolExecutionEventPublisher publisher = publisherWith(
                new AgentToolExecutionEventProjectionSink(store)
        );
        AgentToolExecutionAuditRecord record = auditRecord(
                "atea-projection-executing",
                AgentToolExecutionState.PLANNED
        );
        record.startExecution("工具计划已进入 EXECUTING，正在调用受控工具适配器。");

        publisher.publishStateChanged(AgentToolExecutionState.PLANNED, record);

        List<AgentRuntimeEventProjectionRecord> records = store.listByRunId("run-projection-001");
        assertEquals(1, records.size());
        AgentRuntimeEventProjectionRecord projection = records.getFirst();
        assertEquals(AgentToolExecutionStateChangedEvent.SCHEMA_VERSION, projection.schemaVersion());
        assertEquals(AgentToolExecutionStateChangedEvent.EVENT_TYPE, projection.eventType());
        assertEquals("tool_executing", projection.stage());
        assertEquals("info", projection.severity());
        assertEquals("10", projection.tenantId());
        assertEquals("20", projection.projectId());
        assertEquals("actor-projection", projection.actorId());
        assertEquals("trace-projection-001", projection.requestId());
        assertEquals("session-projection-001", projection.sessionId());
        assertEquals("run-projection-001", projection.runId());
        assertEquals(record.getAuditId(), projection.attributes().get("auditId"));
        assertEquals("EXECUTING", projection.attributes().get("currentState"));
        assertTrue(((List<?>) projection.attributes().get("planArgumentKeys")).contains("objective"));
        assertFalse(projection.attributes().toString().contains("包含敏感业务目标"));
    }

    @Test
    void projectionShouldRepresentApprovalWaitingAsAuditStage() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        DefaultAgentToolExecutionEventPublisher publisher = publisherWith(
                new AgentToolExecutionEventProjectionSink(store)
        );
        AgentToolExecutionAuditRecord record = auditRecord(
                "atea-projection-approval",
                AgentToolExecutionState.WAITING_APPROVAL
        );

        publisher.publishStateChanged(null, record);

        AgentRuntimeEventProjectionRecord projection = store.listByRunId("run-projection-001").getFirst();
        assertEquals("approval_waiting", projection.stage());
        assertEquals("audit", projection.severity());
        assertEquals("WAITING_APPROVAL", projection.attributes().get("currentState"));
        assertEquals(true, projection.attributes().get("requiresApproval"));
    }

    @Test
    void publisherShouldContinueProjectingWhenAnotherSinkFails() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolExecutionEventSink failingSink = (previousState, record, event) -> {
            throw new IllegalStateException("模拟 Kafka 或 WebSocket sink 暂时不可用");
        };
        DefaultAgentToolExecutionEventPublisher publisher = publisherWith(
                failingSink,
                new AgentToolExecutionEventProjectionSink(store)
        );
        AgentToolExecutionAuditRecord record = auditRecord(
                "atea-projection-fail-open",
                AgentToolExecutionState.PLANNED
        );

        publisher.publishStateChanged(null, record);

        assertEquals(1, store.size());
        assertEquals(record.getAuditId(), store.listByRunId("run-projection-001").getFirst().attributes().get("auditId"));
    }

    @Test
    void publisherShouldRethrowWhenRequiredSinkFails() {
        AgentToolExecutionEventSink requiredFailingSink = new AgentToolExecutionEventSink() {
            @Override
            public void accept(AgentToolExecutionState previousState,
                               AgentToolExecutionAuditRecord record,
                               AgentToolExecutionStateChangedEvent event) {
                throw new IllegalStateException("模拟事务 outbox 写入失败");
            }

            @Override
            public boolean requiredForStateCommit() {
                return true;
            }
        };
        DefaultAgentToolExecutionEventPublisher publisher = publisherWith(requiredFailingSink);
        AgentToolExecutionAuditRecord record = auditRecord(
                "atea-projection-required-fail",
                AgentToolExecutionState.PLANNED
        );

        assertThrows(AgentToolExecutionRequiredEventSinkException.class,
                () -> publisher.publishStateChanged(null, record));
    }

    private DefaultAgentToolExecutionEventPublisher publisherWith(AgentToolExecutionEventSink... sinks) {
        AgentToolExecutionEventProperties properties = new AgentToolExecutionEventProperties();
        properties.setSource("agent-runtime");
        return new DefaultAgentToolExecutionEventPublisher(properties, List.of(sinks));
    }

    private AgentToolExecutionAuditRecord auditRecord(String auditId, AgentToolExecutionState state) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-projection-001",
                "run-projection-001",
                "binding-projection-001",
                "task.draft.persist",
                "TASK_MANAGEMENT",
                "task-management",
                "/task-drafts",
                1001L,
                10L,
                20L,
                30L,
                "actor-projection",
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
                "trace-projection-001",
                "工具计划已进入 Java 控制面，等待后续审批或执行。",
                LocalDateTime.of(2026, 5, 28, 2, 10)
        );
    }
}
