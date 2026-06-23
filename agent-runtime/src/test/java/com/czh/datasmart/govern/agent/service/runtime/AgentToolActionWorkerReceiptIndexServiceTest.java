/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker receipt 专用索引测试。
 *
 * <p>这组测试保护 5.80 阶段新增的恢复事实源模型：
 * receipt 可以从通用 runtime event projection 被物化到低敏索引；
 * fact bundle 查询优先消费索引；
 * 索引必须执行租户/项目/actor/run/session 过滤；
 * 索引记录和响应都不能泄露 message、payload、SQL、prompt 或工具参数。</p>
 */
class AgentToolActionWorkerReceiptIndexServiceTest {

    @Test
    void shouldMaterializeLowSensitiveReceiptIndexWithoutMessageOrPayload() throws JsonProcessingException {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        AgentToolActionWorkerReceiptIndexService service = new AgentToolActionWorkerReceiptIndexService(store);

        boolean indexed = service.materialize(receiptProjection("receipt-ready", "20", true, "DRY_RUN_PASSED", 7L));

        assertTrue(indexed);
        List<AgentToolActionWorkerReceiptIndexRecord> records = store.queryByCommandId(indexQuery("20"));
        assertEquals(1, records.size());
        AgentToolActionWorkerReceiptIndexRecord record = records.getFirst();
        assertEquals("taoc-resume-001", record.commandId());
        assertEquals("DRY_RUN_PASSED", record.outcome());
        assertEquals(7L, record.replaySequence());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(record);
        assertFalse(json.contains("prompt raw prompt should not leak"));
        assertFalse(json.contains("select * from sensitive_table"));
        assertFalse(json.contains("payloadBody"));
        assertFalse(json.contains("secret"));
    }

    @Test
    void shouldMaterializeCommandWorkerReceiptWithConfirmedSideEffect() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        AgentToolActionWorkerReceiptIndexService service = new AgentToolActionWorkerReceiptIndexService(store);

        boolean indexed = service.materialize(commandWorkerReceiptProjection());

