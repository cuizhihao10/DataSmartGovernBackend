/**
 * @Author : Cui
 * @Date: 2026/06/23 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控命令 worker 回执服务测试。
 *
 * <p>这组测试保护 command outbox 主链路的一个关键收敛点：真实 worker 可以回写“执行成功/失败/阻断”等低敏事实，
 * 但不能借回执接口泄露命令行、stdout/stderr、真实路径、SQL、prompt、payload body、模型输出或内部 endpoint。
 * 只有先领取 Java 控制面 lease，并且安全预检明确允许受控执行时，回执才可以声明 sideEffectExecuted=true。</p>
 */
class AgentToolActionCommandWorkerReceiptServiceTest {

    private static final String SESSION_ID = "session-command";
    private static final String RUN_ID = "run-command";
    private static final String COMMAND_ID = "cmd-worker-001";
    private static final String EXECUTOR_ID = "agent-command-worker";

    @Test
    void shouldAppendCommandWorkerReceiptIntoProjectionDisplayAndIndex() throws JsonProcessingException {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionWorkerReceiptIndexStore indexStore =
                new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        AgentToolActionWorkerReceiptIndexService indexService =
                new AgentToolActionWorkerReceiptIndexService(indexStore);
        AgentCommandWorkerLeaseService leaseService = leaseService();
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService, EXECUTOR_ID);
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(projectionStore, indexService, leaseService);

        AgentToolActionCommandWorkerReceiptResponse response =
                service.receive(SESSION_ID, RUN_ID, "trace-command-worker", successRequest(lease));

        assertTrue(response.accepted());
        assertFalse(response.duplicate());
        assertEquals(AgentToolActionCommandWorkerReceiptService.EVENT_TYPE, response.eventType());
        assertEquals("EXECUTION_SUCCEEDED", response.outcome());
        assertTrue(response.sideEffectExecuted());

        AgentRuntimeEventProjectionRecord record = projectionStore.listByRunId(RUN_ID).getFirst();
        assertEquals(AgentToolActionCommandWorkerReceiptService.EVENT_TYPE, record.eventType());
        assertEquals("command_worker_execution_succeeded", record.stage());
        assertEquals("TASK_MANAGEMENT_WORKER", record.source());
        assertEquals("trace-command-worker", record.requestId());
        assertEquals(COMMAND_ID, record.attributes().get("commandId"));
        assertEquals(true, record.attributes().get("preCheckPassed"));
        assertEquals(true, record.attributes().get("sideEffectStarted"));
        assertEquals(true, record.attributes().get("sideEffectExecuted"));
        assertEquals(true, record.attributes().get("workerLeaseRequired"));
        assertEquals(true, record.attributes().get("workerLeaseTokenPresent"));
        assertEquals(lease.leaseVersion(), record.attributes().get("workerLeaseVersion"));
        assertEquals(lease.leaseExpiresAt().toEpochMilli(), record.attributes().get("workerLeaseExpiresAtMs"));
        assertTrue(String.valueOf(record.attributes().get("workerLeaseTokenDigest")).startsWith("sha256:"));
        assertEquals("ALLOW_CONTROLLED_EXECUTION", record.attributes().get("commandSafetyDecision"));
        assertEquals("agent-artifact:run-command/receipt-001", record.attributes().get("artifactReference"));
        assertEquals(AgentToolActionCommandWorkerReceiptService.PAYLOAD_POLICY,
                record.attributes().get("eventPayloadPolicy"));

        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(record);
        assertEquals("TOOL_ACTION_COMMAND_WORKER_RECEIPT", display.category());
        assertEquals("SIDE_EFFECT_CONFIRMED", display.status());
        assertFalse(display.requiresAttention());
        assertEquals(true, display.metrics().get("sideEffectExecuted"));
        assertEquals(true, display.metrics().get("workerLeaseTokenPresent"));
        assertEquals((int) lease.leaseVersion(), display.metrics().get("workerLeaseVersion"));

