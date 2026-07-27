/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - CdcReadinessToolAdapterTest.java
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Protects trusted datasource derivation and fail-closed readiness reporting. */
class CdcReadinessToolAdapterTest {

    @Test
    void shouldDeriveDatasourceIdsAndReturnStructuredBlockers() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore store = new AgentToolExecutionOutputStore();
        store.save(snapshot("audit-source", DatasourceAccessToolAdapter.SOURCE_METADATA),
                Map.of("datasourceId", 11L, "metadata", Map.of()));
        store.save(snapshot("audit-target", DatasourceAccessToolAdapter.TARGET_METADATA),
                Map.of("datasourceId", 22L, "metadata", Map.of()));
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("datasource-management", "http://datasource.test");
        CdcReadinessToolAdapter adapter = new CdcReadinessToolAdapter(
                builder,
                new AgentToolDownstreamHttpSupport(properties),
                new AgentToolOutputReferenceResolver(store));

        server.expect(once(), requestTo(
                        "http://datasource.test/datasources/11/cdc-readiness/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.targetDatasourceId").value(22))
                .andExpect(jsonPath("$.sourceDatasourceId").doesNotExist())
                .andExpect(jsonPath("$.objectMappings[0].sourceObjectName").value("customer_source"))
                .andExpect(jsonPath("$.objectMappings[0].targetObjectName").value("customer_target"))
                .andRespond(withSuccess("""
                        {"code":0,"message":"success","data":{"schemaVersion":"datasmart.datasource.cdc-readiness.v1",
                         "ready":false,"decision":"BLOCKED","failedCount":1,
                         "issueCodes":["CDC_PIPELINE_RUNTIME_NOT_IMPLEMENTED"],
                         "recommendedActions":["补齐 CDC runtime"],"checks":[]}}
                        """, MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "sourceMetadataRef", reference(DatasourceAccessToolAdapter.SOURCE_METADATA, "audit-source"),
                "targetMetadataRef", reference(DatasourceAccessToolAdapter.TARGET_METADATA, "audit-target"),
                "objectMappings", List.of(Map.of(
                        "sourceObjectName", "customer_source",
                        "targetSchemaName", "public",
                        "targetObjectName", "customer_target")),
                "sourceDatasourceId", 999L,
                "targetDatasourceId", 999L
        )));

        assertTrue(outcome.success());
        assertFalse((Boolean) outcome.output().get("ready"));
        assertTrue(String.valueOf(outcome.message()).contains("阻断"));
        server.verify();
    }

    private Map<String, Object> reference(String toolCode, String auditId) {
        return Map.of("fromTool", toolCode, "fromAuditId", auditId);
    }

    private AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot snapshot(
            String auditId, String toolCode) {
        return new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                "session-cdc", "run-cdc", auditId, toolCode);
    }

    private AgentToolExecutionContext context(Map<String, Object> arguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-cdc", 10L, 101L, null, "1001", "PROJECT_OWNER", "USER", "101:OWNER",
                "WEB", "创建实时任务", WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101", LocalDateTime.now());
        AgentRunRecord run = new AgentRunRecord(
                "run-cdc", "session-cdc", AgentRunState.PLANNING, "AGENT_REASONING",
                "检查 CDC", true, false, List.of(), Map.of(), LocalDateTime.now(), "CDC 测试");
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-current", "session-cdc", "run-cdc", "binding-current",
                CdcReadinessToolAdapter.TOOL_CODE, "DATA_SYNC", "datasource-management", "/datasources", null,
                10L, 101L, null, "1001", "LOW", "SYNC", false,
                true, true, List.of("PRECHECK"), "CDC 检查", arguments,
                Map.of("projectScoped", true), Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED, "trace-cdc", "CDC 检查", LocalDateTime.now());
        return new AgentToolExecutionContext(session, run, audit, Map.of(), "trace-cdc");
    }
}
