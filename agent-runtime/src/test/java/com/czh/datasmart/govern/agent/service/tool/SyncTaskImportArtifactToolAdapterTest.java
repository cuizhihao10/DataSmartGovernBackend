/**
 * @Author : Cui
 * @Date: 2026/07/22 19:10
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactToolAdapterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Verifies literal upload references and same-session durable output references. */
class SyncTaskImportArtifactToolAdapterTest {

    @Test
    void shouldExecuteFirstDryRunWithBrowserCreatedArtifactReference() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SyncTaskImportArtifactToolAdapter adapter = adapter(builder, new AgentToolExecutionOutputStore());

        server.expect(once(), requestTo(
                        "http://data-sync.test/sync-task-import-artifacts/sync-import-001/dry-run?runImmediately=false"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(successEnvelope("""
                        {"artifact":{"artifactRef":"sync-import-001","versionNumber":1},
                         "repairRequired":true,"confirmationDigest":"digest-001"}
                        """), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(
                SyncTaskImportArtifactToolAdapter.DRY_RUN,
                Map.of("artifactRef", "sync-import-001", "runImmediately", false)));

        assertTrue(outcome.success());
        assertEquals(true, outcome.output().get("repairRequired"));
        server.verify();
    }

    @Test
    void shouldResolveRepairArgumentsFromPriorRunInSameSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001", "run-dry", "audit-dry", SyncTaskImportArtifactToolAdapter.DRY_RUN),
                Map.of(
                        "artifact", Map.of("artifactRef", "sync-import-001", "versionNumber", 1),
                        "confirmationDigest", "digest-001"
                ));
        SyncTaskImportArtifactToolAdapter adapter = adapter(builder, outputStore);

        server.expect(once(), requestTo(
                        "http://data-sync.test/sync-task-import-artifacts/sync-import-001/repairs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.confirmationDigest").value("digest-001"))
                .andExpect(jsonPath("$.patches[0].rowNumber").value(2))
                .andExpect(jsonPath("$.patches[0].columnName").value("name"))
                .andRespond(withSuccess(successEnvelope("""
                        {"artifactRef":"sync-import-002","parentArtifactRef":"sync-import-001",
                         "versionNumber":2,"artifactState":"REPAIRED"}
                        """), MediaType.APPLICATION_JSON));

        Map<String, Object> reference = Map.of(
                "fromTool", SyncTaskImportArtifactToolAdapter.DRY_RUN,
                "fromAuditId", "audit-dry"
        );
        AgentToolExecutionOutcome outcome = adapter.execute(context(
                SyncTaskImportArtifactToolAdapter.APPLY_REPAIR,
                Map.of(
                        "artifactRef", withPath(reference, "artifact.artifactRef"),
                        "baseVersion", withPath(reference, "artifact.versionNumber"),
                        "confirmationDigest", withPath(reference, "confirmationDigest"),
                        "patches", List.of(Map.of(
                                "rowNumber", 2,
                                "columnName", "name",
                                "replacementValue", "fixed-name"
                        ))
                )));

        assertTrue(outcome.success());
        assertEquals("sync-import-002", outcome.output().get("artifactRef"));
        server.verify();
    }

    private SyncTaskImportArtifactToolAdapter adapter(RestClient.Builder builder,
                                                       AgentToolExecutionOutputStore outputStore) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("data-sync", "http://data-sync.test");
        properties.getToolServiceBaseUrls().put("python-ai-runtime", "http://python-ai-runtime.test");
        return new SyncTaskImportArtifactToolAdapter(
                builder,
                new AgentToolDownstreamHttpSupport(properties),
                new AgentToolOutputReferenceResolver(outputStore)
        );
    }

    private AgentToolExecutionContext context(String toolCode, Map<String, Object> planArguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-001", 10L, 101L, null, "1001",
                "PROJECT_OWNER", "USER", "101:OWNER",
                "WEB", "处理任务导入文件", WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101", LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-current", "session-001", AgentRunState.PLANNING,
                "AGENT_REASONING", "处理任务导入文件", true, false,
                List.of(), Map.of(), LocalDateTime.now(), "任务导入测试"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-current", "session-001", "run-current", "binding-current",
                toolCode, "DATA_SYNC", "data-sync", "/sync-task-import-artifacts", null,
                10L, 101L, null, "1001", "HIGH", "APPROVAL_REQUIRED", true,
                false, false, List.of("MODIFY_ARTIFACT"), "测试任务导入工具",
                planArguments, Map.of("projectScoped", true), Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED, "trace-import", "任务导入测试", LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, Map.of(), "trace-import");
    }

    private Map<String, Object> withPath(Map<String, Object> reference, String path) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>(reference);
        copy.put("path", path);
        return copy;
    }

    private String successEnvelope(String data) {
        return "{\"code\":0,\"message\":\"success\",\"data\":" + data + "}";
    }
}
