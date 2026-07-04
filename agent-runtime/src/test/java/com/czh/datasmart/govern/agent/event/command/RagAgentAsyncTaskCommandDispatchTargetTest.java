/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - RagAgentAsyncTaskCommandDispatchTargetTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerCallResult;
import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerClient;
import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerReceiptIngestionService;
import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerRunRequest;
import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerRunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * RAG command outbox target 测试。
 *
 * <p>这组测试保护的是 Java/Python RAG worker 闭环里最容易出错的边界：
 * 1. `knowledge.rag.query` 不能误投给 task-management；
 * 2. question 只能作为短生命周期 arguments 进入 Python；
 * 3. queryRef、artifactReference、LangGraph threadId 等控制面字段必须走白名单；
 * 4. Python 未接受或缺少低敏 payloadPolicy 时，dispatcher 必须失败重试，而不能把 outbox 错标为 PUBLISHED。</p>
 */
class RagAgentAsyncTaskCommandDispatchTargetTest {

    @Test
    void shouldOnlySupportRagCommands() {
        RagAgentAsyncTaskCommandDispatchTarget target = target(request -> accepted());

        assertTrue(target.supports(record(RagAgentAsyncTaskCommandDispatchTarget.RAG_TOOL_CODE, ragPayload())));
        assertFalse(target.supports(record("data-sync.execute", "{}")));
    }

    @Test
    void shouldMapShortLivedQuestionAndLowSensitiveControlFacts() {
        RagAgentAsyncTaskCommandDispatchTarget target = target(request -> accepted());

        AgentRagCommandWorkerRunRequest request = target.toWorkerRequest(
                record(RagAgentAsyncTaskCommandDispatchTarget.RAG_TOOL_CODE, ragPayload())
        );

        assertEquals("DataSmart RAG 管线有哪些阶段？", request.arguments().get("question"));
        assertEquals(3, request.arguments().get("topK"));
        assertEquals(false, request.arguments().get("generateAnswer"));
        assertEquals("10", request.controlFacts().get("tenantId"));
        assertEquals("20", request.controlFacts().get("projectId"));
        assertEquals("30", request.controlFacts().get("workspaceKey"));
        assertEquals("cmd-rag-001", request.controlFacts().get("commandId"));
        assertEquals("rag-query:sha256:abcdef123456", request.controlFacts().get("queryRef"));
        assertEquals("rag-command-worker:run-rag-001:cmd-rag-001",
                request.controlFacts().get("langGraphThreadId"));
        assertEquals(false, request.postToJava());
        assertFalse(request.controlFacts().containsKey("answer"));
        assertFalse(request.controlFacts().containsKey("compressedContext"));
    }

    @Test
    void shouldDispatchAcceptedPythonWorkerResponseAndIngestReceipt() {
        AtomicReference<AgentRagCommandWorkerRunRequest> captured = new AtomicReference<>();
        AgentRagCommandWorkerReceiptIngestionService ingestionService =
                mock(AgentRagCommandWorkerReceiptIngestionService.class);
        RagAgentAsyncTaskCommandDispatchTarget target = new RagAgentAsyncTaskCommandDispatchTarget(
                request -> {
                    captured.set(request);
                    return accepted();
                },
                ingestionService,
                new ObjectMapper()
        );
        AgentAsyncTaskCommandOutboxRecord record =
                record(RagAgentAsyncTaskCommandDispatchTarget.RAG_TOOL_CODE, ragPayload());

        target.dispatch(record);

        assertEquals("knowledge.rag.query", captured.get().controlFacts().get("toolCode"));
        assertEquals(false, captured.get().postToJava());
        verify(ingestionService).ingest(record, accepted().response());
    }

    @Test
    void shouldThrowWhenQuestionIsMissingInsteadOfExecutingByQueryRefOnly() {
        RagAgentAsyncTaskCommandDispatchTarget target = target(request -> accepted());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> target.toWorkerRequest(record(
                        RagAgentAsyncTaskCommandDispatchTarget.RAG_TOOL_CODE,
                        """
                                {
                                  "arguments": {
                                    "queryRef": {
                                      "kind": "rag_query_ref",
                                      "queryDigest": "sha256:abcdef123456"
                                    }
                                  }
                                }
                                """
                ))
        );

