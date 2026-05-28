/**
 * @Author : Cui
 * @Date: 2026/05/24 21:11
 * @Description DataSmart Govern Backend - DatasourceMetadataReadToolAdapterTest.java
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
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * `datasource.metadata.read` 工具适配器测试。
 *
 * <p>这组测试不启动真实 datasource-management 服务，而是用 `MockRestServiceServer`
 * 拦截 `RestClient` 请求。这样可以精确验证三件对生产很关键的事情：</p>
 * <p>1. Agent 计划参数是否被裁剪成安全的下游请求；</p>
 * <p>2. 租户、项目、服务账号、traceId 等平台 Header 是否被正确透传；</p>
 * <p>3. 下游统一响应是否被转换为稳定的 Agent 工具执行结果。</p>
 */
class DatasourceMetadataReadToolAdapterTest {

    @Test
    void shouldClampPlanArgumentsAndBuildSummaryWhenMetadataDiscoverySucceeds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DatasourceMetadataReadToolAdapter adapter = adapter(builder);

        server.expect(once(), requestTo("http://datasource-management.test/datasources/1001/metadata/discover"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.TENANT_ID, "10"))
                .andExpect(header(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT"))
                .andExpect(header(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT"))
                .andExpect(header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "20"))
                .andExpect(header(PlatformContextHeaders.TRACE_ID, "trace-tool"))
                .andExpect(jsonPath("$.actorId").value(1))
                .andExpect(jsonPath("$.actorRole").value("AGENT_RUNTIME"))
                .andExpect(jsonPath("$.actorTenantId").value(10))
                .andExpect(jsonPath("$.schemaPattern").value("public"))
                .andExpect(jsonPath("$.tableNamePattern").value("ods_%"))
                .andExpect(jsonPath("$.maxTables").value(100))
                .andExpect(jsonPath("$.maxColumnsPerTable").value(300))
                .andExpect(jsonPath("$.includeIndexes").value(true))
                .andExpect(jsonPath("$.includeSampleRows").value(false))
                .andExpect(jsonPath("$.sampleRowLimit").value(0))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "schemaPattern", "public",
                "tableNamePattern", "ods_%",
                "maxTables", 999,
                "maxColumnsPerTable", "999",
                "includeIndexes", true,
                "includeSampleRows", true
        )));

        assertTrue(outcome.success());
        assertEquals("数据源元数据读取成功，已生成受控发现摘要。", outcome.message());
        Map<?, ?> summary = assertInstanceOf(Map.class, outcome.output().get("summary"));
        assertEquals(2, summary.get("tableCount"));
        assertEquals(3, summary.get("columnCount"));
        assertEquals(true, summary.get("truncated"));
        assertEquals(false, summary.get("cacheHit"));
        server.verify();
    }

    @Test
    void shouldReturnStableFailureWhenDatasourceManagementReturnsBusinessError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DatasourceMetadataReadToolAdapter adapter = adapter(builder);

        server.expect(once(), requestTo("http://datasource-management.test/datasources/1001/metadata/discover"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":40001,"message":"当前数据源不可见","data":null}
                        """, MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of()));

        assertFalse(outcome.success());
        assertEquals("DATASOURCE_METADATA_FAILED", outcome.errorCode());
        assertTrue(outcome.message().contains("当前数据源不可见"));
        server.verify();
    }

    private DatasourceMetadataReadToolAdapter adapter(RestClient.Builder builder) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("datasource-management", "http://datasource-management.test");
        return new DatasourceMetadataReadToolAdapter(
                properties,
                builder,
                new DatasourceMetadataReadRequestFactory(),
                new DatasourceMetadataReadResponseMapper()
        );
    }

    private AgentToolExecutionContext context(Map<String, Object> planArguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                null,
                "u-001",
                "WEB",
                "读取数据源结构",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "读取 ods 表结构",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "测试元数据工具"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-001",
                "session-001",
                "run-001",
                "binding-001",
                "datasource.metadata.read",
                "DATASOURCE_METADATA",
                "datasource-management",
                "/datasources/{id}/metadata/discover",
                1001L,
                10L,
                20L,
                null,
                "u-001",
                "LOW",
                "SYNC",
                false,
                true,
                true,
                List.of("VIEW_STRUCTURE"),
                "用户希望了解 ods 表结构，用于后续质量规则草案生成。",
                planArguments,
                Map.of("projectScoped", true),
                Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED,
                "trace-tool",
                "测试工具审计",
                LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, Map.of("datasourceId", 1001L), "trace-tool");
    }

    private String successResponse() {
        return """
                {
                  "code": 0,
                  "message": "数据源元数据发现完成",
                  "data": {
                    "datasourceId": 1001,
                    "datasourceName": "订单库",
                    "datasourceType": "MYSQL",
                    "productName": "MySQL",
                    "tableCount": 2,
                    "appliedMaxTables": 100,
                    "cacheHit": false,
                    "discoveryDurationMs": 38,
                    "warnings": ["当前结果未返回样本数据"],
                    "tables": [
                      {"tableName":"ods_order","columnCount":2,"columnsTruncated":false},
                      {"tableName":"ods_user","columnCount":1,"columnsTruncated":true}
                    ]
                  }
                }
                """;
    }
}
