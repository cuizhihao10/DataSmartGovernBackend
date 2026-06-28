/**
 * @Author : Cui
 * @Date: 2026/06/28 13:26
 * @Description DataSmart Govern Backend - QualityRemediationTaskDraftToolAdapterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadMaterializationService;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadRecord;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentToolActionPayloadStore;
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
 * `quality.remediation.task.draft` 工具适配器测试。
 *
 * <p>这组测试保护的不是 data-quality 内部聚合逻辑，而是 Java Agent Runtime 的控制面边界：</p>
 * <p>1. Agent Session 中的项目范围优先于模型/ToolPlan 参数，避免跨项目伪造；</p>
 * <p>2. adapter 永远以服务账号身份调用 data-quality，避免人类 actor 字符串造成 Header 类型转换失败；</p>
 * <p>3. `dryRun` 被强制为 true，当前阶段只生成预览，不创建真实 task-management 任务；</p>
 * <p>4. 输出只暴露低敏摘要和 payload preview，不包含 prompt、SQL、样本值或完整工具参数。</p>
 */
class QualityRemediationTaskDraftToolAdapterTest {

    @Test
    void shouldCallDataQualityDryRunAndReturnLowSensitiveDraft() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AdapterFixture fixture = adapter(builder);

        server.expect(once(), requestTo("http://data-quality.test/quality-rules/remediation-tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.ACTOR_ID, "900001"))
                .andExpect(header(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT"))
                .andExpect(header(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT"))
                .andExpect(header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "20"))
                .andExpect(jsonPath("$.tenantId").value(10))
                .andExpect(jsonPath("$.projectId").value(20))
                .andExpect(jsonPath("$.workspaceId").value(30))
                .andExpect(jsonPath("$.reportId").value(77))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.anomalyType").value("FORMAT_INVALID"))
                .andExpect(jsonPath("$.fieldName").value("phone"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.aggregationLimit").value(50))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = fixture.adapter().execute(context(Map.of(
                "remediationScope", Map.of(
                        "projectId", 999L,
                        "workspaceId", 888L,
                        "reportId", 77L,
                        "severity", "high",
                        "anomalyType", "FORMAT_INVALID",
                        "fieldName", "phone"
                ),
                "remediationType", "manual_review",
                "reason", "基于低敏质量异常聚合生成治理任务草案",
                "recommendation", "MANUAL_REVIEW_AND_ASSIGN_OWNER",
                "dryRun", false,
                "aggregationLimit", 999
        )));

        assertTrue(outcome.success());
        Map<?, ?> summary = assertInstanceOf(Map.class, outcome.output().get("summary"));
        assertEquals(true, summary.get("draftOnly"));
        assertEquals("NONE", summary.get("sideEffect"));
        assertEquals(false, summary.get("submitted"));
        assertEquals(true, summary.get("dryRun"));
        assertEquals(18, summary.get("anomalyCount"));

        Map<?, ?> draft = assertInstanceOf(Map.class, outcome.output().get("remediationTaskDraft"));
        assertEquals(true, draft.get("approvalRequiredBeforeSubmit"));
        Map<?, ?> scope = assertInstanceOf(Map.class, draft.get("scope"));
        assertEquals(20, scope.get("projectId"));
        assertEquals(77, scope.get("reportId"));
        assertEquals("HIGH", scope.get("severity"));
        assertFalse(draft.containsKey("lowSensitivePayloadPreview"));
        assertEquals("agent-payload:run-001/quality-remediation-task-draft:audit-remediation",
                draft.get("payloadReference"));
        assertEquals(true, draft.get("payloadBodyAvailable"));

        AgentToolActionPayloadRecord payloadRecord = fixture.payloadStore()
                .findByReference(String.valueOf(draft.get("payloadReference")))
                .orElseThrow();
        assertTrue(payloadRecord.payloadBodyAvailable());
        assertTrue(payloadRecord.payloadBody().containsKey("remediationTaskDraft"));
        assertTrue(String.valueOf(payloadRecord.payloadBody()).contains("topAnomalyTypes"));
        assertFalse(String.valueOf(outcome.output()).contains("topAnomalyTypes"));
        server.verify();
    }

    @Test
    void shouldReturnStableFailureWhenDataQualityRejectsDryRun() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AdapterFixture fixture = adapter(builder);

        server.expect(once(), requestTo("http://data-quality.test/quality-rules/remediation-tasks"))
                .andRespond(withSuccess("""
                        {"code":400,"message":"项目不可见","data":null}
                        """, MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = fixture.adapter().execute(context(Map.of(
                "remediationScope", Map.of("reportId", 77L),
                "reason", "创建治理任务草案"
        )));

        assertFalse(outcome.success());
        assertEquals("QUALITY_REMEDIATION_DRAFT_FAILED", outcome.errorCode());
        assertTrue(outcome.message().contains("项目不可见"));
        server.verify();
    }

    private AdapterFixture adapter(RestClient.Builder builder) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("data-quality", "http://data-quality.test");
        AgentToolServiceAuthorizationProperties authorizationProperties = new AgentToolServiceAuthorizationProperties();
        InMemoryAgentToolActionPayloadStore payloadStore = new InMemoryAgentToolActionPayloadStore();
        QualityRemediationTaskDraftToolAdapter adapter = new QualityRemediationTaskDraftToolAdapter(
                properties,
                authorizationProperties,
                builder,
                new QualityRemediationTaskDraftRequestFactory(),
                new QualityRemediationTaskDraftResponseMapper(),
                new AgentToolActionPayloadMaterializationService(payloadStore)
        );
        return new AdapterFixture(adapter, payloadStore);
    }

    private AgentToolExecutionContext context(Map<String, Object> planArguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                30L,
                "u-001",
                "WEB",
                "创建质量异常治理任务草案",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "基于质量报告生成治理任务草案",
                true,
                false,
                List.of(),
                Map.of("reportId", 77L),
                LocalDateTime.now(),
                "测试治理任务草案工具"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-remediation",
                "session-001",
                "run-001",
                "binding-remediation",
                "quality.remediation.task.draft",
                "DATA_QUALITY",
                "data-quality",
                "/quality-rules/remediation-tasks",
                null,
                10L,
                20L,
                30L,
                "u-001",
                "MEDIUM",
                "DRAFT_ONLY",
                false,
                false,
                true,
                List.of("CREATE_REMEDIATION_TASK_DRAFT", "DRY_RUN_PREVIEW"),
                "生成质量异常治理任务 dry-run 草案",
                planArguments,
                Map.of("projectScoped", true),
                Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED,
                "trace-remediation",
                "测试质量治理草案工具",
                LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, run.getVariables(), "trace-remediation");
    }

    private String successResponse() {
        return """
                {
                  "code": 0,
                  "message": "质量异常治理任务 dry-run 预览已生成",
                  "data": {
                    "submitted": false,
                    "dryRun": true,
                    "taskId": null,
                    "taskType": "DATA_QUALITY_REMEDIATION",
                    "taskStatus": null,
                    "priority": "HIGH",
                    "anomalyCount": 18,
                    "tenantId": 10,
                    "projectId": 20,
                    "workspaceId": 30,
                    "reportId": 77,
                    "ruleId": 66,
                    "payloadPolicy": "LOW_SENSITIVE_AGGREGATION_ONLY",
                    "payloadPreview": {
                      "payloadVersion": "quality-remediation-task-v1",
                      "reportId": 77,
                      "anomalyCount": 18,
                      "topAnomalyTypes": [{"key":"FORMAT_INVALID","count":18}]
                    },
                    "warnings": []
                  }
                }
                """;
    }

    private record AdapterFixture(QualityRemediationTaskDraftToolAdapter adapter,
                                  InMemoryAgentToolActionPayloadStore payloadStore) {
    }
}
