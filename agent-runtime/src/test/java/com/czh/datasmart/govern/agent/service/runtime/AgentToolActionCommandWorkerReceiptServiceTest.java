/**
 * @Author : Cui
 * @Date: 2026/06/23 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控命令 worker 回执服务测试。
 *
 * <p>这组测试保护 command outbox 主链路的一个关键收敛点：真实 worker 可以回写“执行成功/失败/阻断”等低敏事实，
 * 但不能借回执接口泄露命令行、stdout/stderr、真实路径、SQL、prompt、payload body、模型输出或内部 endpoint。
 * 只有当安全预检明确允许受控执行时，回执才可以声明 sideEffectExecuted=true。</p>
 */
class AgentToolActionCommandWorkerReceiptServiceTest {

    @Test
    void shouldAppendCommandWorkerReceiptIntoProjectionDisplayAndIndex() throws JsonProcessingException {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionWorkerReceiptIndexStore indexStore =
                new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        AgentToolActionWorkerReceiptIndexService indexService =
                new AgentToolActionWorkerReceiptIndexService(indexStore);
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(projectionStore, indexService);

        AgentToolActionCommandWorkerReceiptResponse response =
                service.receive("session-command", "run-command", "trace-command-worker", successRequest());

        assertTrue(response.accepted());
        assertFalse(response.duplicate());
        assertEquals(AgentToolActionCommandWorkerReceiptService.EVENT_TYPE, response.eventType());
        assertEquals("EXECUTION_SUCCEEDED", response.outcome());
        assertTrue(response.sideEffectExecuted());

        AgentRuntimeEventProjectionRecord record = projectionStore.listByRunId("run-command").getFirst();
        assertEquals(AgentToolActionCommandWorkerReceiptService.EVENT_TYPE, record.eventType());
        assertEquals("command_worker_execution_succeeded", record.stage());
        assertEquals("TASK_MANAGEMENT_WORKER", record.source());
        assertEquals("trace-command-worker", record.requestId());
        assertEquals("cmd-worker-001", record.attributes().get("commandId"));
        assertEquals(true, record.attributes().get("preCheckPassed"));
        assertEquals(true, record.attributes().get("sideEffectStarted"));
        assertEquals(true, record.attributes().get("sideEffectExecuted"));
        assertEquals("ALLOW_CONTROLLED_EXECUTION", record.attributes().get("commandSafetyDecision"));
        assertEquals("agent-artifact:run-command/receipt-001", record.attributes().get("artifactReference"));
        assertEquals(AgentToolActionCommandWorkerReceiptService.PAYLOAD_POLICY,
                record.attributes().get("eventPayloadPolicy"));

        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(record);
        assertEquals("TOOL_ACTION_COMMAND_WORKER_RECEIPT", display.category());
        assertEquals("SIDE_EFFECT_CONFIRMED", display.status());
        assertFalse(display.requiresAttention());
        assertEquals(true, display.metrics().get("sideEffectExecuted"));

        List<AgentToolActionWorkerReceiptIndexRecord> indexRecords = indexStore.queryByCommandId(
                new AgentToolActionWorkerReceiptIndexQuery(
                        "cmd-worker-001",
                        "command.run-program",
                        "10",
                        "20",
                        "30",
                        "run-command",
                        "session-command",
                        List.of("20"),
                        10
                )
        );
        assertEquals(1, indexRecords.size());
        AgentToolActionWorkerReceiptIndexRecord indexRecord = indexRecords.getFirst();
        assertEquals("EXECUTION_SUCCEEDED", indexRecord.outcome());
        assertEquals(true, indexRecord.preCheckPassed());
        assertEquals(true, indexRecord.sideEffectExecuted());
        assertEquals(1L, indexRecord.replaySequence());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(record);
        assertFalse(json.contains("stdout"));
        assertFalse(json.contains("stderr"));
        assertFalse(json.contains("commandLine"));
        assertFalse(json.contains("select * from"));
        assertFalse(json.contains("prompt:"));
    }

    @Test
    void duplicateCommandWorkerReceiptShouldBeAcceptedAsIdempotentReplay() {
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(new InMemoryAgentRuntimeEventProjectionStore(10, 100));

        service.receive("session-command", "run-command", "trace-command-worker", successRequest());
        AgentToolActionCommandWorkerReceiptResponse duplicate =
                service.receive("session-command", "run-command", "trace-command-worker", successRequest());

        assertTrue(duplicate.accepted());
        assertTrue(duplicate.duplicate());
        assertEquals("EXECUTION_SUCCEEDED", duplicate.outcome());
    }

    @Test
    void sideEffectExecutedShouldRequireAllowControlledExecutionDecision() {
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(new InMemoryAgentRuntimeEventProjectionStore(10, 100));
        AgentToolActionCommandWorkerReceiptRequest request = request(
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                "REQUIRES_HUMAN_APPROVAL",
                List.of()
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive("session-command", "run-command", "trace-command-worker", request));
    }

