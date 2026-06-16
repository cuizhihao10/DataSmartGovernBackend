/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.InMemoryAgentAsyncTaskCommandOutboxStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作恢复事实包服务测试。
 *
 * <p>这组测试保护的是“恢复执行前事实验真”的控制面语义：
 * 1. 调用方带来的 approvalFactId 不能直接算作审批通过，必须由 evaluator 采信；
 * 2. command outbox 只暴露低敏摘要，不暴露 payloadJson/payloadReference/targetEndpoint/lastError；
 * 3. worker receipt 只暴露 outcome/errorCode 等机器状态，不暴露 message/prompt/SQL；
 * 4. 项目数据范围不匹配时，即使 outbox record 存在，也不能从事实包中被观察到。</p>
 */
class AgentToolActionResumeFactBundleServiceTest {

    @Test
    void shouldAggregateApprovedOutboxAndReceiptFactsWithoutSensitivePayload() throws JsonProcessingException {
        TestHarness harness = harness(approvedEvaluator());
        harness.outboxStore().append(outboxRecord("taoc-resume-001", 20L));
        harness.projectionStore().append(receiptRecord("receipt-ready", "taoc-resume-001", true, "DRY_RUN_PASSED", 1L));

        AgentToolActionResumeFactBundleResponse response = harness.service().query(
                request(List.of(
                        "APPROVAL_CONFIRMATION_FACT",
                        "OUTBOX_WRITE_CONFIRMATION",
                        "WORKER_RECEIPT_PROJECTION"
                )),
                projectOwnerContext()
        );

        assertEquals(List.of(
                "APPROVAL_CONFIRMATION_FACT",
                "OUTBOX_WRITE_CONFIRMATION",
                "WORKER_RECEIPT_PROJECTION"
        ), response.availableFactTypes());
        assertTrue(response.missingFactTypes().isEmpty());
        assertTrue(response.rejectedFactTypes().isEmpty());
        assertNotNull(response.outboxSummary());
        assertEquals("taoc-resume-001", response.outboxSummary().commandId());
        assertEquals(true, response.outboxSummary().payloadReferencePresent());
        assertNotNull(response.receiptSummary());
        assertEquals(1, response.receiptSummary().receiptCount());
        assertEquals("DRY_RUN_PASSED", response.receiptSummary().latestOutcome());

        /*
         * 用 JSON 序列化断言一次“最终 API 响应”不会泄露敏感字段。
         * 这比只检查 Java getter 更接近真实 HTTP 响应形态。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("approval-fact-approved-001"));
        assertFalse(json.contains("agent-payload:run-resume/sensitive-payload-key"));
        assertFalse(json.contains("http://internal-service/tools"));
        assertFalse(json.contains("select * from sensitive_table"));
        assertFalse(json.contains("raw prompt should not leak"));
        assertFalse(json.contains("payloadJson"));
    }

    @Test
    void rejectedApprovalFactShouldEnterRejectedFactTypes() {
        TestHarness harness = harness(rejectedEvaluator());

        AgentToolActionResumeFactBundleResponse response = harness.service().query(
                request(List.of("APPROVAL_CONFIRMATION_FACT")),
                projectOwnerContext()
        );

        assertFalse(response.availableFactTypes().contains("APPROVAL_CONFIRMATION_FACT"));
        assertTrue(response.rejectedFactTypes().contains("APPROVAL_CONFIRMATION_FACT"));
        assertTrue(response.facts().getFirst().issueCodes().contains("APPROVAL_FACT_REJECTED_BY_PERMISSION_ADMIN"));
        assertTrue(response.recommendedActions().stream()
                .anyMatch(action -> action.contains("APPROVAL_CONFIRMATION_FACT")));
    }

    @Test
    void outboxRecordOutsideProjectScopeShouldBeHidden() {
        TestHarness harness = harness(approvedEvaluator());
        harness.outboxStore().append(outboxRecord("taoc-resume-001", 99L));

        AgentToolActionResumeFactBundleResponse response = harness.service().query(
                request(List.of("OUTBOX_WRITE_CONFIRMATION")),
                projectOwnerContext()
        );

        assertTrue(response.missingFactTypes().contains("OUTBOX_WRITE_CONFIRMATION"));
        assertNull(response.outboxSummary());
        assertTrue(response.facts().getFirst().issueCodes().contains("OUTBOX_RECORD_NOT_FOUND_OR_NOT_VISIBLE"));
    }

    @Test
    void failedReceiptShouldRejectWorkerReceiptFact() {
        TestHarness harness = harness(approvedEvaluator());
        harness.projectionStore().append(receiptRecord(
                "receipt-failed",
                "taoc-resume-001",
                false,
                "FAILED_PRECHECK",
                1L
        ));

        AgentToolActionResumeFactBundleResponse response = harness.service().query(
                request(List.of("WORKER_RECEIPT_PROJECTION")),
                projectOwnerContext()
        );

        assertTrue(response.rejectedFactTypes().contains("WORKER_RECEIPT_PROJECTION"));
        assertEquals("FAILED_PRECHECK", response.receiptSummary().latestOutcome());
        assertEquals("AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED",
                response.receiptSummary().latestErrorCode());
    }

    private TestHarness harness(AgentToolActionApprovalFactEvaluator evaluator) {
        AgentToolActionResumeFactBundleProperties properties = new AgentToolActionResumeFactBundleProperties();
        InMemoryAgentAsyncTaskCommandOutboxStore outboxStore = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionResumeFactBundleService service = new AgentToolActionResumeFactBundleService(
                properties,
                evaluator,
                outboxStore,
                projectionStore,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        return new TestHarness(service, outboxStore, projectionStore);
    }

    private AgentToolActionResumeFactBundleQueryRequest request(List<String> requiredFactTypes) {
        return new AgentToolActionResumeFactBundleQueryRequest(
                "checkpoint-resume-001",
                "thread-resume-001",
                "session-resume",
                "run-resume",
                "taoc-resume-001",
                "async-command-outbox:taoc-resume-001",
                "approval-fact-approved-001",
                null,
                "datasource.metadata.read",
                "tool-readiness-policy.v1",
                10L,
                20L,
                "1001",
                requiredFactTypes,
                true,
                true
        );
    }

    private AgentAsyncTaskCommandOutboxRecord outboxRecord(String commandId, Long projectId) {
        return AgentAsyncTaskCommandOutboxRecord.pending(
                commandId,
                "idem-" + commandId,
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
                "datasmart.agent.tool.async.commands",
                "task-management",
                "session-resume",
                "run-resume",
                "audit-resume-001",
                "datasource.metadata.read",
                "agent-runtime",
                "http://internal-service/tools",
                10L,
                projectId,
                30L,
                "1001",
                "trace-resume",
                "agent-payload:run-resume/sensitive-payload-key",
                "{\"sql\":\"select * from sensitive_table\",\"prompt\":\"raw prompt should not leak\"}",
                1024,
                Instant.parse("2026-06-16T00:00:00Z")
        );
    }

    private AgentRuntimeEventProjectionRecord receiptRecord(String identityKey,
                                                            String commandId,
                                                            boolean preCheckPassed,
                                                            String outcome,
                                                            long replaySequence) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("commandId", commandId);
        attributes.put("taskStatus", preCheckPassed ? "RUNNING" : "FAILED");
        attributes.put("outcome", outcome);
        attributes.put("preCheckPassed", preCheckPassed);
        attributes.put("sideEffectExecuted", false);
        attributes.put("errorCode", preCheckPassed ? null : "AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED");
        attributes.put("message", "prompt raw prompt should not leak; select * from sensitive_table");
        attributes.put("payloadBody", Map.of("secret", "hidden"));
        Instant timestamp = Instant.parse("2026-06-16T00:00:0" + replaySequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "TASK_MANAGEMENT",
                AgentToolActionControlledDryRunReceiptService.EVENT_TYPE,
                "controlled_tool_action_receipt",
                "receipt",
                preCheckPassed ? "audit" : "warning",
                "10",
                "20",
                "1001",
                "trace-resume",
                "run-resume",
                "session-resume",
                replaySequence,
                replaySequence,
                timestamp,
                timestamp,
                timestamp,
                attributes
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-resume",
                "PROJECT",
                List.of(20L)
        );
    }

    private AgentToolActionApprovalFactEvaluator approvedEvaluator() {
        return ignored -> new AgentToolActionApprovalFactRemoteResult(
                "approval-fact-approved-001",
                true,
                false,
                "APPROVED",
                "internal approval reason should not leak",
                "APPROVED",
                "tool-readiness-policy.v1",
                null,
                List.of("APPROVAL_FACT_APPROVED", "APPROVAL_FACT_SCOPE_MATCHED"),
                List.of()
        );
    }

    private AgentToolActionApprovalFactEvaluator rejectedEvaluator() {
        return ignored -> new AgentToolActionApprovalFactRemoteResult(
                "approval-fact-approved-001",
                false,
                false,
                "REJECTED",
                "internal rejection reason should not leak",
                "REJECTED",
                "tool-readiness-policy.v1",
                null,
                List.of(),
                List.of("APPROVAL_FACT_REJECTED_BY_PERMISSION_ADMIN")
        );
    }

    private record TestHarness(
            AgentToolActionResumeFactBundleService service,
            InMemoryAgentAsyncTaskCommandOutboxStore outboxStore,
            InMemoryAgentRuntimeEventProjectionStore projectionStore
    ) {
    }
}