        List<AgentToolActionWorkerReceiptIndexRecord> indexRecords = indexStore.queryByCommandId(
                new AgentToolActionWorkerReceiptIndexQuery(
                        COMMAND_ID,
                        "command.run-program",
                        "10",
                        "20",
                        "30",
                        RUN_ID,
                        SESSION_ID,
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
        assertFalse(json.contains(lease.fencingToken()));
    }

    @Test
    void duplicateCommandWorkerReceiptShouldBeAcceptedAsIdempotentReplay() {
        AgentCommandWorkerLeaseService leaseService = leaseService();
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService, EXECUTOR_ID);
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(
                        new InMemoryAgentRuntimeEventProjectionStore(10, 100),
                        new AgentToolActionWorkerReceiptIndexService(
                                new InMemoryAgentToolActionWorkerReceiptIndexStore(100)),
                        leaseService
                );

        service.receive(SESSION_ID, RUN_ID, "trace-command-worker", successRequest(lease));
        AgentToolActionCommandWorkerReceiptResponse duplicate =
                service.receive(SESSION_ID, RUN_ID, "trace-command-worker", successRequest(lease));

        assertTrue(duplicate.accepted());
        assertTrue(duplicate.duplicate());
        assertEquals("EXECUTION_SUCCEEDED", duplicate.outcome());
    }

