/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateReconciliationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateReconciliationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令任务最终态对账服务测试。
 *
 * <p>这组测试保护 5.108 的收敛能力：worker receipt 不能只停留在 timeline 展示，必须能被解释成
 * task-management / Agent 审计可消费的低敏状态建议。测试刻意覆盖成功、失败前阻断、容量退避和无 receipt，
 * 是为了避免后续接入真实自动回调时把“尚未执行完成”的命令误判成终态。</p>
 */
class AgentCommandTaskFinalStateReconciliationServiceTest {

    @Test
    void shouldRecommendSucceededTerminalCallbackFromLatestExecutionReceipt() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt("receipt-precheck", "WORKER_PRECHECK_PASSED", "RUNNING", true, false, null, 11L));
        store.upsert(receipt("receipt-success", "EXECUTION_SUCCEEDED", "SUCCEEDED", true, true,
                "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED", 12L));
        AgentCommandTaskFinalStateReconciliationService service = service(store);

        AgentCommandTaskFinalStateReconciliationResponse response = service.reconcile(
                "cmd-final-001",
                "command.run-program",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                20,
                platformAccess()
        );

        assertEquals(2, response.receiptCount());
        assertEquals("SUCCEEDED", response.reconciliationStatus());
        assertEquals("SUCCEEDED", response.reconciledTaskStatus());
        assertEquals(true, response.terminal());
        assertEquals(true, response.callbackRecommended());
        assertEquals(false, response.requiresManualCompensation());
        assertEquals("SUCCEEDED", response.callbackSuggestion().callbackStatus());
        assertEquals(9001L, response.latestReceipt().taskId());
        assertEquals("audit-final-001", response.latestReceipt().auditId());
        assertNotNull(response.latestReceipt().receiptFingerprint());
        assertFalse(response.latestReceipt().receiptFingerprint().contains("receipt-success"));
        assertTrue(response.evidenceCodes().contains("FINAL_STATE_RECEIPT_EXECUTION_SUCCEEDED"));
    }

    @Test
    void shouldMarkFailedPrecheckAsBlockedBeforeExecutionWithoutManualCompensation() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt("receipt-blocked", "FAILED_PRECHECK", "FAILED", false, false,
                "AGENT_COMMAND_WORKER_PRECHECK_REJECTED", 21L));
        AgentCommandTaskFinalStateReconciliationService service = service(store);

        AgentCommandTaskFinalStateReconciliationResponse response = service.reconcile(
                "cmd-final-001",
                "command.run-program",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                null,
                platformAccess()
        );

        assertEquals("BLOCKED_BEFORE_EXECUTION", response.reconciliationStatus());
        assertEquals("FAILED", response.reconciledTaskStatus());
        assertEquals(true, response.terminal());
        assertEquals(true, response.callbackRecommended());
        assertEquals(false, response.requiresManualCompensation());
        assertEquals("FAILED", response.callbackSuggestion().callbackStatus());
        assertTrue(response.issueCodes().contains("COMMAND_BLOCKED_BEFORE_SIDE_EFFECT"));
        assertTrue(response.evidenceCodes().contains("FINAL_STATE_RECEIPT_FAILED_PRECHECK"));
    }

    @Test
    void shouldKeepCapacityLimitedReceiptNonTerminalAndRetryable() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt("receipt-capacity", "CAPACITY_LIMITED", "DEFERRED", false, false,
                "AGENT_COMMAND_WORKER_CAPACITY_LIMITED", 31L));
        AgentCommandTaskFinalStateReconciliationService service = service(store);

        AgentCommandTaskFinalStateReconciliationResponse response = service.reconcile(
                "cmd-final-001",
                "command.run-program",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                null,
                platformAccess()
        );

        assertEquals("WAITING_WORKER_CAPACITY", response.reconciliationStatus());
        assertEquals("DEFERRED", response.reconciledTaskStatus());
        assertEquals(false, response.terminal());
        assertEquals(true, response.retryable());
        assertEquals(true, response.callbackRecommended());
        assertEquals("DEFERRED", response.callbackSuggestion().callbackStatus());
        assertTrue(response.issueCodes().contains("WORKER_CAPACITY_LIMITED_NOT_TERMINAL"));
    }

    @Test
    void shouldReturnWaitingWhenReceiptIsMissing() {
        AgentCommandTaskFinalStateReconciliationService service = service(new InMemoryAgentToolActionWorkerReceiptIndexStore(100));

        AgentCommandTaskFinalStateReconciliationResponse response = service.reconcile(
                "cmd-final-absent",
                "command.run-program",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                null,
                platformAccess()
        );

        assertEquals(0, response.receiptCount());
        assertEquals(false, response.latestReceiptPresent());
        assertNull(response.latestReceipt());
        assertEquals("WAITING_WORKER_RECEIPT", response.reconciliationStatus());
        assertEquals(false, response.callbackRecommended());
        assertTrue(response.issueCodes().contains("WORKER_RECEIPT_NOT_FOUND"));
    }

    @Test
    void responseShouldNotExposeSensitiveReceiptText() throws JsonProcessingException {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-sensitive",
                "cmd-final-001",
                9001L,
                9101L,
                "worker-safe",
                "audit-final-001",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                "command.run-program",
                "FAILED",
                "EXECUTION_FAILED",
                true,
                false,
                "select * from secret_table prompt: hidden token",
                41L,
                Instant.parse("2026-06-26T00:00:41Z"),
                Instant.parse("2026-06-26T00:01:41Z")
        ));
        AgentCommandTaskFinalStateReconciliationService service = service(store);

        AgentCommandTaskFinalStateReconciliationResponse response = service.reconcile(
                "cmd-final-001",
                "command.run-program",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                null,
                platformAccess()
        );
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertEquals("FAILED", response.reconciliationStatus());
        assertFalse(json.toLowerCase().contains("select *"));
        assertFalse(json.toLowerCase().contains("secret_table"));
        assertFalse(json.toLowerCase().contains("prompt: hidden"));
        assertFalse(json.toLowerCase().contains(" token"));
    }

    private AgentCommandTaskFinalStateReconciliationService service(InMemoryAgentToolActionWorkerReceiptIndexStore store) {
        return new AgentCommandTaskFinalStateReconciliationService(
                new AgentToolActionWorkerReceiptIndexService(store),
                new AgentRuntimeEventProjectionAccessSupport()
        );
    }

    private AgentRuntimeEventQueryAccessContext platformAccess() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PLATFORM_ADMINISTRATOR",
                "trace-final-state",
                "PLATFORM",
                List.of()
        );
    }

    private AgentToolActionWorkerReceiptIndexRecord receipt(String identityKey,
                                                            String outcome,
                                                            String taskStatus,
                                                            boolean preCheckPassed,
                                                            boolean sideEffectExecuted,
                                                            String errorCode,
                                                            long replaySequence) {
        return new AgentToolActionWorkerReceiptIndexRecord(
                identityKey,
                "cmd-final-001",
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
                taskStatus,
                outcome,
                preCheckPassed,
                sideEffectExecuted,
                errorCode,
                replaySequence,
                Instant.parse("2026-06-26T00:00:00Z").plusSeconds(replaySequence),
                Instant.parse("2026-06-26T00:10:00Z").plusSeconds(replaySequence)
        );
    }
}
