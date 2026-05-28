/**
 * @Author : Cui
 * @Date: 2026/05/24 22:26
 * @Description DataSmart Govern Backend - QualityRuleSuggestToolAdapterTest.java
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
 * `quality.rule.suggest` 工具适配器测试。
 *
 * <p>测试重点不是 data-quality 内部如何生成规则，而是 Agent Runtime 是否正确构造服务账号请求：
 * 租户/项目来自会话，datasourceId/businessGoal/metadata 来自 ToolPlan，maxSuggestions 会被裁剪，
 * 并且 PROJECT 数据范围 Header 必须透传到下游。</p>
 */
class QualityRuleSuggestToolAdapterTest {

    @Test
    void shouldCallDataQualityAndReturnSuggestionSummary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QualityRuleSuggestToolAdapter adapter = adapter(builder, new AgentToolExecutionOutputStore());

        server.expect(once(), requestTo("http://data-quality.test/quality-rules/suggestions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT"))
                .andExpect(header(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT"))
                .andExpect(header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "20"))
                .andExpect(jsonPath("$.tenantId").value(10))
                .andExpect(jsonPath("$.projectId").value(20))
                .andExpect(jsonPath("$.datasourceId").value(1001))
                .andExpect(jsonPath("$.businessGoal").value("检查订单主键唯一性"))
                .andExpect(jsonPath("$.maxSuggestions").value(12))
                .andExpect(jsonPath("$.metadata.tables[0].tableName").value("ods_order"))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "datasourceId", 1001L,
                "businessGoal", "检查订单主键唯一性",
                "maxSuggestions", 99,
                "metadata", Map.of("tables", List.of(Map.of("tableName", "ods_order")))
        )));

        assertTrue(outcome.success());
        Map<?, ?> summary = assertInstanceOf(Map.class, outcome.output().get("summary"));
        assertEquals(2, summary.get("suggestionCount"));
        assertEquals("deterministic-metadata-rule-engine-v1", summary.get("generationStrategy"));
        server.verify();
    }

    @Test
    void shouldReturnStableFailureWhenDataQualityRejectsRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QualityRuleSuggestToolAdapter adapter = adapter(builder, new AgentToolExecutionOutputStore());

        server.expect(once(), requestTo("http://data-quality.test/quality-rules/suggestions"))
                .andRespond(withSuccess("""
                        {"code":400,"message":"项目不可见","data":null}
                        """, MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "datasourceId", 1001L,
                "businessGoal", "检查订单主键唯一性"
        )));

        assertFalse(outcome.success());
        assertEquals("QUALITY_RULE_SUGGEST_FAILED", outcome.errorCode());
        assertTrue(outcome.message().contains("项目不可见"));
        server.verify();
    }

    @Test
    void shouldReadMetadataFromPreviousDatasourceToolOutputWhenPlanArgumentsDoNotCarryMetadata() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-metadata",
                        "datasource.metadata.read"
                ),
                Map.of("metadata", Map.of("tables", List.of(Map.of("tableName", "ods_order"))))
        );
        QualityRuleSuggestToolAdapter adapter = adapter(builder, outputStore);

        server.expect(once(), requestTo("http://data-quality.test/quality-rules/suggestions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.metadata.tables[0].tableName").value("ods_order"))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "datasourceId", 1001L,
                "businessGoal", "检查订单主键唯一性"
        )));

        assertTrue(outcome.success());
        server.verify();
    }

    @Test
    void shouldReadMetadataByExplicitOutputReferenceWhenMultipleDatasourceOutputsExist() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-old-metadata",
                        "datasource.metadata.read"
                ),
                Map.of("metadata", Map.of("tables", List.of(Map.of("tableName", "old_table"))))
        );
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-target-metadata",
                        "datasource.metadata.read"
                ),
                Map.of("metadata", Map.of("tables", List.of(Map.of("tableName", "ods_order"))))
        );
        QualityRuleSuggestToolAdapter adapter = adapter(builder, outputStore);

        server.expect(once(), requestTo("http://data-quality.test/quality-rules/suggestions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.metadata.tables[0].tableName").value("ods_order"))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "datasourceId", 1001L,
                "businessGoal", "检查订单主键唯一性",
                "metadataRef", Map.of(
                        "toolCode", "datasource.metadata.read",
                        "auditId", "audit-target-metadata",
                        "jsonPath", "metadata"
                )
        )));

        assertTrue(outcome.success());
        server.verify();
    }

    private QualityRuleSuggestToolAdapter adapter(RestClient.Builder builder, AgentToolExecutionOutputStore outputStore) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("data-quality", "http://data-quality.test");
        return new QualityRuleSuggestToolAdapter(
                properties,
                builder,
                new QualityRuleSuggestRequestFactory(new AgentToolOutputReferenceResolver(outputStore)),
                new QualityRuleSuggestResponseMapper()
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
                "生成质量规则草案",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "检查订单主键唯一性",
                true,
                false,
                List.of(),
                Map.of("datasourceId", 1001L),
                LocalDateTime.now(),
                "测试规则草案工具"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-001",
                "session-001",
                "run-001",
                "binding-001",
                "quality.rule.suggest",
                "DATA_QUALITY",
                "data-quality",
                "/quality-rules/suggestions",
                null,
                10L,
                20L,
                null,
                "u-001",
                "MEDIUM",
                "DRAFT_ONLY",
                false,
                true,
                true,
                List.of("GENERATE"),
                "根据元数据生成质量规则草案",
                planArguments,
                Map.of("projectScoped", true),
                Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED,
                "trace-quality",
                "测试工具审计",
                LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, run.getVariables(), "trace-quality");
    }

    private String successResponse() {
        return """
                {
                  "code": 0,
                  "message": "质量规则草案建议生成完成",
                  "data": {
                    "datasourceId": 1001,
                    "tableName": "ods_order",
                    "businessGoal": "检查订单主键唯一性",
                    "suggestionCount": 2,
                    "generationStrategy": "deterministic-metadata-rule-engine-v1",
                    "warnings": [],
                    "recommendedActions": ["人工确认后保存为 DRAFT"],
                    "suggestions": [
                      {"name": "ods_order.order_id UNIQUENESS 草案", "ruleType": "UNIQUENESS"},
                      {"name": "ods_order.order_id COMPLETENESS 草案", "ruleType": "COMPLETENESS"}
                    ]
                  }
                }
                """;
    }
}