        assertTrue(indexed);
        List<AgentToolActionWorkerReceiptIndexRecord> records = store.queryByCommandId(indexQuery("20"));
        assertEquals(1, records.size());
        AgentToolActionWorkerReceiptIndexRecord record = records.getFirst();
        assertEquals("taoc-resume-001", record.commandId());
        assertEquals("EXECUTION_SUCCEEDED", record.outcome());
        assertEquals(true, record.preCheckPassed());
        assertEquals(true, record.sideEffectExecuted());
        assertEquals(17L, record.replaySequence());
    }

    @Test
    void factEvaluatorShouldReadReceiptFromIndexWhenProjectionHotWindowIsEmpty() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(indexRecord("receipt-ready", "20", true, "DRY_RUN_PASSED", 9L));
        AgentToolActionWorkerReceiptIndexService indexService = new AgentToolActionWorkerReceiptIndexService(store);
        AgentToolActionWorkerReceiptFactEvaluator evaluator = new AgentToolActionWorkerReceiptFactEvaluator(
                indexService,
                new InMemoryAgentRuntimeEventProjectionStore(10, 100)
        );

        AgentToolActionWorkerReceiptFactEvaluation evaluation = evaluator.evaluate(
                request(),
                scopedQuery("20"),
                Instant.parse("2026-06-17T00:00:00Z"),
                10
        );

        assertEquals("WORKER_RECEIPT_PROJECTION", evaluation.fact().factType());
        assertEquals("WORKER_RECEIPT_LOW_SENSITIVE_INDEX", evaluation.fact().source());
        assertEquals("AVAILABLE", evaluation.fact().status());
        assertEquals(true, evaluation.fact().available());
        assertEquals(false, evaluation.fact().rejected());
        assertEquals(1, evaluation.summary().receiptCount());
        assertEquals("DRY_RUN_PASSED", evaluation.summary().latestOutcome());
        assertEquals(9L, evaluation.summary().latestReplaySequence());
    }

    @Test
    void projectScopeShouldHideCrossProjectReceiptIndexRecord() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(indexRecord("receipt-cross-project", "99", true, "DRY_RUN_PASSED", 10L));
        AgentToolActionWorkerReceiptIndexService indexService = new AgentToolActionWorkerReceiptIndexService(store);
        AgentToolActionWorkerReceiptFactEvaluator evaluator = new AgentToolActionWorkerReceiptFactEvaluator(
                indexService,
                new InMemoryAgentRuntimeEventProjectionStore(10, 100)
        );

        AgentToolActionWorkerReceiptFactEvaluation evaluation = evaluator.evaluate(
                request(),
                scopedQuery("20"),
                Instant.parse("2026-06-17T00:00:00Z"),
                10
        );

        assertEquals("MISSING", evaluation.fact().status());
        assertEquals(false, evaluation.fact().available());
        assertTrue(evaluation.fact().issueCodes().contains("WORKER_RECEIPT_NOT_FOUND"));
        assertEquals(0, evaluation.summary().receiptCount());
    }

    private AgentToolActionWorkerReceiptIndexQuery indexQuery(String projectId) {
        return new AgentToolActionWorkerReceiptIndexQuery(
                "taoc-resume-001",
                "datasource.metadata.read",
                "10",
                projectId,
                "1001",
                "run-resume",
                "session-resume",
                List.of(projectId),
                10
        );
    }

    private AgentRuntimeEventProjectionQuery scopedQuery(String projectId) {
        return new AgentRuntimeEventProjectionQuery(
                "10",
                projectId,
                "1001",
                null,
                "run-resume",
                "session-resume",
                null,
                null,
                10,
                List.of(projectId)
        );
    }

    private AgentToolActionResumeFactBundleQueryRequest request() {
        return new AgentToolActionResumeFactBundleQueryRequest(
                "checkpoint-resume-001",
                "thread-resume-001",
                "session-resume",
                "run-resume",
                "taoc-resume-001",
                null,
                null,
                null,
                "datasource.metadata.read",
                "tool-readiness-policy.v1",
                10L,
                20L,
                "1001",
                List.of("WORKER_RECEIPT_PROJECTION"),
                true,
                true
        );
    }

    private AgentToolActionWorkerReceiptIndexRecord indexRecord(String identityKey,
                                                                String projectId,
                                                                boolean preCheckPassed,
                                                                String outcome,
                                                                long replaySequence) {
        return new AgentToolActionWorkerReceiptIndexRecord(
                identityKey,
                "taoc-resume-001",
                "10",
                projectId,
                "1001",
                "run-resume",
                "session-resume",
                "datasource.metadata.read",
                preCheckPassed ? "RUNNING" : "FAILED",
                outcome,
                preCheckPassed,
                false,
                preCheckPassed ? null : "AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED",
                replaySequence,
                Instant.parse("2026-06-16T00:00:00Z").plusSeconds(replaySequence),
                Instant.parse("2026-06-17T00:00:00Z")
        );
    }

    private AgentRuntimeEventProjectionRecord receiptProjection(String identityKey,
                                                                String projectId,
                                                                boolean preCheckPassed,
                                                                String outcome,
                                                                long replaySequence) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("commandId", "taoc-resume-001");
        attributes.put("toolCode", "datasource.metadata.read");
        attributes.put("taskStatus", preCheckPassed ? "RUNNING" : "FAILED");
        attributes.put("outcome", outcome);
        attributes.put("preCheckPassed", preCheckPassed);
        attributes.put("sideEffectExecuted", false);
        attributes.put("errorCode", preCheckPassed ? null : "AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED");
        attributes.put("message", "prompt raw prompt should not leak; select * from sensitive_table");
        attributes.put("payloadBody", Map.of("secret", "hidden"));
        Instant timestamp = Instant.parse("2026-06-16T00:00:00Z").plusSeconds(replaySequence);
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "TASK_MANAGEMENT",
                AgentToolActionControlledDryRunReceiptService.EVENT_TYPE,
                "controlled_tool_action_receipt",
                "receipt",
                preCheckPassed ? "audit" : "warning",
                "10",
                projectId,
                "1001",
                "trace-resume",
                "run-resume",
                "session-resume",
                replaySequence,
                replaySequence,
                timestamp,
                timestamp,
                timestamp,
                attributes
        );
    }

    private AgentRuntimeEventProjectionRecord commandWorkerReceiptProjection() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("commandId", "taoc-resume-001");
        attributes.put("toolCode", "datasource.metadata.read");
        attributes.put("taskStatus", "SUCCEEDED");
        attributes.put("outcome", "EXECUTION_SUCCEEDED");
        attributes.put("preCheckPassed", true);
        attributes.put("sideEffectExecuted", true);
        attributes.put("errorCode", "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED");
        attributes.put("message", "低敏执行成功摘要");
        attributes.put("artifactReference", "agent-artifact:run-resume/receipt-001");
        Instant timestamp = Instant.parse("2026-06-17T00:00:17Z");
        return new AgentRuntimeEventProjectionRecord(
                "command-worker-receipt-ready",
                "agent-runtime-event.v1",
                "TASK_MANAGEMENT_WORKER",
                AgentToolActionCommandWorkerReceiptService.EVENT_TYPE,
                "command_worker_execution_succeeded",
                "receipt",
                "audit",
                "10",
                "20",
                "1001",
                "trace-resume",
                "run-resume",
                "session-resume",
                17L,
                17L,
                timestamp,
                timestamp,
                timestamp,
                attributes
        );
    }
}
