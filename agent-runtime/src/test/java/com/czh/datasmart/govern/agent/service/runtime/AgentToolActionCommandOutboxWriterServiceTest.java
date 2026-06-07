/**
 * @Author : Cui
 * @Date: 2026/06/07 15:16
 * @Description DataSmart Govern Backend - AgentToolActionCommandOutboxWriterServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandOutboxWriteResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.InMemoryAgentAsyncTaskCommandOutboxStore;
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
 * 工具动作 command outbox writer 测试。
 *
 * <p>本测试验证 writer 的核心边界：只有 READY proposal 才能形成 outbox record；重复写入必须走 commandId
 * 幂等复用；等待审批或不安全引用不能靠调用 writer 绕过；写入的 payloadJson 也必须保持低敏命令信封形态。</p>
 */
class AgentToolActionCommandOutboxWriterServiceTest {

    @Test
    void writeShouldAppendReadyProposalAsSafeCommandEnvelope() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView readyGraph = graphByTool(services.graphService(), "datasource.metadata.read");

        AgentToolActionCommandOutboxWriteResponse response = services.writerService().write(
                readyRequest(readyGraph),
                projectOwnerContext()
        );

        assertEquals("ENQUEUED", response.writerState());
        assertEquals(true, response.enqueued());
        assertEquals(false, response.duplicate());
        assertNotNull(response.record());
        assertTrue(response.commandId().startsWith("taoc_"));
        assertEquals(AgentAsyncTaskCommandOutboxStatus.PENDING.name(), response.record().status());
        assertEquals("AGENT_TOOL_ACTION_CONTROLLED_COMMAND", response.record().commandType());
        assertEquals("datasmart.agent.tool.async.commands", response.record().commandTopic());
        assertEquals("task-management", response.record().consumerService());