    @Test
    void sideEffectExecutedShouldRejectOpenSafetyIssueCodes() {
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(new InMemoryAgentRuntimeEventProjectionStore(10, 100));
        AgentToolActionCommandWorkerReceiptRequest request = request(
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of("NETWORK_REQUIRES_APPROVAL")
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive("session-command", "run-command", "trace-command-worker", request));
    }

    @Test
    void failedPrecheckShouldEnterBlockedDisplayWithoutSideEffect() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(store);

        service.receive("session-command", "run-command", "trace-command-worker",
                request("FAILED_PRECHECK", false, false, false, "BLOCKED", List.of("DANGEROUS_PATH")));

        AgentRuntimeEventProjectionRecord record = store.listByRunId("run-command").getFirst();
        assertEquals("command_worker_precheck_failed", record.stage());
        assertEquals(false, record.attributes().get("sideEffectExecuted"));
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(record);
        assertEquals("BLOCKED_BEFORE_SIDE_EFFECT", display.status());
        assertTrue(display.requiresAttention());
    }

    @Test
    void sensitiveMessageShouldBeRejectedBeforeEnteringTimeline() {
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(new InMemoryAgentRuntimeEventProjectionStore(10, 100));
        AgentToolActionCommandWorkerReceiptRequest request = new AgentToolActionCommandWorkerReceiptRequest(
                "cmd-worker-001",
                9101L,
                9201L,
                "agent-command-worker",
                10L,
                20L,
                30L,
                "RUNNING",
                "EXECUTION_FAILED",
                true,
                true,
                false,
                "ALLOW_CONTROLLED_EXECUTION",
                "command-safety-policy.v1",
                List.of(),
                30,
                4096,
                null,
                null,
                false,
                "AGENT_COMMAND_WORKER_EXECUTION_FAILED",
                "audit-command-worker-001",
                "command.run-program",
                "task-management-worker",
                "EXECUTION_RESULT",
                "stdout: raw output should not enter event",
                List.of("查看低敏失败摘要"),
                "command-worker:cmd-worker-001:execution-failed"
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive("session-command", "run-command", "trace-command-worker", request));
    }

    @Test
    void unsafeArtifactReferenceShouldBeRejected() {
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(new InMemoryAgentRuntimeEventProjectionStore(10, 100));
        AgentToolActionCommandWorkerReceiptRequest request = new AgentToolActionCommandWorkerReceiptRequest(
                "cmd-worker-001",
                9101L,
                9201L,
                "agent-command-worker",
                10L,
                20L,
                30L,
                "RUNNING",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                "ALLOW_CONTROLLED_EXECUTION",
                "command-safety-policy.v1",
                List.of(),
                30,
                4096,
                "MINIO_OBJECT",
                "https://internal.example.local/raw-output",
                true,
                null,
                "audit-command-worker-001",
                "command.run-program",
                "task-management-worker",
                "EXECUTION_RESULT",
                "受控命令执行成功。",
                List.of("确认产物元数据"),
                "command-worker:cmd-worker-001:unsafe-artifact"
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive("session-command", "run-command", "trace-command-worker", request));
    }

    private AgentToolActionCommandWorkerReceiptRequest successRequest() {
        return request("EXECUTION_SUCCEEDED", true, true, true, "ALLOW_CONTROLLED_EXECUTION", List.of());
    }

    private AgentToolActionCommandWorkerReceiptRequest request(String outcome,
                                                              boolean preCheckPassed,
                                                              boolean sideEffectStarted,
                                                              boolean sideEffectExecuted,
                                                              String safetyDecision,
                                                              List<String> issueCodes) {
        return new AgentToolActionCommandWorkerReceiptRequest(
                "cmd-worker-001",
                9101L,
                9201L,
                "agent-command-worker",
                10L,
                20L,
                30L,
                sideEffectExecuted ? "SUCCEEDED" : "RUNNING",
                outcome,
                preCheckPassed,
                sideEffectStarted,
                sideEffectExecuted,
                safetyDecision,
                "command-safety-policy.v1",
                issueCodes,
                30,
                4096,
                sideEffectExecuted ? "MINIO_OBJECT" : null,
                sideEffectExecuted ? "agent-artifact:run-command/receipt-001" : null,
                sideEffectExecuted,
                null,
                "audit-command-worker-001",
                "command.run-program",
                "task-management-worker",
                sideEffectExecuted ? "EXECUTION_RESULT" : "PRECHECK_ONLY",
                "受控命令 worker 已写回低敏执行事实。",
                List.of("确认任务中心状态与 artifact 元数据已经对账"),
                "command-worker:cmd-worker-001:" + outcome
        );
    }
}
