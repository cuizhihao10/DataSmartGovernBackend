/**
 * @Author : Cui
 * @Date: 2026/07/22 21:00
 * @Description DataSmart Govern Backend - SyncFailureRecoveryToolAdapterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.sun.net.httpserver.HttpServer;
import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Verifies that recovery actions consume server-created same-session references. */
class SyncFailureRecoveryToolAdapterTest {

    /**
     * Verifies the safe selector default used when Recovery knows the failed execution but intentionally
     * does not expose dirty-row IDs to the model. The adapter may broaden only this read-only preview to
     * all retryable samples; the downstream service keeps the 500-row cap and any later apply still needs
     * an approval bound to the preview digest.
     */
    @Test
    void shouldPreviewAllRetryableSamplesWhenNoExplicitDirtySampleIdsWereProposed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore store = new AgentToolExecutionOutputStore();
        store.save(snapshot("audit-diagnosis", SyncFailureRecoveryToolAdapter.DIAGNOSE), Map.of(
                "taskId", 31L,
                "executionId", 373L
        ));
        SyncFailureRecoveryToolAdapter adapter = adapter(builder, store);

        server.expect(once(), requestTo("http://data-sync.test/sync-tasks/31/errors/quarantine/preview"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.executionId").value(373))
                .andExpect(jsonPath("$.quarantineAllRetryableInExecution").value(true))
                .andRespond(withSuccess(successEnvelope("""
                        {"taskId":31,"executionId":373,"selectedCount":2,"eligibleCount":2,
                         "affectedCount":0,"operationState":"PREVIEWED","issueCodes":[],
                         "message":"preview ready"}
                        """), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(
                SyncFailureRecoveryToolAdapter.DIRTY_QUARANTINE_PREVIEW,
                Map.of("diagnosisRef", reference(
                        SyncFailureRecoveryToolAdapter.DIAGNOSE, "audit-diagnosis", null))));

        assertTrue(outcome.success());
        assertEquals("PREVIEWED", outcome.output().get("operationState"));
        server.verify();
    }

    @Test
    void shouldSearchRagWithDiagnosisGeneratedQuestion() throws Exception {
        /*
         * MockRestServiceServer can prove JSON serialization but cannot reveal an h2c upgrade. This raw
         * HTTP/1.1 endpoint observes the real request line and headers so the regression test covers the
         * exact container failure where Uvicorn saw an upgrade request followed by a body-less POST.
         */
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<ObservedHttpRequest> observedRequest = new AtomicReference<>();
        server.createContext("/agent/rag/query", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            observedRequest.set(new ObservedHttpRequest(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + exchange.getProtocol(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Upgrade"),
                    body));
            byte[] responseBody = """
                    {"answer":"先扩大目标字符字段，再重试失败对象。",
                     "citations":[{"documentId":"runbook-1"}],
                     "retrievalSummary":{"candidateCount":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        try {
            RestClient.Builder builder = RestClient.builder();
            AgentToolExecutionOutputStore store = new AgentToolExecutionOutputStore();
            store.save(snapshot("audit-diagnosis", SyncFailureRecoveryToolAdapter.DIAGNOSE), Map.of(
                    "taskId", 31L,
                    "executionId", 373L,
                    "ragQuery", "TARGET_COLUMN_TOO_NARROW PostgreSQL 安全修复案例"
            ));
            SyncFailureRecoveryToolAdapter adapter = adapter(
                    builder, store, "http://127.0.0.1:" + server.getAddress().getPort());

            AgentToolExecutionOutcome outcome = adapter.execute(context(
                    SyncFailureRecoveryToolAdapter.RAG_LOOKUP,
                    Map.of("diagnosisRef", reference(
                            SyncFailureRecoveryToolAdapter.DIAGNOSE, "audit-diagnosis", null))));
            ObservedHttpRequest request = observedRequest.get();

            assertTrue(outcome.success(), () -> outcome.errorCode() + ": " + outcome.message());
            assertEquals("先扩大目标字符字段，再重试失败对象。", outcome.output().get("answer"));
            assertTrue(request != null, "真实 HTTP 端点应当观察到 RAG 请求");
            assertEquals("POST /agent/rag/query HTTP/1.1", request.requestLine());
            assertTrue(request.contentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
            assertNull(request.upgrade());
            assertTrue(request.body().contains("\"tenantId\":10"));
            assertTrue(request.body().contains("\"projectId\":101"));
            assertTrue(request.body().contains("TARGET_COLUMN_TOO_NARROW PostgreSQL 安全修复案例"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldApplyOnlyDigestBoundQuarantinePreview() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore store = new AgentToolExecutionOutputStore();
        store.save(snapshot("audit-preview", SyncFailureRecoveryToolAdapter.DIRTY_QUARANTINE_PREVIEW), Map.of(
                "taskId", 31L,
                "executionId", 373L,
                "selectedSampleIds", List.of(91L, 92L),
                "confirmationDigest", "digest-preview-001"
        ));
        SyncFailureRecoveryToolAdapter adapter = adapter(builder, store);

        server.expect(once(), requestTo("http://data-sync.test/sync-tasks/31/errors/quarantine/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.executionId").value(373))
                .andExpect(jsonPath("$.errorSampleIds[0]").value(91))
                .andExpect(jsonPath("$.confirmationDigest").value("digest-preview-001"))
                .andExpect(jsonPath("$.confirmed").value(true))
                .andRespond(withSuccess(successEnvelope("""
                        {"taskId":31,"executionId":373,"selectedCount":2,"eligibleCount":2,
                         "affectedCount":2,"operationState":"APPLIED","issueCodes":[],
                         "message":"已隔离"}
                        """), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(
                SyncFailureRecoveryToolAdapter.DIRTY_QUARANTINE_APPLY,
                Map.of("previewRef", reference(
                        SyncFailureRecoveryToolAdapter.DIRTY_QUARANTINE_PREVIEW, "audit-preview", null))));

        assertTrue(outcome.success());
        assertEquals(2, outcome.output().get("affectedCount"));
        server.verify();
    }

    private SyncFailureRecoveryToolAdapter adapter(RestClient.Builder builder,
                                                   AgentToolExecutionOutputStore store) {
        return adapter(builder, store, "http://python-ai-runtime.test");
    }

    private SyncFailureRecoveryToolAdapter adapter(RestClient.Builder builder,
                                                   AgentToolExecutionOutputStore store,
                                                   String pythonRuntimeBaseUrl) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("data-sync", "http://data-sync.test");
        properties.getToolServiceBaseUrls().put("datasource-management", "http://datasource.test");
        properties.getToolServiceBaseUrls().put("python-ai-runtime", pythonRuntimeBaseUrl);
        return new SyncFailureRecoveryToolAdapter(
                builder,
                new AgentToolDownstreamHttpSupport(properties),
                new AgentToolOutputReferenceResolver(store));
    }

    /** Low-sensitive transport facts captured by the raw contract endpoint. */
    private record ObservedHttpRequest(String requestLine, String contentType, String upgrade, String body) {
    }

    private AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot snapshot(String auditId, String toolCode) {
        return new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                "session-001", "run-recovery", auditId, toolCode);
    }

    private Map<String, Object> reference(String toolCode, String auditId, String path) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("fromTool", toolCode);
        reference.put("fromAuditId", auditId);
        if (path != null) {
            reference.put("path", path);
        }
        return reference;
    }

    private AgentToolExecutionContext context(String toolCode, Map<String, Object> planArguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-001", 10L, 101L, null, "1001",
                "PROJECT_OWNER", "USER", "101:OWNER",
                "WEB", "恢复失败同步任务", WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101", LocalDateTime.now());
        AgentRunRecord run = new AgentRunRecord(
                "run-recovery", "session-001", AgentRunState.PLANNING,
                "AGENT_REASONING", "恢复失败同步任务", true, false,
                List.of(), Map.of(), LocalDateTime.now(), "同步恢复测试");
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-current", "session-001", "run-recovery", "binding-current",
                toolCode, "DATA_SYNC", "data-sync", "/sync-tasks", null,
                10L, 101L, null, "1001", "HIGH", "APPROVAL_REQUIRED", true,
                false, false, List.of("RECOVER"), "测试同步恢复工具",
                planArguments, Map.of("projectScoped", true), Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED, "trace-recovery", "同步恢复测试", LocalDateTime.now());
        return new AgentToolExecutionContext(session, run, audit, Map.of(), "trace-recovery");
    }

    private String successEnvelope(String data) {
        return "{\"code\":0,\"message\":\"success\",\"data\":" + data + "}";
    }
}
