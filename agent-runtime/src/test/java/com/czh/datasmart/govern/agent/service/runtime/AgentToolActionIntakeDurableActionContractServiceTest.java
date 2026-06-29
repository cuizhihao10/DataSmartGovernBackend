/**
 * @Author : Cui
 * @Date: 2026/06/07 14:04
 * @Description DataSmart Govern Backend - AgentToolActionIntakeDurableActionContractServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDurableActionContractQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDurableActionContractView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作入口 durable action 契约预览服务测试。
 *
 * <p>测试重点不是验证真实 outbox 写入，而是验证低敏 intake event 能否被解释成“进入 outbox 前的证据清单”。
 * 这可以防止未来有人把 MCP preview 事件直接当作可执行命令，绕过 payloadReference、审批、幂等和 worker receipt。</p>
 */
class AgentToolActionIntakeDurableActionContractServiceTest {

    @Test
    void queryContractsShouldExplainReadyApprovalAndRejectedIntakeWithoutSensitivePayload() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(intakeRecord("intake-ready", "20", "run-contract", 1, "ready"));
        store.append(intakeRecord("intake-rejected", "20", "run-contract", 2, "rejected"));
        store.append(intakeRecord("intake-other-project", "30", "run-contract", 3, "ready"));
        AgentToolActionIntakeProjectionService projectionService = new AgentToolActionIntakeProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        AgentToolActionIntakeDurableActionContractService service =
                new AgentToolActionIntakeDurableActionContractService(projectionService);

        AgentToolActionIntakeDurableActionContractQueryResponse response = service.queryContracts(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-contract", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(2, response.sourceSnapshotCount());
        assertEquals(3, response.totalContracts());
        assertEquals(1L, response.readyForDurableContractCount());
        assertEquals(1L, response.waitingApprovalCount());
        assertEquals(1L, response.blockedOrRejectedCount());
        assertEquals(0L, response.outboxWritableNowCount());
        assertEquals(1L, response.contractStateCounts().get("READY_FOR_DURABLE_ACTION_CONTRACT"));
        assertEquals(1L, response.contractStateCounts().get("WAITING_APPROVAL"));
        assertEquals(1L, response.contractStateCounts().get("REJECTED_BEFORE_READINESS"));
        assertEquals(2L, response.toolNameCounts().get("datasource.metadata.read"));
        assertEquals(3L, response.missingRequirementCounts().get("OUTBOX_RECORD_NOT_WRITTEN"));
        assertTrue(response.recommendedActions().stream().anyMatch(action -> action.contains("payloadReference")));

        AgentToolActionIntakeDurableActionContractView readyContract = response.contracts().stream()
                .filter(contract -> "READY_FOR_DURABLE_ACTION_CONTRACT".equals(contract.durableActionState()))
                .findFirst()
                .orElseThrow();
        assertTrue(readyContract.contractId().startsWith("tool-action-contract:"));
        assertTrue(readyContract.idempotencyKey().startsWith("tool-action-intake:"));
        assertEquals("AGENT_TOOL_ACTION_CONTROLLED_COMMAND", readyContract.outboxCommandType());
        assertEquals(false, readyContract.outboxWritableNow());
        assertEquals("PAYLOAD_REFERENCE_ONLY_NO_RAW_ARGUMENTS", readyContract.payloadPolicy());
        assertTrue(readyContract.requiredEvidence().contains("WORKER_RECEIPT"));
        assertTrue(readyContract.missingRequirements().contains("PAYLOAD_REFERENCE_REQUIRED"));
        assertTrue(readyContract.guardrailNotes().stream().anyMatch(note -> note.contains("不能替代真实 outbox command")));

        String serialized = response.toString();
        assertFalse(serialized.contains("ds-sensitive-contract"));
        assertFalse(serialized.contains("select * from"));
        assertFalse(serialized.contains("raw prompt"));
        assertFalse(serialized.contains("http://internal-service"));
        assertFalse(serialized.contains("手机号唯一性"));
        assertFalse(serialized.contains("datasourceId"));
        assertFalse(serialized.contains("businessGoal"));
    }

