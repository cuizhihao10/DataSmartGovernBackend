/**
 * @Author : Cui
 * @Date: 2026/05/28 00:58
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditEventPublisherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionStateChangedEvent;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具执行事件发布测试。
 *
 * <p>这组测试保护的是“状态机事实是否能够稳定向外发布”。对类 Codex/Claude Code 的 Agent 产品来说，
 * 工具调用不是一个黑盒 HTTP 请求，而是一条可观察、可审批、可暂停、可回放、可被模型二次推理消费的状态链。
 * 因此只要审计状态发生变化，就必须有明确的事件边界，后续网关 WebSocket、Python Runtime、审计中心和指标系统
 * 才能基于同一份事实协同工作。</p>
 */
class AgentToolExecutionAuditEventPublisherTest {

    @Test
    void executionStateTransitionsShouldPublishOrderedEvents() {
        AgentToolExecutionAuditMemoryStore memoryStore = new AgentToolExecutionAuditMemoryStore();
        CollectingPublisher publisher = new CollectingPublisher();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(memoryStore, publisher);
        AgentToolExecutionAuditRecord record = auditRecord("atea-event-execute", AgentToolExecutionState.PLANNED);
        memoryStore.saveAll(List.of(record));

        auditService.startExecution(record.getSessionId(), record.getRunId(), record.getAuditId());
        auditService.succeedExecution(record, "工具执行成功", "工具执行成功，输出字段: datasourceId");

        assertEquals(2, publisher.transitions.size());
        assertEquals(new PublishedTransition(
                record.getAuditId(),
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionState.EXECUTING
        ), publisher.transitions.get(0));
        assertEquals(new PublishedTransition(
                record.getAuditId(),
                AgentToolExecutionState.EXECUTING,
                AgentToolExecutionState.SUCCEEDED
        ), publisher.transitions.get(1));
    }

    @Test
    void approvalDecisionTransitionsShouldPublishEvents() {
        AgentToolExecutionAuditMemoryStore memoryStore = new AgentToolExecutionAuditMemoryStore();
        CollectingPublisher publisher = new CollectingPublisher();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(memoryStore, publisher);
        AgentToolExecutionAuditRecord approvedRecord = auditRecord("atea-event-approve", AgentToolExecutionState.WAITING_APPROVAL);
        AgentToolExecutionAuditRecord rejectedRecord = auditRecord("atea-event-reject", AgentToolExecutionState.WAITING_APPROVAL);
        memoryStore.saveAll(List.of(approvedRecord, rejectedRecord));

        auditService.approve(
                approvedRecord.getSessionId(),
                approvedRecord.getRunId(),
                approvedRecord.getAuditId(),
                new AgentToolExecutionDecisionRequest("owner-001", "允许生成任务草稿")
        );
        auditService.reject(
                rejectedRecord.getSessionId(),
                rejectedRecord.getRunId(),
                rejectedRecord.getAuditId(),
                new AgentToolExecutionDecisionRequest("owner-001", "影响范围不清晰，暂不允许")
        );

        assertEquals(2, publisher.transitions.size());
        assertEquals(AgentToolExecutionState.PLANNED, publisher.transitions.get(0).currentState());
        assertEquals(AgentToolExecutionState.SKIPPED, publisher.transitions.get(1).currentState());
    }

    @Test
    void eventPayloadShouldKeepRawPlanArgumentsOutOfPublicAttributes() {
        AgentToolExecutionAuditRecord record = auditRecord("atea-event-payload", AgentToolExecutionState.EXECUTING);
        AgentToolExecutionStateChangedEvent event = AgentToolExecutionStateChangedEvent.from(
                "agent-runtime",
                AgentToolExecutionState.PLANNED,
                record
        );

        assertEquals(AgentToolExecutionStateChangedEvent.SCHEMA_VERSION, event.schemaVersion());
        assertEquals(AgentToolExecutionStateChangedEvent.EVENT_TYPE, event.eventType());
        assertEquals(record.getRunId(), event.partitionKey());
        assertEquals("PLANNED", event.previousState());
        assertEquals("EXECUTING", event.currentState());
        assertTrue(event.attributes().containsKey("planArgumentKeys"));
        assertTrue(event.attributes().toString().contains("objective"));
        assertFalse(event.attributes().toString().contains("包含敏感业务目标"));
    }

    @Test
    void publisherFailureShouldNotRollbackBusinessState() {
        AgentToolExecutionAuditMemoryStore memoryStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                memoryStore,
                (previousState, record) -> {
                    throw new IllegalStateException("模拟事件总线不可用");
                }
        );
        AgentToolExecutionAuditRecord record = auditRecord("atea-event-fail-open", AgentToolExecutionState.WAITING_APPROVAL);
        memoryStore.saveAll(List.of(record));

        AgentToolExecutionAuditView rejected = auditService.reject(
                record.getSessionId(),
                record.getRunId(),
                record.getAuditId(),
                new AgentToolExecutionDecisionRequest("owner-001", "事件失败也不能回滚业务审批")
        );

        assertEquals("SKIPPED", rejected.state());
        assertEquals(AgentToolExecutionState.SKIPPED, record.getState());
    }

    private AgentToolExecutionAuditRecord auditRecord(String auditId, AgentToolExecutionState state) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-event-001",
                "run-event-001",
                "binding-event-001",
                "task.draft.persist",
                "TASK_MANAGEMENT",
                "task-management",
                "/task-drafts",
                null,
                10L,
                20L,
                30L,
                "actor-event",
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
                "trace-event-001",
                "工具计划已生成，等待控制面继续推进。",
                LocalDateTime.of(2026, 5, 28, 0, 58)
        );
    }

    private static class CollectingPublisher implements AgentToolExecutionEventPublisher {

        private final List<PublishedTransition> transitions = new ArrayList<>();

        @Override
        public void publishStateChanged(AgentToolExecutionState previousState, AgentToolExecutionAuditRecord record) {
            transitions.add(new PublishedTransition(record.getAuditId(), previousState, record.getState()));
        }
    }

    private record PublishedTransition(String auditId,
                                       AgentToolExecutionState previousState,
                                       AgentToolExecutionState currentState) {
    }
}
