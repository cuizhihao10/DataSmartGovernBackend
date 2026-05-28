/**
 * @Author : Cui
 * @Date: 2026/05/24 23:59
 * @Description DataSmart Govern Backend - TaskDraftPersistToolAdapterTest.java
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * `task.draft.persist` 工具适配器测试。
 *
 * <p>这组测试固定一个非常关键的商业化契约：
 * Agent 可以把前序 `task.create.draft` 的输出保存为 task-management 草稿，
 * 但保存后仍然只是 DRAFT，不会自动提交审批、审批通过或转换成真实 PENDING 任务。</p>
 */
class TaskDraftPersistToolAdapterTest {

    @Test
    void shouldPersistTaskDraftFromExplicitOutputReference() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-task-draft",
                        "task.create.draft"
                ),
                Map.of("taskDraft", taskDraft())
        );
        TaskDraftPersistToolAdapter adapter = adapter(builder, outputStore);

        server.expect(once(), requestTo("http://task-management.test/task-drafts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT"))
                .andExpect(header(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT"))
                .andExpect(header(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime"))
                .andExpect(header(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT"))
                .andExpect(header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "20"))
                .andExpect(jsonPath("$.name").value("DATA_QUALITY_SCAN 草稿 - ods_order"))
                .andExpect(jsonPath("$.type").value("DATA_QUALITY_SCAN"))
                .andExpect(jsonPath("$.tenantId").value(10))
                .andExpect(jsonPath("$.projectId").value(20))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.sourceType").value("AGENT"))
                .andExpect(jsonPath("$.sourceRef").value("audit-persist"))
                .andExpect(jsonPath("$.params").isString())
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "taskDraftRef", Map.of(
                        "fromTool", "task.create.draft",
                        "fromAuditId", "audit-task-draft",
                        "path", "taskDraft"
                )
        )));

        assertTrue(outcome.success());
        Map<?, ?> summary = assertInstanceOf(Map.class, outcome.output().get("summary"));
        assertEquals(true, summary.get("persisted"));
        assertEquals(501, ((Number) summary.get("draftId")).intValue());
        assertEquals("DRAFT", summary.get("status"));
        assertEquals(false, summary.get("queueVisible"));

        Map<?, ?> approval = assertInstanceOf(Map.class, outcome.output().get("approval"));
        assertEquals(true, approval.get("requiredBeforeExecution"));
        server.verify();
    }

    @Test
    void shouldPersistDirectTaskDraftArgumentAfterUserEditedDraft() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TaskDraftPersistToolAdapter adapter = adapter(builder, new AgentToolExecutionOutputStore());

        server.expect(once(), requestTo("http://task-management.test/task-drafts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("人工确认后的质量扫描草稿"))
                .andExpect(jsonPath("$.params").isString())
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "taskDraft", Map.of(
                        "name", "人工确认后的质量扫描草稿",
                        "description", "用户在确认页调整后的草稿",
                        "type", "data_quality_scan",
                        "tenantId", 10L,
                        "projectId", 20L,
                        "priority", "high",
                        "params", Map.of("payloadVersion", "agent-task-draft-v1")
                ),
                "sourceRef", "audit-user-edited-draft"
        )));

        assertTrue(outcome.success());
        server.verify();
    }

    @Test
    void shouldReturnStableFailureWhenTaskManagementRejectsDraft() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TaskDraftPersistToolAdapter adapter = adapter(builder, new AgentToolExecutionOutputStore());

        server.expect(once(), requestTo("http://task-management.test/task-drafts"))
                .andRespond(withSuccess("""
                        {"code":400,"message":"无权在未授权项目中创建任务草稿","data":null}
                        """, MediaType.APPLICATION_JSON));

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of("taskDraft", taskDraft())));

        assertFalse(outcome.success());
        assertEquals("TASK_DRAFT_PERSIST_FAILED", outcome.errorCode());
        assertTrue(outcome.message().contains("未授权项目"));
        server.verify();
    }

    private TaskDraftPersistToolAdapter adapter(RestClient.Builder builder, AgentToolExecutionOutputStore outputStore) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("task-management", "http://task-management.test");
        return new TaskDraftPersistToolAdapter(
                properties,
                builder,
                new TaskDraftPersistRequestFactory(new AgentToolOutputReferenceResolver(outputStore), new ObjectMapper()),
                new TaskDraftPersistResponseMapper()
        );
    }

    private AgentToolExecutionContext context(Map<String, Object> planArguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                30L,
                "10001",
                "WEB",
                "保存任务草稿",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "保存质量扫描任务草稿",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "测试任务草稿持久化工具"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-persist",
                "session-001",
                "run-001",
                "binding-task-draft-persist",
                "task.draft.persist",
                "TASK_MANAGEMENT",
                "task-management",
                "/task-drafts",
                null,
                10L,
                20L,
                30L,
                "10001",
                "HIGH",
                "APPROVAL_REQUIRED",
                true,
                false,
                false,
                List.of("CREATE"),
                "保存受控任务草稿",
                planArguments,
                Map.of("projectScoped", true),
                Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED,
                "trace-task-draft-persist",
                "测试任务草稿持久化工具",
                LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, run.getVariables(), "trace-task-draft-persist");
    }

    private Map<String, Object> taskDraft() {
        return Map.of(
                "name", "DATA_QUALITY_SCAN 草稿 - ods_order",
                "description", "Agent 根据质量规则建议生成的待审批扫描任务草稿",
                "type", "DATA_QUALITY_SCAN",
                "tenantId", 10L,
                "projectId", 20L,
                "priority", "HIGH",
                "maxRetryCount", 3,
                "maxDeferCount", 20,
                "params", Map.of(
                        "payloadVersion", "agent-task-draft-v1",
                        "objective", "检查订单主键唯一性和金额有效性",
                        "qualityRuleSuggestions", List.of(
                                Map.of("name", "ods_order.order_id 唯一性草案", "ruleType", "UNIQUENESS")
                        )
                )
        );
    }

    private String successResponse() {
        return """
                {
                  "code": 0,
                  "message": "任务草稿创建成功",
                  "data": {
                    "id": 501,
                    "name": "DATA_QUALITY_SCAN 草稿 - ods_order",
                    "description": "Agent 根据质量规则建议生成的待审批扫描任务草稿",
                    "type": "DATA_QUALITY_SCAN",
                    "tenantId": 10,
                    "ownerId": 10001,
                    "projectId": 20,
                    "status": "DRAFT",
                    "priority": "HIGH",
                    "sourceType": "AGENT",
                    "sourceRef": "audit-persist"
                  }
                }
                """;
    }
}
