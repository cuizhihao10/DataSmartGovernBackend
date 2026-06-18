/**
 * @Author : Cui
 * @Date: 2026/06/18 01:39
 * @Description DataSmart Govern Backend - AgentToolActionResumeGateGraphPreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeGateGraphQueryResponse;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作恢复门控图预览服务测试。
 *
 * <p>本测试保护的是“从事实包到条件图”的语义，不是 worker 真正执行。
 * 对 Codex/Claude Code/OpenAI Agents/LangGraph 类 Agent Host 来说，恢复链路最危险的错误是：
 * 看到一个 approvalFactId 或 commandId 就直接恢复副作用。因此这里重点断言图必须同时呈现 locator、scope、fact gate
 * 和 final resume gate，并且 READY 也只能表示 resume-preview-ready。</p>
 */
class AgentToolActionResumeGateGraphPreviewServiceTest {

    @Test
    void readyFactsShouldBuildResumePreviewGraphWithoutSensitivePayload() throws JsonProcessingException {
        TestHarness harness = harness(approvedEvaluator());
        harness.outboxStore().append(outboxRecord("taoc-resume-001", 20L));
        harness.projectionStore().append(receiptRecord("receipt-ready", "taoc-resume-001",
                true, "DRY_RUN_PASSED", 1L));

        AgentToolActionResumeGateGraphQueryResponse response = harness.previewService().query(
                request(List.of(
                        "APPROVAL_CONFIRMATION_FACT",
                        "OUTBOX_WRITE_CONFIRMATION",
                        "WORKER_RECEIPT_PROJECTION"
                )),
                projectOwnerContext()
        );

        assertEquals("READY_FOR_RESUME_PREVIEW", response.graphState());
        assertEquals("READY_FOR_PYTHON_RESUME_PREVIEW_ONLY", response.terminalState());
        assertEquals(true, response.resumePreviewReady());
        assertEquals(6, response.nodeCount());
        assertEquals(8, response.edgeCount());
        assertEquals(1L, response.nodeTypeCounts().get("CHECKPOINT_LOCATOR"));
        assertEquals(1L, response.nodeTypeCounts().get("SECURITY_SCOPE"));
        assertEquals(1L, response.nodeTypeCounts().get("HUMAN_APPROVAL_FACT"));
        assertEquals(1L, response.nodeTypeCounts().get("OUTBOX_WRITE_FACT"));
        assertEquals(1L, response.nodeTypeCounts().get("WORKER_RECEIPT_FACT"));
        assertTrue(response.graph().nodes().stream()
                .anyMatch(node -> "resume-gate".equals(node.nodeId())
                        && "READY_FOR_PREVIEW_ONLY_RESUME".equals(node.status())));
        assertTrue(response.graph().edges().stream()
                .anyMatch(edge -> "OUTBOX_FACT_TO_WORKER_RECEIPT_FACT".equals(edge.edgeType())));
        assertTrue(response.recommendedActions().contains(
                "CALL_PYTHON_RUNTIME_RESUME_PREVIEW_WITHOUT_DIRECT_SIDE_EFFECT_EXECUTION"));

        /*
         * 这里做响应 JSON 级别的敏感信息保护。
         * 图可以告诉调用方“payloadReference 存在”，但不能返回 payloadReference 原文、payloadJson、内部 endpoint、
         * SQL、prompt、审批事实 ID 或 worker receipt message。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("approval-fact-approved-001"));
        assertFalse(json.contains("async-command-outbox:taoc-resume-001"));
        assertFalse(json.contains("agent-payload:run-resume/sensitive-payload-key"));
        assertFalse(json.contains("http://internal-service/tools"));
        assertFalse(json.contains("select * from sensitive_table"));
        assertFalse(json.contains("raw prompt should not leak"));
        assertFalse(json.contains("payloadJson"));
    }

    @Test
    void rejectedApprovalFactShouldBlockResumeGateGraph() {
        TestHarness harness = harness(rejectedEvaluator());

        AgentToolActionResumeGateGraphQueryResponse response = harness.previewService().query(
                request(List.of("APPROVAL_CONFIRMATION_FACT")),
                projectOwnerContext()
        );

        assertEquals("RESUME_BLOCKED_BY_REJECTED_FACT", response.graphState());
        assertEquals("STOP_BEFORE_RESUME_PREVIEW", response.terminalState());
        assertEquals(false, response.resumePreviewReady());
        assertTrue(response.graph().rejectedFactTypes().contains("APPROVAL_CONFIRMATION_FACT"));
        assertTrue(response.graph().nodes().stream()
                .anyMatch(node -> "fact-approval-confirmation-fact".equals(node.nodeId())
                        && "FACT_REJECTED".equals(node.status())
                        && !Boolean.TRUE.equals(node.executable())));
        assertTrue(response.graph().nodes().stream()
                .anyMatch(node -> "resume-gate".equals(node.nodeId())
                        && node.blockedByNodeIds().contains("fact-approval-confirmation-fact")));
        assertTrue(response.recommendedActions().contains("FAIL_CLOSED_AND_RECREATE_REJECTED_CONTROL_PLANE_FACTS"));
    }

    @Test
    void missingOutboxAndReceiptShouldWaitForControlPlaneFacts() {
        TestHarness harness = harness(approvedEvaluator());

        AgentToolActionResumeGateGraphQueryResponse response = harness.previewService().query(
                request(List.of("APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION",
                        "WORKER_RECEIPT_PROJECTION")),
                projectOwnerContext()
        );

        assertEquals("WAITING_RESUME_FACTS", response.graphState());
        assertEquals("WAIT_FOR_CONTROL_PLANE_FACTS", response.terminalState());
        assertEquals(false, response.resumePreviewReady());
        assertTrue(response.graph().availableFactTypes().contains("APPROVAL_CONFIRMATION_FACT"));
        assertTrue(response.graph().missingFactTypes().contains("OUTBOX_WRITE_CONFIRMATION"));
        assertTrue(response.graph().missingFactTypes().contains("WORKER_RECEIPT_PROJECTION"));
        assertTrue(response.graph().nodes().stream()
                .anyMatch(node -> "resume-gate".equals(node.nodeId())
                        && node.missingRequirements().contains("missing:OUTBOX_WRITE_CONFIRMATION")));
        assertTrue(response.recommendedActions().contains("RETRY_AFTER_CONTROL_PLANE_FACTS_ARE_MATERIALIZED"));
    }

    private TestHarness harness(AgentToolActionApprovalFactEvaluator evaluator) {
        AgentToolActionResumeFactBundleProperties properties = new AgentToolActionResumeFactBundleProperties();
        InMemoryAgentAsyncTaskCommandOutboxStore outboxStore = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionWorkerReceiptIndexService receiptIndexService = new AgentToolActionWorkerReceiptIndexService(
                new InMemoryAgentToolActionWorkerReceiptIndexStore(100)
        );
        AgentToolActionResumeFactBundleService factBundleService = new AgentToolActionResumeFactBundleService(
                properties,
                evaluator,
                outboxStore,
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentToolActionResumeLocatorIndexService(new InMemoryAgentToolActionResumeLocatorIndexStore()),
                new AgentToolActionClarificationFactEvaluator(new InMemoryAgentToolActionClarificationFactStore(properties)),
                new AgentToolActionWorkerReceiptFactEvaluator(receiptIndexService, projectionStore),
                new AgentToolActionResumeFactBundleDiagnosticPublisher(projectionStore)
        );
        return new TestHarness(
                new AgentToolActionResumeGateGraphPreviewService(factBundleService),
                outboxStore,
                projectionStore
        );
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
                Instant.parse("2026-06-18T01:39:00Z")
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
        attributes.put("message", "raw prompt should not leak; select * from sensitive_table");
        attributes.put("payloadBody", Map.of("secret", "hidden"));
        Instant timestamp = Instant.parse("2026-06-18T01:39:0" + replaySequence + "Z");
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
            AgentToolActionResumeGateGraphPreviewService previewService,
            InMemoryAgentAsyncTaskCommandOutboxStore outboxStore,
            InMemoryAgentRuntimeEventProjectionStore projectionStore
    ) {
    }
}
