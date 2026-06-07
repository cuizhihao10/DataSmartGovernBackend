/**
 * @Author : Cui
 * @Date: 2026/06/07 13:39
 * @Description DataSmart Govern Backend - AgentToolActionIntakeProjectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 工具动作入口投影查询服务测试。
 *
 * <p>这组测试保护 Java 控制面对 Python `tool_action_intake_recorded` 事件的解释能力。
 * 事件中会故意混入 arguments、prompt、SQL、payload 和 internalEndpoint，验证投影服务只读取低敏白名单。</p>
 */
class AgentToolActionIntakeProjectionServiceTest {

    @Test
    void querySnapshotsShouldParseToolActionIntakeAttributesAndAggregateWindow() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(intakeRecord("intake-1", "20", "run-intake", 1, false));
        store.append(nonIntakeRuntimeEvent("tool-planned-1", "20", "run-intake", 2));
        store.append(intakeRecord("intake-other-project", "30", "run-intake", 3, true));
        AgentToolActionIntakeProjectionService service = new AgentToolActionIntakeProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentToolActionIntakeProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-intake", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals("runtime-event-projection-fallback", response.indexSource());
        assertEquals(1L, response.acceptedWindowCount());
        assertEquals(0L, response.rejectedBeforeReadinessWindowCount());
        assertEquals(1L, response.readinessExecutableWindowCount());
        assertEquals(1L, response.readinessApprovalRequiredWindowCount());
        assertEquals(2L, response.boundaryCounts().get("TOOL_PLAN_READINESS_GRAPH"));
        assertEquals(1L, response.graphBranchCounts().get("READY_TO_EXECUTE"));
        assertEquals(1L, response.toolNameCounts().get("datasource.metadata.read"));
        assertEquals(1L, response.issueCodeCounts().get("MODEL_TOOL_CALL_APPROVAL_REQUIRED"));
        assertEquals(1L, response.nextActionCounts().get("EXECUTE_READY_TOOLS"));

        AgentToolActionIntakeProjectionView snapshot = response.snapshots().getFirst();
        assertEquals("tool_action_intake_recorded", snapshot.eventType());
        assertEquals("TOOL_ACTION_INTAKE", snapshot.snapshotType());
        assertEquals("LOW_SENSITIVE_TOOL_ACTION_INTAKE_EVENT_ONLY", snapshot.payloadPolicy());
        assertEquals("datasmart.python-ai-runtime.mcp-tools-call-intake-preview.v1", snapshot.previewSchemaVersion());
        assertEquals("MCP", snapshot.protocolFamily());
        assertEquals(true, snapshot.previewOnly());
        assertEquals(false, snapshot.toolExecutionEnabled());
        assertEquals(true, snapshot.jsonRpcDetected());
        assertEquals(true, snapshot.methodAccepted());
        assertEquals(true, snapshot.callDetected());
        assertEquals(2, snapshot.sensitiveFieldIgnoredCount());
        assertEquals("MCP_TOOLS_CALL", snapshot.intakeSource());
        assertEquals(2, snapshot.acceptedToolPlanCount());
        assertEquals(0, snapshot.rejectedBeforeReadinessCount());
        assertEquals(List.of("MODEL_TOOL_CALL_APPROVAL_REQUIRED"), snapshot.issueCodes());
        assertEquals(1, snapshot.readinessExecutableCount());
        assertEquals(1, snapshot.readinessApprovalRequiredCount());
        assertEquals(List.of("EXECUTE_READY_TOOLS", "CREATE_OR_WAIT_APPROVAL"), snapshot.readinessNextActions());
        assertEquals(List.of("READY_LOW_RISK_SYNC", "HUMAN_APPROVAL_REQUIRED"), snapshot.readinessReasonCodes());
        assertEquals("PRE_EXECUTION_CONDITION_GRAPH_ONLY", snapshot.graphExecutionBoundary());
        assertEquals(4, snapshot.graphNodeCount());
        assertEquals(3, snapshot.graphEdgeCount());
        assertEquals(false, snapshot.graphToolExecuted());
        assertEquals(false, snapshot.graphOutboxWritten());
        assertEquals(false, snapshot.graphApprovalCreated());
        assertEquals(false, snapshot.productionReadyForExecution());
        assertEquals("task.create.draft", snapshot.decisionSummaries().get(1).toolName());
        assertEquals("waiting_approval", snapshot.decisionSummaries().get(1).decision());