    @Test
    void queryContractsShouldHonorReplaySequenceForIncrementalContractPreview() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(intakeRecord("intake-ready", "20", "run-contract", 1, "ready"));
        store.append(intakeRecord("intake-rejected", "20", "run-contract", 2, "rejected"));
        AgentToolActionIntakeProjectionService projectionService = new AgentToolActionIntakeProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        AgentToolActionIntakeDurableActionContractService service =
                new AgentToolActionIntakeDurableActionContractService(projectionService);

        AgentToolActionIntakeDurableActionContractQueryResponse response = service.queryContracts(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-contract", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.sourceSnapshotCount());
        assertEquals(1, response.totalContracts());
        assertEquals("REJECTED_BEFORE_READINESS", response.contracts().getFirst().durableActionState());
        assertEquals(2L, response.contracts().getFirst().sourceReplaySequence());
        assertEquals("NONE", response.contracts().getFirst().outboxCommandType());
    }

    @Test
    void queryContractsShouldSpecializeWorkspaceFileToolsWithoutExposingPathOrContent() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(workspaceFileIntakeRecord("intake-workspace-file", "20", "run-workspace-file", 1));
        AgentToolActionIntakeProjectionService projectionService = new AgentToolActionIntakeProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        AgentToolActionIntakeDurableActionContractService service =
                new AgentToolActionIntakeDurableActionContractService(projectionService);