        AgentAsyncTaskCommandOutboxRecord record = services.outboxStore()
                .findByCommandId(response.commandId())
                .orElseThrow();
        assertEquals("datasmart.agent.async-task-command.v1", record.schemaVersion());
        assertEquals("agent-payload:run-proposal/datasource-metadata-read", record.payloadReference());
        assertEquals("agent-runtime", record.targetService());
        assertNull(record.targetEndpoint());
        assertTrue(record.payloadJson().contains("\"schemaVersion\":\"datasmart.agent.async-task-command.v1\""));
        assertTrue(record.payloadJson().contains("\"proposalCommandSchemaVersion\":\"agent-tool-action-command.v1\""));
        assertTrue(record.payloadJson().contains("\"auditId\":\"" + record.auditId() + "\""));
        assertTrue(record.payloadJson().contains("\"toolCode\":\"datasource.metadata.read\""));
        assertTrue(record.payloadJson().contains("\"targetService\":\"agent-runtime\""));
        assertTrue(record.payloadJson().contains("\"source\":\"TOOL_ACTION_COMMAND_PROPOSAL\""));
        assertTrue(record.payloadJson().contains("\"payloadReferenceVerificationStatus\":\"VERIFIED\""));
        assertTrue(record.payloadJson().contains("\"factEvidenceVerificationStatus\":\"VERIFIED_OR_NOT_REQUIRED\""));
        assertTrue(record.payloadJson().contains("\"serverSideVerificationRequired\":true"));
        assertFalse(record.payloadJson().contains("ds-sensitive-proposal"));
        assertFalse(record.payloadJson().contains("select * from"));
        assertFalse(record.payloadJson().contains("raw prompt"));
        assertFalse(record.payloadJson().contains("http://internal-service"));
        assertFalse(record.payloadJson().contains("businessGoal"));
    }

    @Test
    void writeShouldReuseExistingOutboxRecordByStableCommandId() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView readyGraph = graphByTool(services.graphService(), "datasource.metadata.read");
        AgentToolActionCommandProposalRequest request = readyRequest(readyGraph);

        AgentToolActionCommandOutboxWriteResponse first = services.writerService().write(request, projectOwnerContext());
        AgentToolActionCommandOutboxWriteResponse second = services.writerService().write(request, projectOwnerContext());

        assertEquals("ENQUEUED", first.writerState());
        assertEquals("DUPLICATE_REUSED", second.writerState());
        assertEquals(first.commandId(), second.commandId());
        assertEquals(false, second.enqueued());
        assertEquals(true, second.duplicate());
        assertEquals(1, services.outboxStore().list("run-proposal", null, 10).size());
    }

    @Test
    void writeShouldBlockNonReadyProposalWithoutAppendingOutbox() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView approvalGraph = graphByState(services.graphService(), "WAITING_APPROVAL");

        AgentToolActionCommandOutboxWriteResponse response = services.writerService().write(
                new AgentToolActionCommandProposalRequest(
                        approvalGraph.graphId(),
                        approvalGraph.contractId(),
                        "10",
                        null,
                        null,
                        null,
                        "run-proposal",
                        null,
                        null,
                        20,
                        "https://internal-service/payload/raw",
                        "approval-001",
                        null,
                        "tool-readiness-policy.v1",
                        "agent-tool-action-command.v1",
                        "REQUIRED",
                        "client-request-approval"
                ),
                projectOwnerContext()
        );

        assertEquals("BLOCKED_BY_PROPOSAL", response.writerState());
        assertEquals(false, response.enqueued());
        assertNull(response.record());
        assertEquals(0, services.outboxStore().list(null, null, 10).size());
        assertTrue(response.summaryReasons().stream().anyMatch(reason -> reason.contains("不会写入")));
    }

    @Test
    void writeShouldBlockPayloadReferenceThatDoesNotBelongToCurrentRun() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView readyGraph = graphByTool(services.graphService(), "datasource.metadata.read");

        AgentToolActionCommandOutboxWriteResponse response = services.writerService().write(
                new AgentToolActionCommandProposalRequest(
                        readyGraph.graphId(),
                        readyGraph.contractId(),
                        "10",
                        null,
                        null,
                        null,
                        "run-proposal",
                        null,
                        null,
                        20,
                        "agent-payload:another-run/datasource-metadata-read",
                        null,
                        null,
                        "tool-readiness-policy.v1",
                        "agent-tool-action-command.v1",
                        "REQUIRED",
                        "client-request-wrong-run"
                ),
                projectOwnerContext()
        );

        assertEquals("BLOCKED_BY_SERVER_VERIFICATION", response.writerState());
        assertEquals(false, response.enqueued());
        assertNull(response.record());
        assertTrue(response.summaryReasons().stream().anyMatch(reason -> reason.contains("agent-payload")));
        assertEquals(0, services.outboxStore().list(null, null, 10).size());
    }

    @Test
    void writeShouldBlockUnsafeFactEvidenceEvenWhenProposalIsReady() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView readyGraph = graphByTool(services.graphService(), "datasource.metadata.read");

        AgentToolActionCommandOutboxWriteResponse response = services.writerService().write(
                new AgentToolActionCommandProposalRequest(
                        readyGraph.graphId(),
                        readyGraph.contractId(),
                        "10",
                        null,
                        null,
                        null,
                        "run-proposal",
                        null,
                        null,
                        20,
                        "agent-payload:run-proposal/datasource-metadata-read",
                        "https://internal-service/approval/raw",
                        null,
                        "tool-readiness-policy.v1",
                        "agent-tool-action-command.v1",
                        "REQUIRED",
                        "client-request-unsafe-fact"
                ),
                projectOwnerContext()
        );

        assertEquals("BLOCKED_BY_SERVER_VERIFICATION", response.writerState());
        assertEquals(false, response.enqueued());
        assertNull(response.record());
        assertTrue(response.summaryReasons().stream().anyMatch(reason -> reason.contains("人工事实证据")));
        assertEquals(0, services.outboxStore().list(null, null, 10).size());
    }

    private AgentToolActionCommandProposalRequest readyRequest(AgentToolActionExecutionGraphView readyGraph) {
        return new AgentToolActionCommandProposalRequest(
                readyGraph.graphId(),
                readyGraph.contractId(),
                "10",
                null,
                null,
                null,
                "run-proposal",
                null,
                null,
                20,
                "agent-payload:run-proposal/datasource-metadata-read",
                null,
                null,
                "tool-readiness-policy.v1",
                "agent-tool-action-command.v1",
                "REQUIRED",
                "client-request-001"
        );
    }

    private TestServices servicesWithEvents() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        projectionStore.append(intakeRecord("intake-ready", "20", "run-proposal", 1, "ready"));
        projectionStore.append(intakeRecord("intake-rejected", "20", "run-proposal", 2, "rejected"));
        AgentToolActionIntakeProjectionService projectionService = new AgentToolActionIntakeProjectionService(
                projectionStore,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        AgentToolActionIntakeDurableActionContractService contractService =
                new AgentToolActionIntakeDurableActionContractService(projectionService);
        AgentToolActionExecutionGraphPreviewService graphService =
                new AgentToolActionExecutionGraphPreviewService(contractService);
        AgentToolActionCommandProposalService proposalService =
                new AgentToolActionCommandProposalService(graphService);
        AgentAsyncTaskCommandOutboxProperties outboxProperties = new AgentAsyncTaskCommandOutboxProperties();
        InMemoryAgentAsyncTaskCommandOutboxStore outboxStore =
                new InMemoryAgentAsyncTaskCommandOutboxStore(outboxProperties);
        AgentToolActionCommandOutboxWriterService writerService =
                new AgentToolActionCommandOutboxWriterService(
                        outboxProperties,
                        new AgentRuntimeProperties(),
                        proposalService,
                        new AgentToolActionPayloadReferenceVerifier(),
                        new AgentToolActionFactEvidenceVerifier(),
                        outboxStore,
                        new ObjectMapper()
                );
        return new TestServices(graphService, writerService, outboxStore);
    }

    private AgentToolActionExecutionGraphView graphByTool(
            AgentToolActionExecutionGraphPreviewService graphService,
            String toolName) {
        return queryGraphs(graphService).graphs().stream()
                .filter(graph -> toolName.equals(graph.toolName()))
                .findFirst()
                .orElseThrow();
    }

    private AgentToolActionExecutionGraphView graphByState(
            AgentToolActionExecutionGraphPreviewService graphService,
            String state) {
        return queryGraphs(graphService).graphs().stream()
                .filter(graph -> state.equals(graph.graphState()))
                .findFirst()
                .orElseThrow();
    }

    private AgentToolActionExecutionGraphQueryResponse queryGraphs(
            AgentToolActionExecutionGraphPreviewService graphService) {
        return graphService.queryExecutionGraphs(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-proposal", null, null, null, 20),
                projectOwnerContext()
        );
    }

    private AgentRuntimeEventProjectionRecord intakeRecord(String identityKey,
                                                           String projectId,
                                                           String runId,
                                                           long sequence,
                                                           String mode) {
        boolean rejected = "rejected".equals(mode);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventPayloadVersion", "v1");
        attributes.put("snapshotType", "TOOL_ACTION_INTAKE");
        attributes.put("payloadPolicy", "LOW_SENSITIVE_TOOL_ACTION_INTAKE_EVENT_ONLY");
        attributes.put("schemaVersion", "datasmart.python-ai-runtime.mcp-tools-call-intake-preview.v1");
        attributes.put("protocolFamily", "MCP");
        attributes.put("previewOnly", true);
        attributes.put("toolExecutionEnabled", false);
        attributes.put("source", "MCP_TOOLS_CALL");
        attributes.put("totalCount", rejected ? 1 : 2);
        attributes.put("acceptedToolPlanCount", rejected ? 0 : 2);
        attributes.put("rejectedBeforeReadinessCount", rejected ? 1 : 0);
        attributes.put("issueCodes", rejected
                ? List.of("MODEL_TOOL_CALL_NOT_EXPOSED")
                : List.of("MODEL_TOOL_CALL_APPROVAL_REQUIRED"));
        attributes.put("toolNames", rejected
                ? List.of("datasource.metadata.read")
                : List.of("datasource.metadata.read", "task.create.draft"));
        attributes.put("readinessExecutableCount", rejected ? 0 : 1);
        attributes.put("readinessApprovalRequiredCount", rejected ? 0 : 1);
        attributes.put("readinessClarificationRequiredCount", 0);
        attributes.put("readinessThrottledCount", 0);
        attributes.put("readinessBlockedCount", 0);
        attributes.put("readinessReasonCodes", rejected
                ? List.of()
                : List.of("READY_LOW_RISK_SYNC", "HUMAN_APPROVAL_REQUIRED"));
        attributes.put("graphOutboxWritten", false);
        attributes.put("graphWorkerReceiptRequiredForSideEffects", true);
        attributes.put("productionReadyForExecution", false);
        attributes.put("missingProductionRequirements", List.of("OUTBOX_COMMAND_AND_WORKER_RECEIPT"));
        attributes.put("decisionSummaries", rejected ? List.of() : List.of(
                Map.of(
                        "toolName", "datasource.metadata.read",
                        "decision", "ready_to_execute",
                        "executable", true,
                        "queueRequired", false,
                        "requiresHumanApproval", false,
                        "parameterIssueCount", 0,
                        "issueCodes", List.of(),
                        "reasonCodes", List.of("READY_LOW_RISK_SYNC")
                ),
                Map.ofEntries(
                        Map.entry("toolName", "task.create.draft"),
                        Map.entry("decision", "waiting_approval"),
                        Map.entry("executable", false),
                        Map.entry("queueRequired", false),
                        Map.entry("requiresHumanApproval", true),
                        Map.entry("parameterIssueCount", 0),
                        Map.entry("issueCodes", List.of()),
                        Map.entry("reasonCodes", List.of("HUMAN_APPROVAL_REQUIRED")),
                        Map.entry("arguments", Map.of("datasourceId", "ds-sensitive-proposal")),
                        Map.entry("payload", Map.of("businessGoal", "proposal sensitive payload"))
                )
        ));
        attributes.put("arguments", Map.of("datasourceId", "ds-sensitive-proposal"));
        attributes.put("prompt", "raw prompt should not be exposed");
        attributes.put("sql", "select * from sensitive_table");
        attributes.put("internalEndpoint", "http://internal-service/tools");
        Instant timestamp = Instant.parse("2026-06-07T07:16:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "tool_action_intake_recorded",
                "record_tool_action_intake",
                "recorded tool action intake",
                rejected ? "warning" : "audit",
                "10",
                projectId,
                "1001",
                "request-proposal",
                runId,
                "session-proposal",
                sequence,
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
                "trace-tool-action-outbox-writer-test",
                "PROJECT",
                List.of(20L)
        );
    }

    private record TestServices(
            AgentToolActionExecutionGraphPreviewService graphService,
            AgentToolActionCommandOutboxWriterService writerService,
            InMemoryAgentAsyncTaskCommandOutboxStore outboxStore
    ) {
    }
}