        String serialized = response.toString();
        assertFalse(serialized.contains("ds-sensitive-intake"));
        assertFalse(serialized.contains("手机号唯一性"));
        assertFalse(serialized.contains("select * from"));
        assertFalse(serialized.contains("raw prompt"));
        assertFalse(serialized.contains("http://internal-service"));
        assertFalse(serialized.contains("arguments"));
        assertFalse(serialized.contains("businessGoal"));
    }

    @Test
    void querySnapshotsShouldUseReplaySequenceForIncrementalRead() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(intakeRecord("intake-1", "20", "run-intake", 1, false));
        store.append(intakeRecord("intake-2", "20", "run-intake", 2, true));
        AgentToolActionIntakeProjectionService service = new AgentToolActionIntakeProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentToolActionIntakeProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-intake", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.rejectedBeforeReadinessWindowCount());
        assertEquals(1L, response.boundaryCounts().get("REJECTED_BEFORE_READINESS"));
        assertEquals(2L, response.snapshots().getFirst().replaySequence());
        assertEquals(1, response.snapshots().getFirst().rejectedBeforeReadinessCount());
        assertEquals(List.of("MODEL_TOOL_CALL_NOT_EXPOSED"), response.snapshots().getFirst().issueCodes());
    }

    private AgentRuntimeEventProjectionRecord intakeRecord(String identityKey,
                                                           String projectId,
                                                           String runId,
                                                           long sequence,
                                                           boolean rejected) {
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
        attributes.put("sensitiveFieldIgnoredCount", 2);
        attributes.put("source", "MCP_TOOLS_CALL");
        attributes.put("totalCount", rejected ? 1 : 2);
        attributes.put("acceptedToolPlanCount", rejected ? 0 : 2);
        attributes.put("rejectedBeforeReadinessCount", rejected ? 1 : 0);
        attributes.put("boundaryCounts", rejected
                ? Map.of("REJECTED_BEFORE_READINESS", 1)
                : Map.of("TOOL_PLAN_READINESS_GRAPH", 2));
        attributes.put("issueCodes", rejected
                ? List.of("MODEL_TOOL_CALL_NOT_EXPOSED")
                : List.of("MODEL_TOOL_CALL_APPROVAL_REQUIRED"));
        attributes.put("blockingIssueCount", rejected ? 1 : 0);
        attributes.put("toolNames", rejected
                ? List.of("datasource.metadata.read")
                : List.of("datasource.metadata.read", "task.create.draft"));
        attributes.put("toolNamesTruncatedCount", 0);
        attributes.put("readinessTotalCount", rejected ? 0 : 2);
        attributes.put("readinessExecutableCount", rejected ? 0 : 1);
        attributes.put("readinessApprovalRequiredCount", rejected ? 0 : 1);
        attributes.put("readinessClarificationRequiredCount", 0);
        attributes.put("readinessDraftOnlyCount", 0);
        attributes.put("readinessQueuedAsyncCount", 0);
        attributes.put("readinessThrottledCount", 0);
        attributes.put("readinessBlockedCount", 0);
        attributes.put("readinessHasBlockingDecision", false);
        attributes.put("readinessNextActions", rejected
                ? List.of()
                : List.of("EXECUTE_READY_TOOLS", "CREATE_OR_WAIT_APPROVAL"));
        attributes.put("readinessReasonCodes", rejected
                ? List.of()
                : List.of("READY_LOW_RISK_SYNC", "HUMAN_APPROVAL_REQUIRED"));
        attributes.put("graphExecutionBoundary", "PRE_EXECUTION_CONDITION_GRAPH_ONLY");
        attributes.put("graphNodeCount", rejected ? 2 : 4);
        attributes.put("graphEdgeCount", rejected ? 1 : 3);
        attributes.put("graphBranches", rejected
                ? List.of("NO_TOOL_PLAN")
                : List.of("READY_TO_EXECUTE", "WAITING_APPROVAL"));
        attributes.put("graphBranchCounts", rejected
                ? Map.of("NO_TOOL_PLAN", 1)
                : Map.of("READY_TO_EXECUTE", 1, "WAITING_APPROVAL", 1));
        attributes.put("graphToolExecuted", false);
        attributes.put("graphOutboxWritten", false);
        attributes.put("graphApprovalCreated", false);
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
                        Map.entry("arguments", Map.of("datasourceId", "ds-sensitive-intake")),
                        Map.entry("payload", Map.of("businessGoal", "手机号唯一性"))
                )
        ));
        attributes.put("arguments", Map.of("datasourceId", "ds-sensitive-intake"));
        attributes.put("prompt", "raw prompt should not be exposed");
        attributes.put("sql", "select * from sensitive_table");
        attributes.put("internalEndpoint", "http://internal-service/tools");
        return eventRecord(identityKey, "tool_action_intake_recorded", projectId,
                runId, sequence, rejected ? "warning" : "audit", attributes);
    }

    private AgentRuntimeEventProjectionRecord nonIntakeRuntimeEvent(String identityKey,
                                                                    String projectId,
                                                                    String runId,
                                                                    long sequence) {
        return eventRecord(identityKey, "tool_planned", projectId, runId, sequence,
                "info", Map.of("toolName", "datasource.metadata.read"));
    }

    private AgentRuntimeEventProjectionRecord eventRecord(String identityKey,
                                                          String eventType,
                                                          String projectId,
                                                          String runId,
                                                          long sequence,
                                                          String severity,
                                                          Map<String, Object> attributes) {
        Instant timestamp = Instant.parse("2026-06-07T05:40:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                "record_tool_action_intake",
                "已记录工具动作意图入口治理快照。",
                severity,
                "10",
                projectId,
                "1001",
                "request-intake",
                runId,
                "session-intake",
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
                "trace-tool-action-intake-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