        AgentToolActionIntakeDurableActionContractQueryResponse response = service.queryContracts(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-workspace-file", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.sourceSnapshotCount());
        assertEquals(2, response.totalContracts());
        assertEquals(1L, response.readyForDurableContractCount());
        assertEquals(1L, response.waitingApprovalCount());
        assertEquals(1L, response.toolNameCounts().get("workspace.file.read"));
        assertEquals(1L, response.toolNameCounts().get("workspace.file.write"));
        assertTrue(response.missingRequirementCounts().containsKey("WORKSPACE_FILE_PATH_REFERENCE_REQUIRED"));
        assertTrue(response.missingRequirementCounts().containsKey("WORKSPACE_FILE_ARTIFACT_GRANT_REQUIRED"));

        AgentToolActionIntakeDurableActionContractView readContract = response.contracts().stream()
                .filter(contract -> "workspace.file.read".equals(contract.toolName()))
                .findFirst()
                .orElseThrow();
        assertEquals("AGENT_WORKSPACE_FILE_READ_COMMAND", readContract.outboxCommandType());
        assertTrue(readContract.requiredEvidence().contains("WORKSPACE_FILE_PATH_REFERENCE"));
        assertTrue(readContract.requiredEvidence().contains("WORKSPACE_FILE_ARTIFACT_GRANT"));
        assertTrue(readContract.missingRequirements().contains("WORKSPACE_FILE_PATH_REFERENCE_REQUIRED"));
        assertTrue(readContract.missingRequirements().contains("WORKSPACE_FILE_WORKER_RECEIPT_REQUIRED"));
        assertTrue(readContract.guardrailNotes().stream().anyMatch(note -> note.contains("projection 不允许恢复真实路径")));

        AgentToolActionIntakeDurableActionContractView writeContract = response.contracts().stream()
                .filter(contract -> "workspace.file.write".equals(contract.toolName()))
                .findFirst()
                .orElseThrow();
        assertEquals("WAITING_APPROVAL", writeContract.durableActionState());
        assertEquals("NONE", writeContract.outboxCommandType());
        assertTrue(writeContract.requiredEvidence().contains("WORKSPACE_FILE_CONTENT_REFERENCE"));
        assertTrue(writeContract.requiredEvidence().contains("WORKSPACE_FILE_DLP_OR_MALWARE_SCAN"));
        assertTrue(writeContract.missingRequirements().contains("WORKSPACE_FILE_CONTENT_REFERENCE_REQUIRED"));
        assertTrue(writeContract.missingRequirements().contains("WORKSPACE_FILE_WRITE_APPROVAL_OR_POLICY_ALLOWANCE_REQUIRED"));

        String serialized = response.toString();
        assertFalse(serialized.contains("reports/private-output.md"));
        assertFalse(serialized.contains("write-content-secret"));
        assertFalse(serialized.contains("workspaceFileContent"));
        assertFalse(serialized.contains("C:\\tenant\\workspace"));
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
                        Map.entry("arguments", Map.of("datasourceId", "ds-sensitive-contract")),
                        Map.entry("payload", Map.of("businessGoal", "手机号唯一性"))
                )
        ));
        attributes.put("arguments", Map.of("datasourceId", "ds-sensitive-contract"));
        attributes.put("prompt", "raw prompt should not be exposed");
        attributes.put("sql", "select * from sensitive_table");
        attributes.put("internalEndpoint", "http://internal-service/tools");
        Instant timestamp = Instant.parse("2026-06-07T06:04:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "tool_action_intake_recorded",
                "record_tool_action_intake",
                "已记录工具动作意图入口治理快照。",
                rejected ? "warning" : "audit",
                "10",
                projectId,
                "1001",
                "request-contract",
                runId,
                "session-contract",
                sequence,
                timestamp,
                timestamp,
                timestamp,
                attributes
        );
    }

    private AgentRuntimeEventProjectionRecord workspaceFileIntakeRecord(String identityKey,
                                                                        String projectId,
                                                                        String runId,
                                                                        long sequence) {
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
        attributes.put("totalCount", 2);
        attributes.put("acceptedToolPlanCount", 2);
        attributes.put("rejectedBeforeReadinessCount", 0);
        attributes.put("issueCodes", List.of("WORKSPACE_FILE_WRITE_APPROVAL_REQUIRED"));
        attributes.put("toolNames", List.of("workspace.file.read", "workspace.file.write"));
        attributes.put("readinessExecutableCount", 1);
        attributes.put("readinessApprovalRequiredCount", 1);
        attributes.put("readinessClarificationRequiredCount", 0);
        attributes.put("readinessThrottledCount", 0);
        attributes.put("readinessBlockedCount", 0);
        attributes.put("readinessReasonCodes", List.of("READY_LOW_RISK_SYNC", "HUMAN_APPROVAL_REQUIRED"));
        attributes.put("graphOutboxWritten", false);
        attributes.put("graphWorkerReceiptRequiredForSideEffects", true);
        attributes.put("productionReadyForExecution", false);
        attributes.put("missingProductionRequirements", List.of("OUTBOX_COMMAND_AND_WORKER_RECEIPT"));
        attributes.put("decisionSummaries", List.of(
                Map.of(
                        "toolName", "workspace.file.read",
                        "decision", "ready_to_execute",
                        "executable", true,
                        "queueRequired", false,
                        "requiresHumanApproval", false,
                        "parameterIssueCount", 0,
                        "issueCodes", List.of(),
                        "reasonCodes", List.of("READY_LOW_RISK_SYNC")
                ),
                Map.ofEntries(
                        Map.entry("toolName", "workspace.file.write"),
                        Map.entry("decision", "waiting_approval"),
                        Map.entry("executable", false),
                        Map.entry("queueRequired", false),
                        Map.entry("requiresHumanApproval", true),
                        Map.entry("parameterIssueCount", 0),
                        Map.entry("issueCodes", List.of("WORKSPACE_FILE_WRITE_APPROVAL_REQUIRED")),
                        Map.entry("reasonCodes", List.of("HUMAN_APPROVAL_REQUIRED")),
                        Map.entry("arguments", Map.of(
                                "workspaceFilePath", "reports/private-output.md",
                                "workspaceFileContent", "write-content-secret"
                        ))
                )
        ));
        attributes.put("arguments", Map.of(
                "workspaceFilePath", "reports/private-output.md",
                "workspaceFileContent", "write-content-secret",
                "workspaceRoot", "C:\\tenant\\workspace"
        ));
        attributes.put("prompt", "raw prompt should not be exposed");
        Instant timestamp = Instant.parse("2026-06-29T15:10:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "tool_action_intake_recorded",
                "record_tool_action_intake",
                "recorded workspace file tool action intake",
                "audit",
                "10",
                projectId,
                "1001",
                "request-workspace-file",
                runId,
                "session-workspace-file",
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
                "trace-tool-action-contract-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
