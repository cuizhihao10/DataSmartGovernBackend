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

    @Test
    void checkpointOnlyQueryShouldResolveFactsFromLocatorIndex() throws JsonProcessingException {
        TestHarness harness = harness(approvedEvaluator());
        harness.outboxStore().append(outboxRecord("taoc-resume-001", 20L));
        harness.projectionStore().append(receiptRecord("receipt-ready", "taoc-resume-001", true, "DRY_RUN_PASSED", 1L));

        /*
         * 第一次查询模拟 Python 5.73/5.75 已经从 checkpoint 摘要派生出低敏 locator hints。
         * Java 在这次查询中会把 checkpointId/threadId 与 command/outbox/approval 等定位符写入 locator index。
         */
        harness.service().query(
                request(List.of("APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION", "WORKER_RECEIPT_PROJECTION")),
                projectOwnerContext()
        );

        /*
         * 第二次查询只提交 checkpointId，不再重复传 commandId、outboxId、approvalFactId。
         * 如果 locator index 生效，Java 应能先补齐定位符，再沿用原有 fact bundle 逻辑查 outbox、receipt 和审批事实。
         */
        AgentToolActionResumeFactBundleResponse response = harness.service().query(
                checkpointOnlyRequest(),
                projectOwnerContext()
        );
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertTrue((Boolean) response.requestedLocator().get("locatorIndexHit"));
        assertTrue(((List<?>) response.requestedLocator().get("locatorIndexEvidenceCodes"))
                .contains("LOCATOR_INDEX_FILLED_MISSING_FIELDS"));
        assertEquals("taoc-resume-001", response.requestedLocator().get("commandId"));
        assertEquals(true, response.requestedLocator().get("approvalFactIdPresent"));
        assertEquals(List.of(
                "APPROVAL_CONFIRMATION_FACT",
                "OUTBOX_WRITE_CONFIRMATION",
                "WORKER_RECEIPT_PROJECTION"
        ), response.availableFactTypes());
        assertEquals("IN_MEMORY_CHECKPOINT_THREAD_TO_FACT_LOCATOR_INDEX",
                response.productionReadiness().get("currentLocatorIndexMode"));
        assertFalse(json.contains("approval-fact-approved-001"));
        assertFalse(json.contains("agent-payload:run-resume/sensitive-payload-key"));
        assertFalse(json.contains("raw prompt should not leak"));
    }

    @Test
    void clarificationFactShouldBeReadFromServerSideStore() throws JsonProcessingException {
        TestHarness harness = harness(approvedEvaluator());
        harness.clarificationStore().upsert(clarificationFactRecord("clarification-fact-ready-001", 20L,
                AgentToolActionClarificationFactRecord.STATUS_AVAILABLE));

        AgentToolActionResumeFactBundleResponse response = harness.service().query(
                requestWithClarification(List.of("CLARIFICATION_FACT"), "clarification-fact-ready-001"),
                projectOwnerContext()
        );

        assertEquals(List.of("CLARIFICATION_FACT"), response.availableFactTypes());
        assertTrue(response.missingFactTypes().isEmpty());
        assertTrue(response.facts().getFirst().evidenceCodes().contains("CLARIFICATION_FACT_CONTENT_NOT_EXPOSED"));

        /*
         * 澄清事实只证明“用户已经通过受控入口补充过信息”，不保存也不回显补充原文。
         * 这里用 JSON 级断言保护最终 HTTP 响应不会泄露 factId 之外的澄清内容、prompt 或 SQL。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("user clarification raw answer should not leak"));
        assertFalse(json.contains("select * from sensitive_table"));
        assertFalse(json.contains("raw prompt should not leak"));
    }

    @Test
    void queryShouldPublishLowSensitiveDiagnosticTimelineEvent() throws JsonProcessingException {
        TestHarness harness = harness(approvedEvaluator());
        harness.outboxStore().append(outboxRecord("taoc-resume-001", 20L));
        harness.projectionStore().append(receiptRecord("receipt-ready", "taoc-resume-001", true, "DRY_RUN_PASSED", 1L));

        harness.service().query(
                request(List.of("APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION", "WORKER_RECEIPT_PROJECTION")),
                projectOwnerContext()
        );

        List<AgentRuntimeEventProjectionRecord> diagnosticRecords = harness.projectionStore().query(
                new AgentRuntimeEventProjectionQuery(
                        "10",
                        "20",
                        "1001",
                        null,
                        "run-resume",
                        "session-resume",
                        AgentToolActionResumeFactBundleDiagnosticPublisher.EVENT_TYPE,
                        null,
                        10,
                        List.of("20")
                )
        );

        assertEquals(1, diagnosticRecords.size());
        AgentRuntimeEventProjectionRecord record = diagnosticRecords.getFirst();
        Map<String, Object> attributes = record.attributes();
        assertEquals("resume_fact_bundle_ready_preview_only", record.stage());
        assertEquals("audit", record.severity());
        assertEquals(true, attributes.get("previewOnly"));
        assertEquals(true, attributes.get("locatorIndexHit"));
        assertEquals(3, attributes.get("availableFactTypeCount"));
        assertEquals(0, attributes.get("missingFactTypeCount"));
        assertEquals(0, attributes.get("rejectedFactTypeCount"));
        assertEquals(3, ((List<?>) attributes.get("factSummaries")).size());
        assertEquals(true, ((Map<?, ?>) attributes.get("outboxSummary")).get("present"));
        assertEquals(true, ((Map<?, ?>) attributes.get("receiptSummary")).get("present"));
        assertEquals(1, ((Map<?, ?>) attributes.get("receiptSummary")).get("receiptCount"));
        assertEquals("PROJECT_OWNER", ((Map<?, ?>) attributes.get("securityBoundary")).get("actorRole"));
        assertEquals(1, ((Map<?, ?>) attributes.get("securityBoundary")).get("authorizedProjectCount"));

        /*
         * 诊断事件会进入统一 timeline，因此要像 HTTP 响应一样做最终序列化级别的泄露检查。
         * 这里重点确认：事件只写事实类型/状态/计数，不扩散 approvalFactId、payload key、内部 endpoint、
         * SQL、prompt 或 payloadJson。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(record);
        assertFalse(json.contains("approval-fact-approved-001"));
        assertFalse(json.contains("async-command-outbox:taoc-resume-001"));
        assertFalse(json.contains("agent-payload:run-resume/sensitive-payload-key"));
        assertFalse(json.contains("http://internal-service/tools"));
        assertFalse(json.contains("select * from sensitive_table"));
        assertFalse(json.contains("raw prompt should not leak"));
        assertFalse(json.contains("payloadJson"));
    }

    private TestHarness harness(AgentToolActionApprovalFactEvaluator evaluator) {
        AgentToolActionResumeFactBundleProperties properties = new AgentToolActionResumeFactBundleProperties();
        InMemoryAgentAsyncTaskCommandOutboxStore outboxStore = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentToolActionResumeLocatorIndexStore locatorIndexStore = new InMemoryAgentToolActionResumeLocatorIndexStore();
        AgentToolActionWorkerReceiptIndexService receiptIndexService = new AgentToolActionWorkerReceiptIndexService(
                new InMemoryAgentToolActionWorkerReceiptIndexStore(100)
        );
        AgentToolActionClarificationFactStore clarificationStore =
                new InMemoryAgentToolActionClarificationFactStore(properties);
        AgentToolActionResumeFactBundleService service = new AgentToolActionResumeFactBundleService(
                properties,
                evaluator,
                outboxStore,
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentToolActionResumeLocatorIndexService(locatorIndexStore),
                new AgentToolActionClarificationFactEvaluator(clarificationStore),
                new AgentToolActionWorkerReceiptFactEvaluator(receiptIndexService, projectionStore),
                new AgentToolActionResumeFactBundleDiagnosticPublisher(projectionStore)
        );
        return new TestHarness(service, outboxStore, projectionStore, clarificationStore);
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

    private AgentToolActionResumeFactBundleQueryRequest checkpointOnlyRequest() {
        return new AgentToolActionResumeFactBundleQueryRequest(
                "checkpoint-resume-001",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10L,
                20L,
                "1001",
                null,
                true,
                true
        );
    }

    private AgentToolActionResumeFactBundleQueryRequest requestWithClarification(List<String> requiredFactTypes,
                                                                                 String clarificationFactId) {
        return new AgentToolActionResumeFactBundleQueryRequest(
                "checkpoint-resume-001",
                "thread-resume-001",
                "session-resume",
                "run-resume",
                "taoc-resume-001",
                "async-command-outbox:taoc-resume-001",
                "approval-fact-approved-001",
                clarificationFactId,
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

    private AgentToolActionClarificationFactRecord clarificationFactRecord(String clarificationFactId,
                                                                           Long projectId,
                                                                           String status) {
        return new AgentToolActionClarificationFactRecord(
                clarificationFactId,
                "session-resume",
                "run-resume",
                "taoc-resume-001",
                "datasource.metadata.read",
                "tool-readiness-policy.v1",
                "10",
                String.valueOf(projectId),
                "1001",
                status,
                List.of("USER_CLARIFICATION_CAPTURED"),
                List.of(),
                Instant.parse("2026-12-31T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:00:00Z")
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
            InMemoryAgentRuntimeEventProjectionStore projectionStore,
            AgentToolActionClarificationFactStore clarificationStore
    ) {
    }
}
