/**
 * @Author : Cui
 * @Date: 2026/06/07 14:48
 * @Description DataSmart Govern Backend - AgentToolActionCommandProposalServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作 command proposal 服务测试。
 *
 * <p>本测试验证 proposal 层的边界：它可以判断是否具备进入正式 outbox writer 的最低条件，但不会写 outbox。
 * 这能防止“执行图上出现 OUTBOX 节点”被误解成“当前请求已经触发真实副作用”。</p>
 */
class AgentToolActionCommandProposalServiceTest {

    @Test
    void proposeShouldAllowReadyGraphToEnterOutboxConfirmationWhenEvidenceIsSafe() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView readyGraph = graphByTool(services.graphService(), "datasource.metadata.read");

        AgentToolActionCommandProposalResponse response = services.proposalService().propose(
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
                        null,
                        null,
                        "tool-readiness-policy.v1",
                        "agent-tool-action-command.v1",
                        "REQUIRED",
                        "client-request-001"
                ),
                projectOwnerContext()
        );

        assertEquals("READY_FOR_OUTBOX_CONFIRMATION", response.proposalState());
        assertEquals(true, response.outboxWriteAllowedByPreflight());
        assertEquals(readyGraph.graphId(), response.graphId());
        assertEquals(readyGraph.contractId(), response.contractId());
        assertEquals("AGENT_TOOL_ACTION_CONTROLLED_COMMAND", response.commandType());
        assertTrue(response.proposalId().startsWith("tool-action-command-proposal:"));
        assertTrue(response.acceptedEvidence().stream().anyMatch(evidence -> evidence.startsWith("PAYLOAD_REFERENCE:")));
        assertTrue(response.acceptedEvidence().contains("IDEMPOTENCY_KEY"));
        assertFalse(response.missingEvidence().contains("PAYLOAD_REFERENCE_REQUIRED"));
        assertFalse(response.missingEvidence().contains("POLICY_VERSION_REQUIRED"));

        String serialized = response.toString();
        assertFalse(serialized.contains("ds-sensitive-proposal"));
        assertFalse(serialized.contains("select * from"));
        assertFalse(serialized.contains("raw prompt"));
        assertFalse(serialized.contains("http://internal-service"));
        assertFalse(serialized.contains("businessGoal"));
    }

    @Test
    void proposeShouldNotBypassApprovalOrUnsafePayloadReference() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView approvalGraph = graphByState(services.graphService(), "WAITING_APPROVAL");

        AgentToolActionCommandProposalResponse response = services.proposalService().propose(
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

        assertEquals("WAITING_HUMAN_APPROVAL_FACT", response.proposalState());
        assertEquals(false, response.outboxWriteAllowedByPreflight());
        assertTrue(response.acceptedEvidence().stream()
                .anyMatch(evidence -> evidence.startsWith("HUMAN_APPROVAL_CONFIRMATION_ID:")));
        assertTrue(response.rejectedEvidence().contains("PAYLOAD_REFERENCE_UNSAFE_OR_INLINE"));
        assertTrue(response.rejectedEvidence().contains("HUMAN_APPROVAL_REQUIRES_SERVER_SIDE_VERIFICATION"));
        assertTrue(response.recommendedActions().stream().anyMatch(action -> action.contains("审批")));
    }

    @Test
    void proposeShouldBlockRejectedGraphEvenWhenEvidenceLooksComplete() {
        TestServices services = servicesWithEvents();
        AgentToolActionExecutionGraphView rejectedGraph = graphByState(services.graphService(), "REJECTED_BEFORE_READINESS");

        AgentToolActionCommandProposalResponse response = services.proposalService().propose(
                new AgentToolActionCommandProposalRequest(
                        rejectedGraph.graphId(),
                        rejectedGraph.contractId(),
                        "10",
                        null,
                        null,
                        null,
                        "run-proposal",
                        null,
                        null,
                        20,
                        "agent-payload:run-proposal/rejected-tool",
                        null,
                        null,
                        "tool-readiness-policy.v1",
                        "agent-tool-action-command.v1",
                        "REQUIRED",
                        "client-request-rejected"
                ),
                projectOwnerContext()
        );

        assertEquals("BLOCKED_BY_GRAPH_STATE", response.proposalState());
        assertEquals(false, response.outboxWriteAllowedByPreflight());
        assertEquals("NONE", response.commandType());
        assertTrue(response.missingEvidence().contains("VISIBLE_TOOL_OR_PROTOCOL_ACCEPTANCE_REQUIRED"));
    }

    private TestServices servicesWithEvents() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(intakeRecord("intake-ready", "20", "run-proposal", 1, "ready"));
        store.append(intakeRecord("intake-rejected", "20", "run-proposal", 2, "rejected"));
        AgentToolActionIntakeProjectionService projectionService = new AgentToolActionIntakeProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        AgentToolActionIntakeDurableActionContractService contractService =
                new AgentToolActionIntakeDurableActionContractService(projectionService);
        AgentToolActionExecutionGraphPreviewService graphService =
                new AgentToolActionExecutionGraphPreviewService(contractService);
        return new TestServices(graphService, new AgentToolActionCommandProposalService(graphService));
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
        Instant timestamp = Instant.parse("2026-06-07T06:48:0" + sequence + "Z");
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
                "trace-tool-action-proposal-test",
                "PROJECT",
                List.of(20L)
        );
    }

    private record TestServices(
            AgentToolActionExecutionGraphPreviewService graphService,
            AgentToolActionCommandProposalService proposalService
    ) {
    }
}
