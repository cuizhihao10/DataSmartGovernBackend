/**
 * @Author : Cui
 * @Date: 2026/06/28 23:10
 * @Description DataSmart Govern Backend - QualityRemediationTaskCommandSubmissionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionQualityRemediationSubmitRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionQualityRemediationSubmitResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.InMemoryAgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionApprovalConfirmationRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionApprovalConfirmationStatus;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadRecord;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentToolActionApprovalConfirmationStore;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentToolActionPayloadStore;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 质量治理受控命令提交服务测试。
 *
 * <p>本测试保护真实副作用入口的关键安全边界：task-management worker 只能传 commandId，
 * agent-runtime 必须自己回查 outbox、confirmation fact 和 payload body；提交给 data-quality 的请求必须
 * `dryRun=false`，而响应不能携带 payloadPreview、治理草案正文或异常聚合明细。</p>
 */
class QualityRemediationTaskCommandSubmissionServiceTest {

    @Test
    void submitShouldCallDataQualityWithDryRunFalseAfterHostSideFactsVerified() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TestFixture fixture = fixture(builder);
        fixture.prepareVerifiedFacts(false);

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
                .andExpect(jsonPath("$.ruleId").value(66))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.anomalyType").value("FORMAT_INVALID"))
                .andExpect(jsonPath("$.fieldName").value("phone"))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        AgentToolActionQualityRemediationSubmitResponse response = fixture.service().submit(
                "command-remediation-001",
                new AgentToolActionQualityRemediationSubmitRequest(
                        "task-worker-001",
                        501L,
                        9001L,
                        "submit:command-remediation-001"
                ),
                "trace-submit-test"
        );

        assertTrue(response.accepted());
        assertFalse(response.duplicate());
        assertTrue(response.sideEffectStarted());
        assertTrue(response.sideEffectExecuted());
        assertEquals("EXECUTION_SUCCEEDED", response.outcome());
        assertEquals(7001L, response.taskId());
        assertEquals("PENDING", response.taskStatus());
        assertFalse(response.toString().contains("payloadPreview"));
        assertFalse(response.toString().contains("topAnomalyTypes"));