    @Test
    void sideEffectExecutedShouldRequireAllowControlledExecutionDecision() {
        AgentCommandWorkerLeaseService leaseService = leaseService();
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService, EXECUTOR_ID);
        AgentToolActionCommandWorkerReceiptService service =
                receiptServiceWithLease(leaseService);
        AgentToolActionCommandWorkerReceiptRequest request = request(
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                "REQUIRES_HUMAN_APPROVAL",
                List.of(),
                lease
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive(SESSION_ID, RUN_ID, "trace-command-worker", request));
    }

    @Test
    void sideEffectExecutedShouldRejectOpenSafetyIssueCodes() {
        AgentCommandWorkerLeaseService leaseService = leaseService();
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService, EXECUTOR_ID);
        AgentToolActionCommandWorkerReceiptService service =
                receiptServiceWithLease(leaseService);
        AgentToolActionCommandWorkerReceiptRequest request = request(
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of("NETWORK_REQUIRES_APPROVAL"),
                lease
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive(SESSION_ID, RUN_ID, "trace-command-worker", request));
    }

    @Test
    void sideEffectExecutedShouldRequireWorkerLeaseEvidence() {
        AgentToolActionCommandWorkerReceiptService service =
                receiptServiceWithLease(leaseService());
        AgentToolActionCommandWorkerReceiptRequest request = request(
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of(),
                null
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive(SESSION_ID, RUN_ID, "trace-command-worker", request));
    }

    @Test
    void sideEffectExecutedShouldRejectLeaseHeldByDifferentExecutor() {
        AgentCommandWorkerLeaseService leaseService = leaseService();
        AgentCommandWorkerLeaseRecord otherWorkerLease = claimLease(leaseService, "agent-command-worker-other");
        AgentToolActionCommandWorkerReceiptService service =
                receiptServiceWithLease(leaseService);
        AgentToolActionCommandWorkerReceiptRequest request = request(
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of(),
                otherWorkerLease
        );

        assertThrows(PlatformBusinessException.class,
                () -> service.receive(SESSION_ID, RUN_ID, "trace-command-worker", request));
    }

    @Test
    void failedPrecheckShouldEnterBlockedDisplayWithoutSideEffect() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(store);

        service.receive(SESSION_ID, RUN_ID, "trace-command-worker",
                request("FAILED_PRECHECK", false, false, false, "BLOCKED", List.of(), null));

        AgentRuntimeEventProjectionRecord record = store.listByRunId(RUN_ID).getFirst();
        assertEquals("command_worker_precheck_failed", record.stage());
        assertEquals(false, record.attributes().get("sideEffectExecuted"));
        AgentRuntimeEventDisplayView display = new AgentRuntimeEventDisplaySupport().buildDisplay(record);
        assertEquals("BLOCKED_BEFORE_SIDE_EFFECT", display.status());
        assertTrue(display.requiresAttention());
    }

    @Test
    void ragQueryCompletedShouldUseReadOnlyReceiptModeWithoutWorkerLease() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionWorkerReceiptIndexStore indexStore =
                new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        AgentToolActionWorkerReceiptIndexService indexService =
                new AgentToolActionWorkerReceiptIndexService(indexStore);
        AgentToolActionCommandWorkerReceiptService service =
                new AgentToolActionCommandWorkerReceiptService(projectionStore, indexService, leaseService());

        AgentToolActionCommandWorkerReceiptResponse response = service.receive(
                "session-rag-001",
                "run-rag-001",
                "trace-rag-receipt",
                ragQueryCompletedRequest()
        );

        assertTrue(response.accepted());
        assertFalse(response.sideEffectExecuted());
        assertEquals("RAG_QUERY_COMPLETED", response.outcome());

        AgentRuntimeEventProjectionRecord record = projectionStore.listByRunId("run-rag-001").getFirst();
        assertEquals("command_worker_rag_query_completed", record.stage());
        assertEquals(true, record.attributes().get("preCheckPassed"));
        assertEquals(false, record.attributes().get("sideEffectStarted"));
        assertEquals(false, record.attributes().get("sideEffectExecuted"));
        assertEquals(false, record.attributes().get("workerLeaseRequired"));
        assertEquals(false, record.attributes().get("workerLeaseTokenPresent"));
        assertEquals("AGENT_RAG_QUERY_COMPLETED", record.attributes().get("errorCode"));
        assertEquals("READ_ONLY_QUERY_SUMMARY", record.attributes().get("workerReceiptMode"));
        assertEquals("AGENT_RAG_ANSWER_ARTIFACT", record.attributes().get("artifactReferenceType"));
        assertEquals("knowledge.rag.query", record.attributes().get("toolCode"));

        List<AgentToolActionWorkerReceiptIndexRecord> indexRecords = indexStore.queryByCommandId(
                new AgentToolActionWorkerReceiptIndexQuery(
                        "cmd-rag-001",
                        "knowledge.rag.query",
                        "10",
                        "20",
                        "30",
                        "run-rag-001",
                        "session-rag-001",
                        List.of("20"),
                        10
                )
        );
        assertEquals(1, indexRecords.size());
        assertEquals("RAG_QUERY_COMPLETED", indexRecords.getFirst().outcome());
        assertEquals(false, indexRecords.getFirst().sideEffectExecuted());
    }

    @Test
    void sensitiveMessageShouldBeRejectedBeforeEnteringTimeline() {
        AgentCommandWorkerLeaseService leaseService = leaseService();
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService, EXECUTOR_ID);
        AgentToolActionCommandWorkerReceiptService service =
                receiptServiceWithLease(leaseService);
        AgentToolActionCommandWorkerReceiptRequest request = new AgentToolActionCommandWorkerReceiptRequest(
                COMMAND_ID,
                9101L,
                9201L,
                EXECUTOR_ID,
                10L,
                20L,
                30L,
                "RUNNING",
                "EXECUTION_FAILED",
                true,
                true,
                false,
                true,
                lease.fencingToken(),
                lease.leaseVersion(),
                lease.leaseExpiresAt().toEpochMilli(),
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
                () -> service.receive(SESSION_ID, RUN_ID, "trace-command-worker", request));
    }

    @Test
    void unsafeArtifactReferenceShouldBeRejected() {
        AgentCommandWorkerLeaseService leaseService = leaseService();
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService, EXECUTOR_ID);
        AgentToolActionCommandWorkerReceiptService service =
                receiptServiceWithLease(leaseService);
        AgentToolActionCommandWorkerReceiptRequest request = new AgentToolActionCommandWorkerReceiptRequest(
                COMMAND_ID,
                9101L,
                9201L,
                EXECUTOR_ID,
                10L,
                20L,
                30L,
                "RUNNING",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                true,
                lease.fencingToken(),
                lease.leaseVersion(),
                lease.leaseExpiresAt().toEpochMilli(),
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
                () -> service.receive(SESSION_ID, RUN_ID, "trace-command-worker", request));
    }

    private AgentToolActionCommandWorkerReceiptRequest successRequest(AgentCommandWorkerLeaseRecord lease) {
        return request("EXECUTION_SUCCEEDED", true, true, true,
                "ALLOW_CONTROLLED_EXECUTION", List.of(), lease);
    }

    private AgentToolActionCommandWorkerReceiptRequest ragQueryCompletedRequest() {
        return new AgentToolActionCommandWorkerReceiptRequest(
                "cmd-rag-001",
                null,
                null,
                "python-rag-query-worker",
                10L,
                20L,
                30L,
                "SUCCEEDED",
                "RAG_QUERY_COMPLETED",
                true,
                false,
                false,
                false,
                null,
                null,
                null,
                "ALLOW_READ_ONLY_RAG_QUERY",
                "rag-policy.v1",
                List.of(),
                0,
                0,
                "AGENT_RAG_ANSWER_ARTIFACT",
                "agent-artifact:run-rag-001/cmd-rag-001/rag-answer",
                true,
                null,
                "rag-query:sha256:abcdef123456",
                "knowledge.rag.query",
                "python-ai-runtime-rag",
                null,
                null,
                List.of("通过 artifact grant 读取答案正文。"),
                "rag-worker:run-rag-001:cmd-rag-001"
        );
    }

    private AgentToolActionCommandWorkerReceiptRequest request(String outcome,
                                                              boolean preCheckPassed,
                                                              boolean sideEffectStarted,
                                                              boolean sideEffectExecuted,
                                                              String safetyDecision,
                                                              List<String> issueCodes,
                                                              AgentCommandWorkerLeaseRecord lease) {
        boolean leaseRequired = sideEffectStarted || sideEffectExecuted;
        return new AgentToolActionCommandWorkerReceiptRequest(
                COMMAND_ID,
                9101L,
                9201L,
                EXECUTOR_ID,
                10L,
                20L,
                30L,
                sideEffectExecuted ? "SUCCEEDED" : "RUNNING",
                outcome,
                preCheckPassed,
                sideEffectStarted,
                sideEffectExecuted,
                leaseRequired,
                leaseRequired && lease != null ? lease.fencingToken() : null,
                leaseRequired && lease != null ? lease.leaseVersion() : null,
                leaseRequired && lease != null ? lease.leaseExpiresAt().toEpochMilli() : null,
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

    private AgentCommandWorkerLeaseService leaseService() {
        return new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore());
    }

    private AgentCommandWorkerLeaseRecord claimLease(AgentCommandWorkerLeaseService leaseService, String executorId) {
        AgentCommandWorkerLeaseClaimResult result = leaseService.claim(SESSION_ID, RUN_ID,
                new AgentCommandWorkerLeaseClaimRequest(COMMAND_ID, executorId, 10L, 20L, 30L, 120));
        assertTrue(result.acquired());
        assertNotNull(result.record());
        assertNotNull(result.record().fencingToken());
        return result.record();
    }

    private AgentToolActionCommandWorkerReceiptService receiptServiceWithLease(
            AgentCommandWorkerLeaseService leaseService) {
        return new AgentToolActionCommandWorkerReceiptService(
                new InMemoryAgentRuntimeEventProjectionStore(10, 100),
                new AgentToolActionWorkerReceiptIndexService(
                        new InMemoryAgentToolActionWorkerReceiptIndexStore(100)),
                leaseService
        );
    }
}
