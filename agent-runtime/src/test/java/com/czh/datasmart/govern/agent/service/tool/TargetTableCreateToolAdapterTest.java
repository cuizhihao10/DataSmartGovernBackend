/**
 * @Author : Cui
 * @Date: 2026/07/27 18:05
 * @Description DataSmart Govern Backend - TargetTableCreateToolAdapterTest.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Protects the trusted-metadata -> digest preview -> apply -> metadata refresh chain. */
class TargetTableCreateToolAdapterTest {

    @Test
    void previewShouldDeriveColumnsFromSourceMetadataWithoutAcceptingRawDdl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore store = metadataStore();
        TargetTableCreateToolAdapter adapter = adapter(builder, store);

        server.expect(once(), requestTo(
                        "http://datasource.test/datasources/22/schema-repair-plans/preview"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.operation").value("CREATE_TABLE"))
                .andExpect(jsonPath("$.schemaName").value("public"))
                .andExpect(jsonPath("$.tableName").value("customer_target"))
                .andExpect(jsonPath("$.columns[0].columnName").value("id"))
                .andExpect(jsonPath("$.columns[0].dataType").value("BIGINT"))
                .andExpect(jsonPath("$.columns[0].primaryKey").value(true))
                .andExpect(jsonPath("$.columns[1].dataType").value("VARCHAR"))
                .andExpect(jsonPath("$.columns[1].length").value(120))
                .andExpect(jsonPath("$.columns[1].defaultValue").doesNotExist())
                .andExpect(jsonPath("$.ddl").doesNotExist())
                .andRespond(withSuccess(successEnvelope("""
                        {"planId":91,"planRef":"plan-91","datasourceId":22,"operation":"CREATE_TABLE",
                         "objectLocator":"public.customer_target","currentDefinition":"TABLE_ABSENT",
                         "requestedDefinition":"id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(120) NULL",
                         "impactSummary":"创建新空表","planStatus":"PREVIEWED","requiresConfirmation":true,
                         "confirmationDigest":"digest-91","safetyConstraints":["不接受原始 DDL"]}
                        """), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(
                TargetTableCreateToolAdapter.PREVIEW,
                Map.of(
                        "sourceMetadataRef", reference(
                                DatasourceAccessToolAdapter.SOURCE_METADATA, "audit-source"),
                        "targetMetadataRef", reference(
                                DatasourceAccessToolAdapter.TARGET_METADATA, "audit-target"),
                        "sourceTableName", "customer_source",
                        "targetSchemaName", "public",
                        "targetTableName", "customer_target",
                        "ddl", "DROP TABLE customer_target")));

        assertTrue(outcome.success());
        assertEquals(22L, outcome.output().get("targetDatasourceId"));
        assertEquals("digest-91", outcome.output().get("confirmationDigest"));
        server.verify();
    }

    @Test
    void applyShouldUsePreviewDigestAndRefreshCreatedTargetMetadata() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore store = new AgentToolExecutionOutputStore();
        store.save(snapshot("audit-preview", TargetTableCreateToolAdapter.PREVIEW), Map.of(
                "planId", 91L,
                "confirmationDigest", "digest-91",
                "datasourceId", 22L,
                "targetDatasourceId", 22L,
                "sourceDatasourceId", 11L,
                "sourceTableName", "customer_source",
                "targetSchemaName", "public",
                "targetTableName", "customer_target"
        ));
        TargetTableCreateToolAdapter adapter = adapter(builder, store);

        server.expect(once(), requestTo(
                        "http://datasource.test/datasources/22/schema-repair-plans/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.planId").value(91))
                .andExpect(jsonPath("$.confirmationDigest").value("digest-91"))
                .andExpect(jsonPath("$.confirmed").value(true))
                .andRespond(withSuccess(successEnvelope("""
                        {"planId":91,"planRef":"plan-91","datasourceId":22,"operation":"CREATE_TABLE",
                         "objectLocator":"public.customer_target","currentDefinition":"TABLE_CREATED",
                         "requestedDefinition":"id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(120) NULL",
                         "impactSummary":"创建新空表","planStatus":"APPLIED","requiresConfirmation":false}
                        """), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "http://datasource.test/datasources/22/metadata/discover"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.schemaPattern").value("public"))
                .andExpect(jsonPath("$.tableNamePattern").value("customer_target"))
                .andExpect(jsonPath("$.includeSampleRows").value(false))
                .andRespond(withSuccess(successEnvelope("""
                        {"datasourceId":22,"datasourceName":"target","datasourceType":"POSTGRESQL",
                         "productName":"PostgreSQL","tableCount":1,"appliedMaxTables":5,
                         "tables":[{"schemaName":"public","tableName":"customer_target","tableType":"TABLE",
                           "columnCount":2,"totalColumnCount":2,"columnsTruncated":false,"primaryKeys":["id"],
                           "columns":[
                             {"columnName":"id","dataTypeName":"BIGINT","columnSize":19,"nullable":false,
                              "primaryKey":true,"ordinalPosition":1},
                             {"columnName":"name","dataTypeName":"VARCHAR","columnSize":120,"nullable":true,
                              "primaryKey":false,"ordinalPosition":2}]}]}
                        """), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(
                TargetTableCreateToolAdapter.APPLY,
                Map.of("previewRef", reference(TargetTableCreateToolAdapter.PREVIEW, "audit-preview"))));

        assertTrue(outcome.success());
        assertEquals("APPLIED", outcome.output().get("planStatus"));
        Map<?, ?> metadata = assertInstanceOf(Map.class, outcome.output().get("metadata"));
        List<?> tables = assertInstanceOf(List.class, metadata.get("tables"));
        assertEquals("customer_target", assertInstanceOf(Map.class, tables.getFirst()).get("tableName"));
        server.verify();
    }

    private TargetTableCreateToolAdapter adapter(RestClient.Builder builder,
                                                 AgentToolExecutionOutputStore store) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("datasource-management", "http://datasource.test");
        return new TargetTableCreateToolAdapter(
                builder,
                new AgentToolDownstreamHttpSupport(properties),
                new AgentToolOutputReferenceResolver(store),
                new DatasourceMetadataReadResponseMapper());
    }

    private AgentToolExecutionOutputStore metadataStore() {
        AgentToolExecutionOutputStore store = new AgentToolExecutionOutputStore();
        store.save(snapshot("audit-source", DatasourceAccessToolAdapter.SOURCE_METADATA), Map.of(
                "datasourceId", 11L,
                "metadata", Map.of(
                        "datasourceType", "MYSQL",
                        "tables", List.of(Map.of(
                                "tableName", "customer_source",
                                "columnsTruncated", false,
                                "columns", List.of(
                                        column("id", "BIGINT", 19, false, true, 1),
                                        column("name", "VARCHAR", 120, true, false, 2)))))));
        store.save(snapshot("audit-target", DatasourceAccessToolAdapter.TARGET_METADATA), Map.of(
                "datasourceId", 22L,
                "metadata", Map.of("datasourceType", "POSTGRESQL", "tables", List.of())));
        return store;
    }

    private Map<String, Object> column(String name,
                                       String type,
                                       int size,
                                       boolean nullable,
                                       boolean primaryKey,
                                       int ordinal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columnName", name);
        result.put("dataTypeName", type);
        result.put("columnSize", size);
        result.put("nullable", nullable);
        result.put("primaryKey", primaryKey);
        result.put("ordinalPosition", ordinal);
        result.put("defaultValue", primaryKey ? "AUTO_INCREMENT" : null);
        return result;
    }

    private Map<String, Object> reference(String toolCode, String auditId) {
        return Map.of("fromTool", toolCode, "fromAuditId", auditId);
    }

    private AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot snapshot(
            String auditId,
            String toolCode) {
        return new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                "session-create-table", "run-create-table", auditId, toolCode);
    }

    private AgentToolExecutionContext context(String toolCode, Map<String, Object> planArguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-create-table", 10L, 101L, null, "1001",
                "PROJECT_OWNER", "USER", "101:OWNER",
                "WEB", "创建同步任务", WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101", LocalDateTime.now());
        AgentRunRecord run = new AgentRunRecord(
                "run-create-table", "session-create-table", AgentRunState.PLANNING,
                "AGENT_REASONING", "创建目标表", true, false,
                List.of(), Map.of(), LocalDateTime.now(), "目标表创建测试");
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-current", "session-create-table", "run-create-table", "binding-current",
                toolCode, "DATASOURCE_MANAGEMENT", "datasource-management", "/datasources", null,
                10L, 101L, null, "1001", "HIGH", "APPROVAL_REQUIRED", true,
                false, false, List.of("CREATE_TARGET_TABLE"), "目标表创建测试",
                planArguments, Map.of("projectScoped", true), Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED, "trace-create-table", "目标表创建测试", LocalDateTime.now());
        return new AgentToolExecutionContext(session, run, audit, Map.of(), "trace-create-table");
    }

    private String successEnvelope(String data) {
        return "{\"code\":0,\"message\":\"success\",\"data\":" + data + "}";
    }
}