        AgentToolActionQualityRemediationSubmitResponse duplicate = fixture.service().submit(
                "command-remediation-001",
                new AgentToolActionQualityRemediationSubmitRequest("task-worker-001", 501L, 9002L, null),
                "trace-submit-test-duplicate"
        );
        assertTrue(duplicate.duplicate());
        assertEquals(7001L, duplicate.taskId());
        server.verify();
    }

    @Test
    void submitShouldRejectMissingConfirmationBeforeCallingDataQuality() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TestFixture fixture = fixture(builder);
        fixture.prepareVerifiedFacts(true);

        assertThrows(RuntimeException.class, () -> fixture.service().submit(
                "command-remediation-001",
                new AgentToolActionQualityRemediationSubmitRequest("task-worker-001", 501L, 9001L, null),
                "trace-submit-test"
        ));
        server.verify();
    }

    private TestFixture fixture(RestClient.Builder builder) {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        runtimeProperties.getToolServiceBaseUrls().put("data-quality", "http://data-quality.test");
        InMemoryAgentAsyncTaskCommandOutboxStore outboxStore =
                new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        InMemoryAgentToolActionPayloadStore payloadStore = new InMemoryAgentToolActionPayloadStore();
        InMemoryAgentToolActionApprovalConfirmationStore confirmationStore =
                new InMemoryAgentToolActionApprovalConfirmationStore();
        QualityRemediationTaskCommandSubmissionService service = new QualityRemediationTaskCommandSubmissionService(
                outboxStore,
                payloadStore,
                confirmationStore,
                new QualityRemediationTaskSubmissionRequestBuilder(),
                runtimeProperties,
                new AgentToolServiceAuthorizationProperties(),
                builder,
                new ObjectMapper()
        );
        return new TestFixture(service, outboxStore, payloadStore, confirmationStore, new ObjectMapper());
    }

    private String successResponse() {
        return """
                {
                  "code": 0,
                  "message": "质量异常治理任务已提交",
                  "data": {
                    "submitted": true,
                    "dryRun": false,
                    "taskId": 7001,
                    "taskType": "DATA_QUALITY_REMEDIATION",
                    "taskStatus": "PENDING",
                    "priority": "HIGH",
                    "anomalyCount": 18,
                    "tenantId": 10,
                    "projectId": 20,
                    "workspaceId": 30,
                    "reportId": 77,
                    "ruleId": 66,
                    "payloadPolicy": "LOW_SENSITIVE_AGGREGATION_ONLY",
                    "payloadPreview": {
                      "topAnomalyTypes": [{"key":"FORMAT_INVALID","count":18}]
                    }
                  }
                }
                """;
    }

    private record TestFixture(
            QualityRemediationTaskCommandSubmissionService service,
            InMemoryAgentAsyncTaskCommandOutboxStore outboxStore,
            InMemoryAgentToolActionPayloadStore payloadStore,
            InMemoryAgentToolActionApprovalConfirmationStore confirmationStore,
            ObjectMapper objectMapper
    ) {

        private void prepareVerifiedFacts(boolean skipConfirmation) {
            String payloadReference = "agent-payload:run-001/quality-remediation-task-draft:audit-001";
            outboxStore.append(outboxRecord(payloadReference));
            payloadStore.append(payloadRecord(payloadReference));
            if (!skipConfirmation) {
                confirmationStore.saveIfAbsent(confirmation(payloadReference));
            }
        }

        private AgentAsyncTaskCommandOutboxRecord outboxRecord(String payloadReference) {
            Instant now = Instant.now();
            AgentAsyncTaskCommandOutboxRecord pending = AgentAsyncTaskCommandOutboxRecord.pending(
                    "command-remediation-001",
                    "idem-remediation-001",
                    "datasmart.agent.async-task-command.v1",
                    "AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
                    "datasmart.agent.tool.async.commands",
                    "task-management",
                    "session-001",
                    "run-001",
                    "audit-remediation-001",
                    "quality.remediation.task.draft",
                    "agent-runtime",
                    null,
                    10L,
                    20L,
                    null,
                    "1001",
                    "trace-command",
                    payloadReference,
                    payloadJson(payloadReference),
                    1000,
                    now
            );
            return pending.markPublished(now);
        }

        private String payloadJson(String payloadReference) {
            try {
                return objectMapper.writeValueAsString(Map.ofEntries(
                        Map.entry("schemaVersion", "datasmart.agent.async-task-command.v1"),
                        Map.entry("commandId", "command-remediation-001"),
                        Map.entry("commandType", "AGENT_TOOL_ACTION_CONTROLLED_COMMAND"),
                        Map.entry("toolCode", "quality.remediation.task.draft"),
                        Map.entry("targetService", "agent-runtime"),
                        Map.entry("proposalId", "proposal-remediation-001"),
                        Map.entry("graphId", "graph-remediation-001"),
                        Map.entry("contractId", "quality-remediation-task-draft.v1"),
                        Map.entry("tenantId", "10"),
                        Map.entry("projectId", "20"),
                        Map.entry("actorId", "1001"),
                        Map.entry("runId", "run-001"),
                        Map.entry("sessionId", "session-001"),
                        Map.entry("payloadReference", payloadReference),
                        Map.entry("payloadPolicy", "LOW_SENSITIVE_DRAFT_BODY"),
                        Map.entry("payloadBodyAvailable", true),
                        Map.entry("payloadSizeBytes", 512),
                        Map.entry("confirmationId", "tool-action-confirmation:remediation-001"),
                        Map.entry("approvalConfirmationId", "tool-action-confirmation:remediation-001"),
                        Map.entry("policyVersion", "tool-readiness-policy.v1"),
                        Map.entry("priority", "HIGH"),
                        Map.entry("maxRetryCount", 3)
                ));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private AgentToolActionPayloadRecord payloadRecord(String payloadReference) {
            return new AgentToolActionPayloadRecord(
                    payloadReference,
                    "run-001",
                    "quality-remediation-task-draft:audit-001",
                    "10",
                    "20",
                    "1001",
                    "quality.remediation.task.draft",
                    "graph-remediation-001",
                    "quality-remediation-task-draft.v1",
                    "LOW_SENSITIVE_DRAFT_BODY",
                    List.of("remediationScope", "dryRun"),
                    List.of("remediationScope"),
                    true,
                    512,
                    "payload-digest-001",
                    Instant.now(),
                    Instant.now().plusSeconds(1800),
                    Map.of(
                            "summary", Map.of("anomalyCount", 18, "draftOnly", true),
                            "remediationTaskDraft", Map.of(
                                    "priority", "HIGH",
                                    "scope", Map.of(
                                            "workspaceId", 30,
                                            "reportId", 77,
                                            "ruleId", 66,
                                            "severity", "HIGH",
                                            "anomalyType", "FORMAT_INVALID",
                                            "fieldName", "phone"
                                    ),
                                    "lowSensitivePayloadPreview", Map.of(
                                            "workspaceId", 30,
                                            "reportId", 77,
                                            "ruleId", 66,
                                            "severity", "HIGH",
                                            "targetObject", "ods_customer",
                                            "remediationType", "MANUAL_REVIEW",
                                            "reason", "经低敏质量异常聚合确认后提交治理任务",
                                            "recommendation", "MANUAL_REVIEW_AND_ASSIGN_OWNER",
                                            "filters", Map.of("fieldName", "phone")
                                    )
                            )
                    )
            );
        }

        private AgentToolActionApprovalConfirmationRecord confirmation(String payloadReference) {
            return new AgentToolActionApprovalConfirmationRecord(
                    "tool-action-confirmation:remediation-001",
                    "proposal-remediation-001",
                    "client-request-001",
                    payloadReference,
                    "run-001",
                    "quality-remediation-task-draft:audit-001",
                    "10",
                    "20",
                    "1001",
                    "1001",
                    "quality.remediation.task.draft",
                    "graph-remediation-001",
                    "quality-remediation-task-draft.v1",
                    "tool-readiness-policy.v1",
                    "LOW_SENSITIVE_DRAFT_BODY",
                    true,
                    512,
                    "payload-digest-001",
                    List.of("PAYLOAD_BODY_AVAILABLE"),
                    true,
                    AgentToolActionApprovalConfirmationStatus.CONFIRMED,
                    Instant.now(),
                    Instant.now(),
                    Instant.now().plusSeconds(1800)
            );
        }
    }
}
