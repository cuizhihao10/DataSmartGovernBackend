/**
 * @Author : Cui
 * @Date: 2026/05/28 21:05
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxOperationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.outbox;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxOperationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxOperationResponse;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxRecord;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxStatus;
import com.czh.datasmart.govern.agent.event.outbox.InMemoryAgentToolExecutionEventOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * outbox 人工补偿服务测试。
 *
 * <p>这组测试保护 4.23 的运维闭环语义：BLOCKED/FAILED 可以被人工重新入队或忽略；
 * PENDING/PUBLISHING/PUBLISHED 不能被随意补偿，避免运维误操作造成重复投递或把成功事件伪装成忽略。</p>
 */
class AgentToolExecutionEventOutboxOperationServiceTest {

    @Test
    void requeueShouldMoveBlockedRecordBackToPending() {
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(record("outbox-operation-blocked", AgentToolExecutionEventOutboxStatus.BLOCKED));
        AgentToolExecutionEventOutboxOperationService service = new AgentToolExecutionEventOutboxOperationService(store);

        AgentToolExecutionEventOutboxOperationResponse response = service.requeue(
                "outbox-operation-blocked",
                new AgentToolExecutionEventOutboxOperationRequest("修复 Kafka topic ACL 后重新投递", null),
                "operator-1001"
        );

        assertEquals("REQUEUE", response.action());
        assertEquals("BLOCKED", response.previousStatus());
        assertEquals("PENDING", response.currentStatus());
        assertEquals("operator-1001", response.operatorId());
        AgentToolExecutionEventOutboxRecord current = store.findByOutboxId("outbox-operation-blocked").orElseThrow();
        assertEquals(AgentToolExecutionEventOutboxStatus.PENDING, current.status());
        assertTrue(current.lastError().contains("修复 Kafka topic ACL"));
    }

    @Test
    void ignoreShouldMoveFailedRecordToIgnoredAndUpdateDiagnostics() {
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(record("outbox-operation-failed", AgentToolExecutionEventOutboxStatus.FAILED));
        AgentToolExecutionEventOutboxOperationService service = new AgentToolExecutionEventOutboxOperationService(store);

        AgentToolExecutionEventOutboxOperationResponse response = service.ignore(
                "outbox-operation-failed",
                new AgentToolExecutionEventOutboxOperationRequest("客户确认该历史测试事件无需补发", "operator-body"),
                "operator-header"
        );

        assertEquals("IGNORE", response.action());
        assertEquals("FAILED", response.previousStatus());
        assertEquals("IGNORED", response.currentStatus());
        assertEquals("operator-body", response.operatorId());
        assertEquals(1, store.diagnostics().ignoredRecords());
    }

    @Test
    void noteShouldAppendOperationContextWithoutChangingStatus() {
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(record("outbox-operation-note", AgentToolExecutionEventOutboxStatus.BLOCKED));
        AgentToolExecutionEventOutboxOperationService service = new AgentToolExecutionEventOutboxOperationService(store);

        AgentToolExecutionEventOutboxOperationResponse response = service.appendNote(
                "outbox-operation-note",
                new AgentToolExecutionEventOutboxOperationRequest("等待下游审计中心恢复后再处理", null),
                "operator-note"
        );

        assertEquals("NOTE", response.action());
        assertEquals("BLOCKED", response.previousStatus());
        assertEquals("BLOCKED", response.currentStatus());
        assertTrue(response.reason().contains("等待下游审计中心恢复"));
        assertEquals(AgentToolExecutionEventOutboxStatus.BLOCKED,
                store.findByOutboxId("outbox-operation-note").orElseThrow().status());
    }

    @Test
    void requeueShouldRejectPendingRecord() {
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(record("outbox-operation-pending", AgentToolExecutionEventOutboxStatus.PENDING));
        AgentToolExecutionEventOutboxOperationService service = new AgentToolExecutionEventOutboxOperationService(store);

        assertThrows(PlatformBusinessException.class, () -> service.requeue(
                "outbox-operation-pending",
                new AgentToolExecutionEventOutboxOperationRequest("这条记录还没有失败，不允许人工重放", null),
                "operator-1001"
        ));
    }

    @Test
    void operationShouldRequireReason() {
        InMemoryAgentToolExecutionEventOutboxStore store = new InMemoryAgentToolExecutionEventOutboxStore(10, 100);
        store.append(record("outbox-operation-no-reason", AgentToolExecutionEventOutboxStatus.BLOCKED));
        AgentToolExecutionEventOutboxOperationService service = new AgentToolExecutionEventOutboxOperationService(store);

        assertThrows(PlatformBusinessException.class, () -> service.ignore(
                "outbox-operation-no-reason",
                new AgentToolExecutionEventOutboxOperationRequest(" ", null),
                "operator-1001"
        ));
    }

    private AgentToolExecutionEventOutboxRecord record(String outboxId, AgentToolExecutionEventOutboxStatus status) {
        Instant now = Instant.now();
        return new AgentToolExecutionEventOutboxRecord(
                outboxId,
                "event-" + outboxId,
                "agent.tool_execution.state_changed",
                "agent-tool-execution-event.v1",
                "agent-runtime",
                "run-operation-001",
                "10",
                "20",
                "30",
                "actor-operation",
                "session-operation-001",
                "run-operation-001",
                "audit-operation-001",
                "task.draft.persist",
                "FAILED",
                status,
                2,
                now,
                now,
                now,
                null,
                null,
                "初始错误",
                64,
                false,
                "{\"eventType\":\"agent.tool_execution.state_changed\"}"
        );
    }
}
