/**
 * @Author : Cui
 * @Date: 2026/06/07 14:27
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphPreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

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
 * 工具动作执行图预览服务测试。
 *
 * <p>本测试的重点是验证“执行图”仍然只是低敏控制面解释，不会因为图上出现 outbox/worker 节点就真的执行工具。
 * 对商业化 Agent Host 来说，这个边界很关键：前端和审计台需要看见完整治理路径，但真实副作用必须由专用确认 API、
 * outbox command builder 和 worker receipt 链路承接。</p>
 */
class AgentToolActionExecutionGraphPreviewServiceTest {

    @Test
    void queryExecutionGraphsShouldBuildGovernanceNodesWithoutSensitivePayload() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(intakeRecord("intake-ready", "20", "run-graph", 1, "ready"));
        store.append(intakeRecord("intake-rejected", "20", "run-graph", 2, "rejected"));
        store.append(intakeRecord("intake-other-project", "30", "run-graph", 3, "ready"));
        AgentToolActionExecutionGraphPreviewService service = graphService(store);

        AgentToolActionExecutionGraphQueryResponse response = service.queryExecutionGraphs(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-graph", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(3, response.sourceContractCount());
        assertEquals(3, response.totalGraphs());
        assertEquals(0L, response.readyForOutboxGraphCount());
        assertEquals(1L, response.waitingApprovalGraphCount());
        assertEquals(1L, response.blockedGraphCount());
        assertEquals(1L, response.graphStateCounts().get("WAITING_DURABLE_ACTION_EVIDENCE"));
        assertEquals(1L, response.graphStateCounts().get("WAITING_APPROVAL"));
        assertEquals(1L, response.graphStateCounts().get("REJECTED_BEFORE_READINESS"));
        assertEquals(3L, response.nodeTypeCounts().get("OUTBOX_COMMAND"));
        assertEquals(3L, response.nodeTypeCounts().get("WORKER_RECEIPT"));
        assertEquals(1L, response.nodeTypeCounts().get("HUMAN_APPROVAL"));
        assertTrue(response.recommendedActions().stream().anyMatch(action -> action.contains("command builder")));

        AgentToolActionExecutionGraphView readyGraph = response.graphs().stream()
                .filter(graph -> "datasource.metadata.read".equals(graph.toolName()))
                .findFirst()
                .orElseThrow();
        assertTrue(readyGraph.graphId().startsWith("tool-action-execution-graph:"));
        assertEquals("WAITING_DURABLE_ACTION_EVIDENCE", readyGraph.graphState());
        assertEquals("WAIT_FOR_PAYLOAD_OR_OUTBOX_EVIDENCE", readyGraph.terminalState());
        assertEquals(false, readyGraph.outboxWritableNow());
        assertTrue(readyGraph.requiredEvidence().contains("PAYLOAD_REFERENCE_NOT_RAW_ARGUMENTS"));
        assertTrue(readyGraph.missingRequirements().contains("PAYLOAD_REFERENCE_REQUIRED"));
        assertTrue(readyGraph.nodes().stream().anyMatch(node -> "READINESS_GATE".equals(node.nodeType())));
        assertTrue(readyGraph.nodes().stream().anyMatch(node -> "DURABLE_CONTRACT".equals(node.nodeType())));
        assertTrue(readyGraph.nodes().stream().anyMatch(node -> "OUTBOX_COMMAND".equals(node.nodeType())));
        assertTrue(readyGraph.edges().stream().anyMatch(edge -> "CONTRACT_TO_OUTBOX".equals(edge.edgeType())));

        AgentToolActionExecutionGraphView approvalGraph = response.graphs().stream()
                .filter(graph -> "WAITING_APPROVAL".equals(graph.graphState()))
                .findFirst()
                .orElseThrow();
        assertTrue(approvalGraph.nodes().stream().anyMatch(node -> "HUMAN_APPROVAL".equals(node.nodeType())));
        assertTrue(approvalGraph.edges().stream().anyMatch(edge -> "READINESS_TO_APPROVAL".equals(edge.edgeType())));

        String serialized = response.toString();
        assertFalse(serialized.contains("ds-sensitive-graph"));
        assertFalse(serialized.contains("select * from"));
        assertFalse(serialized.contains("raw prompt"));
        assertFalse(serialized.contains("http://internal-service"));
        assertFalse(serialized.contains("phone uniqueness sensitive payload"));
        assertFalse(serialized.contains("datasourceId"));
        assertFalse(serialized.contains("businessGoal"));
    }

    @Test
    void queryExecutionGraphsShouldHonorReplaySequenceForIncrementalGraphPreview() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(intakeRecord("intake-ready", "20", "run-graph", 1, "ready"));
        store.append(intakeRecord("intake-rejected", "20", "run-graph", 2, "rejected"));
        AgentToolActionExecutionGraphPreviewService service = graphService(store);

        AgentToolActionExecutionGraphQueryResponse response = service.queryExecutionGraphs(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-graph", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.sourceContractCount());
        assertEquals(1, response.totalGraphs());
        assertEquals("REJECTED_BEFORE_READINESS", response.graphs().getFirst().graphState());
        assertEquals("STOP_BEFORE_READINESS", response.graphs().getFirst().terminalState());
        assertEquals(2L, response.graphs().getFirst().sourceReplaySequence());
        assertTrue(response.graphs().getFirst().nodes().stream()
                .anyMatch(node -> "READINESS_GATE".equals(node.nodeType()) && "BLOCKED".equals(node.status())));
    }

    private AgentToolActionExecutionGraphPreviewService graphService(InMemoryAgentRuntimeEventProjectionStore store) {
        AgentToolActionIntakeProjectionService projectionService = new AgentToolActionIntakeProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        AgentToolActionIntakeDurableActionContractService contractService =
                new AgentToolActionIntakeDurableActionContractService(projectionService);
        return new AgentToolActionExecutionGraphPreviewService(contractService);
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
        attributes.put("jsonRpcDetected", true);
        attributes.put("methodAccepted", true);
        attributes.put("callDetected", true);
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
        attributes.put("readinessNextActions", rejected
                ? List.of()
                : List.of("EXECUTE_READY_TOOLS", "CREATE_OR_WAIT_APPROVAL"));
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
                        "reasonCodes", List.of("READY_LOW_RISK_SYNC"),
                        "retryHint", "NO_RUNTIME_RETRY_BEFORE_EXECUTION"
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
                        Map.entry("retryHint", "WAIT_FOR_CONTROL_PLANE"),
                        Map.entry("arguments", Map.of("datasourceId", "ds-sensitive-graph")),
                        Map.entry("payload", Map.of("businessGoal", "phone uniqueness sensitive payload"))
                )
        ));
        attributes.put("arguments", Map.of("datasourceId", "ds-sensitive-graph"));
        attributes.put("prompt", "raw prompt should not be exposed");
        attributes.put("sql", "select * from sensitive_table");
        attributes.put("internalEndpoint", "http://internal-service/tools");
        Instant timestamp = Instant.parse("2026-06-07T06:27:0" + sequence + "Z");
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
                "request-graph",
                runId,
                "session-graph",
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
                "trace-tool-action-graph-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