        assertTrue(exception.getMessage().contains("question"));
        assertFalse(exception.getMessage().contains("abcdef123456"));
    }

    @Test
    void shouldThrowWhenPythonWorkerDidNotAcceptCommandWithoutLeakingQuestion() {
        RagAgentAsyncTaskCommandDispatchTarget target = target(request ->
                AgentRagCommandWorkerCallResult.failed(
                        503,
                        "RAG_COMMAND_WORKER_HTTP_STATUS_NOT_SUCCESSFUL",
                        "low-sensitive failure"
                )
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> target.dispatch(record(RagAgentAsyncTaskCommandDispatchTarget.RAG_TOOL_CODE, ragPayload()))
        );

        assertTrue(exception.getMessage().contains("RAG_COMMAND_WORKER_HTTP_STATUS_NOT_SUCCESSFUL"));
        assertFalse(exception.getMessage().contains("DataSmart RAG 管线有哪些阶段"));
    }

    private RagAgentAsyncTaskCommandDispatchTarget target(AgentRagCommandWorkerClient client) {
        return new RagAgentAsyncTaskCommandDispatchTarget(
                client,
                mock(AgentRagCommandWorkerReceiptIngestionService.class),
                new ObjectMapper()
        );
    }

    private AgentRagCommandWorkerCallResult accepted() {
        AgentRagCommandWorkerRunResponse response = new AgentRagCommandWorkerRunResponse(
                "datasmart.rag-command-worker-api.v1",
                true,
                Map.of(
                        "toolCode", "knowledge.rag.query",
                        "queryRef", "rag-query:sha256:abcdef123456"
                ),
                Map.of("outcome", "RAG_QUERY_COMPLETED"),
                javaReceiptPayload(),
                Map.of(),
                Map.of("threadId", "rag-command-worker:run-rag-001:cmd-rag-001"),
                "RAG_WORKER_SUMMARY_ONLY_NO_QUESTION_NO_ANSWER_NO_CONTEXT_NO_DOCUMENT_TEXT_NO_SOURCE_URI"
        );
        return AgentRagCommandWorkerCallResult.accepted(200, response, "accepted");
    }

    private Map<String, Object> javaReceiptPayload() {
        return Map.ofEntries(
                Map.entry("commandId", "cmd-rag-001"),
                Map.entry("executorId", "python-rag-query-worker"),
                Map.entry("tenantId", 10L),
                Map.entry("projectId", 20L),
                Map.entry("actorId", 1001L),
                Map.entry("taskStatus", "SUCCEEDED"),
                Map.entry("outcome", "RAG_QUERY_COMPLETED"),
                Map.entry("preCheckPassed", true),
                Map.entry("sideEffectStarted", false),
                Map.entry("sideEffectExecuted", false),
                Map.entry("workerLeaseRequired", false),
                Map.entry("commandSafetyDecision", "ALLOW_READ_ONLY_RAG_QUERY"),
                Map.entry("commandSafetyPolicyVersion", "rag-policy.v1"),
                Map.entry("commandSafetyIssueCodes", List.of()),
                Map.entry("normalizedTimeoutSeconds", 0),
                Map.entry("normalizedOutputByteLimitBytes", 0),
                Map.entry("artifactReferenceType", "AGENT_RAG_ANSWER_ARTIFACT"),
                Map.entry("artifactReference", "agent-artifact:run-rag-001/cmd-rag-001/rag-answer"),
                Map.entry("artifactAvailable", true),
                Map.entry("errorCode", "AGENT_RAG_QUERY_COMPLETED"),
                Map.entry("auditId", "rag-query:sha256:abcdef123456"),
                Map.entry("toolCode", "knowledge.rag.query"),
                Map.entry("targetService", "python-ai-runtime-rag"),
                Map.entry("workerReceiptMode", "READ_ONLY_QUERY_SUMMARY"),
                Map.entry("message", "RAG 查询已完成低敏回执"),
                Map.entry("recommendedActions", List.of("通过 artifact grant 读取答案正文")),
                Map.entry("idempotencyKey", "rag-worker:run-rag-001:cmd-rag-001")
        );
    }

    private AgentAsyncTaskCommandOutboxRecord record(String toolCode, String payloadJson) {
        return AgentAsyncTaskCommandOutboxRecord.pending(
                "cmd-rag-001",
                "idem-rag-001",
                "datasmart.agent.async-task-command.v1",
                "AGENT_RAG_QUERY_REQUESTED",
                "datasmart.agent.rag.commands",
                RagAgentAsyncTaskCommandDispatchTarget.RAG_CONSUMER_SERVICE,
                "session-rag-001",
                "run-rag-001",
                "audit-rag-001",
                toolCode,
                RagAgentAsyncTaskCommandDispatchTarget.RAG_CONSUMER_SERVICE,
                "/internal/agent/rag/command-worker/run",
                10L,
                20L,
                30L,
                "1001",
                "trace-rag-001",
                "agent-tool-audit://session-rag-001/run-rag-001/audit-rag-001/plan-arguments",
                payloadJson,
                payloadJson.length(),
                Instant.now()
        );
    }

    private String ragPayload() {
        return """
                {
                  "arguments": {
                    "question": "DataSmart RAG 管线有哪些阶段？",
                    "topK": 3,
                    "queryRef": {
                      "kind": "rag_query_ref",
                      "queryDigest": "sha256:abcdef123456"
                    }
                  },
                  "retrievalPolicyVersion": "rag-policy.v1"
                }
                """;
    }
}
