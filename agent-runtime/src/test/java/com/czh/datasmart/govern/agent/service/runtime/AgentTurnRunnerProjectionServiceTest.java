/**
 * @Author : Cui
 * @Date: 2026/07/02 18:08
 * @Description DataSmart Govern Backend - AgentTurnRunnerProjectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerAttemptProjectionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控多 Agent Turn Runner 投影查询服务测试。
 *
 * <p>该测试验证 Java 控制面能把 Python `agent_turn_runner_recorded` 事件解释成可审计的 turn runner
 * 视图，并持续保护三个核心约束：</p>
 * <p>1. 专用接口必须固定事件类型，不能混入其他 runtime event；</p>
 * <p>2. turn attempt、requiredEvidenceCodes、manager-as-tools 统计可以被 Java 正确聚合；</p>
 * <p>3. prompt、SQL、工具参数、模型输出、checkpointId、commandId 等敏感或内部字段不会出现在 DTO 中。</p>
 */
class AgentTurnRunnerProjectionServiceTest {

    @Test
    void querySnapshotsShouldParseTurnRunnerAttributesAndAggregateWindow() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(turnRunnerRecord("turn-runner-1", "20", "run-agent", 1));
        store.append(nonTurnRunnerRuntimeEvent("session-event-1", "20", "run-agent", 2));
        store.append(turnRunnerRecord("turn-runner-other-project", "30", "run-agent", 3));
        AgentTurnRunnerProjectionService service = new AgentTurnRunnerProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentTurnRunnerProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-agent", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals("runtime-event-projection-fallback", response.indexSource());
        assertEquals(1L, response.waitingRunnerCount());
        assertEquals(0L, response.blockedRunnerCount());
        assertEquals(0L, response.sideEffectViolationCount());
        assertEquals(1L, response.waitingAttemptTotal());
        assertEquals(0L, response.blockedAttemptTotal());
        assertEquals(2L, response.controlPlaneHandoffAttemptTotal());
        assertEquals(2L, response.managerAsToolsTotal());
        assertEquals(1L, response.checkpointLinkedCount());
        assertEquals(1L, response.runStatusCounts().get("WAITING_CONTROL_PLANE_FEEDBACK"));
        assertEquals(1L, response.checkpointStatusCounts().get("waiting_human"));
        assertEquals(1L, response.checkpointNodeCounts().get("multi_agent_turn_wait_human"));
        assertEquals(1L, response.checkpointGraphCounts().get("datasmart.agent.multi-agent-turn-runner"));
        assertEquals(1L, response.checkpointResumeRequirementCounts().get("requiredEvidenceCodes"));
        assertEquals(1L, response.turnStatusCounts().get("READY_FOR_CONTROL_PLANE_HANDOFF"));
        assertEquals(1L, response.turnStatusCounts().get("WAITING_APPROVAL"));
        assertEquals(2L, response.requiredEvidenceCounts().get("WORKER_RECEIPT_REQUIRED"));
        assertEquals(1L, response.nextActionCounts().get("WAIT_FOR_JAVA_CONTROL_PLANE_FACTS"));

        AgentTurnRunnerProjectionView snapshot = response.snapshots().getFirst();
        assertEquals("agent_turn_runner_recorded", snapshot.eventType());
        assertEquals("CONTROLLED_MULTI_AGENT_TURN_RUNNER_VIEW", snapshot.snapshotType());
        assertEquals("WAITING_CONTROL_PLANE_FEEDBACK", snapshot.runStatus());
        assertEquals("WAITING_APPROVAL_OR_HANDOFF", snapshot.sessionStatus());
        assertEquals("waiting_control_plane", snapshot.durablePhase());
        assertEquals(2, snapshot.turnAttemptCount());
        assertEquals(2, snapshot.managerAsToolsCount());
        assertTrue(snapshot.javaControlPlaneRequiredForSideEffects());
        assertTrue(snapshot.workerReceiptRequiredForSideEffects());
        assertFalse(snapshot.toolExecutedByPython());
        assertFalse(snapshot.modelCalledByTurnRunner());
        assertEquals("turn-runner:session-agent", snapshot.turnRunnerCheckpoint().threadId());
        assertEquals("lgcp:turn:session-agent:001", snapshot.turnRunnerCheckpoint().checkpointId());
        assertEquals("datasmart.agent.multi-agent-turn-runner", snapshot.turnRunnerCheckpoint().graphName());
        assertEquals("multi_agent_turn_wait_human", snapshot.turnRunnerCheckpoint().nodeName());
        assertEquals("waiting_human", snapshot.turnRunnerCheckpoint().checkpointStatus());
        assertEquals(List.of("wait_approval_fact", "resume_after_human_decision"),
                snapshot.turnRunnerCheckpoint().nextNodes());
        assertEquals(List.of("requiredEvidenceCodes", "javaControlPlaneRequired", "workerReceiptRequired"),
                snapshot.turnRunnerCheckpoint().resumeRequirementKeys());
        assertEquals("WAITING_APPROVAL", snapshot.turnRunnerCheckpoint()
                .recoveryAgentStatuses()
                .get("DATA_QUALITY_AGENT"));
        assertTrue(snapshot.turnRunnerCheckpoint().handoffRequired());

        AgentTurnRunnerAttemptProjectionView attempt = snapshot.turnAttempts().get(1);
        assertEquals("DATA_QUALITY_AGENT", attempt.agentRole());
        assertEquals("WAITING_APPROVAL", attempt.turnStatus());
        assertEquals("manager_call_data_quality_agent", attempt.managerToolName());
        assertEquals(List.of("APPROVAL_DECISION_FACT_REQUIRED", "WORKER_RECEIPT_REQUIRED"),
                attempt.requiredEvidenceCodes());

        String serialized = snapshot.toString() + response.toString();
        assertFalse(serialized.contains("secret objective"));
        assertFalse(serialized.contains("select * from hidden_customer"));
        assertFalse(serialized.contains("toolArguments"));
        assertFalse(serialized.contains("secret model output"));
        assertFalse(serialized.contains("checkpoint-secret"));
        assertFalse(serialized.contains("command-secret"));
    }

    @Test
    void querySnapshotsShouldUseReplaySequenceAndExposeSideEffectViolationCounter() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(turnRunnerRecord("turn-runner-1", "20", "run-agent", 1));
        store.append(sideEffectViolationRecord("turn-runner-2", "20", "run-agent", 2));
        AgentTurnRunnerProjectionService service = new AgentTurnRunnerProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentTurnRunnerProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-agent", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(2L, response.snapshots().getFirst().replaySequence());
        assertEquals(1L, response.blockedRunnerCount());
        assertEquals(1L, response.sideEffectViolationCount());
        assertEquals("BLOCKED_SIDE_EFFECT_BOUNDARY_VIOLATION", response.snapshots().getFirst().runStatus());
    }

    private AgentRuntimeEventProjectionRecord turnRunnerRecord(String identityKey,
                                                               String projectId,
                                                               String runId,
                                                               long sequence) {
        Map<String, Object> attributes = baseAttributes("WAITING_CONTROL_PLANE_FEEDBACK", false);
        attributes.put("turnAttempts", List.of(
                turnAttempt("turn-1-master", "workitem-1-master", "MASTER_ORCHESTRATOR",
                        "READY_FOR_CONTROL_PLANE_HANDOFF", "HANDOFF_TO_JAVA_CONTROL_PLANE",
                        "manager_call_master_orchestrator",
                        List.of("JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED", "WORKER_RECEIPT_REQUIRED")),
                turnAttempt("turn-1-quality", "workitem-2-quality", "DATA_QUALITY_AGENT",
                        "WAITING_APPROVAL", "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT",
                        "manager_call_data_quality_agent",
                        List.of("APPROVAL_DECISION_FACT_REQUIRED", "WORKER_RECEIPT_REQUIRED"))
        ));
        attributes.put("prompt", "secret objective should not leak");
        attributes.put("sql", "select * from hidden_customer");
        attributes.put("toolArguments", Map.of("datasourceId", "ds-secret"));
        attributes.put("modelOutput", "secret model output");
        attributes.put("checkpointId", "checkpoint-secret");
        attributes.put("commandId", "command-secret");
        return eventRecord(identityKey, "agent_turn_runner_recorded", projectId,
                runId, sequence, "warning", attributes);
    }

    private AgentRuntimeEventProjectionRecord sideEffectViolationRecord(String identityKey,
                                                                        String projectId,
                                                                        String runId,
                                                                        long sequence) {
        Map<String, Object> attributes = baseAttributes("BLOCKED_SIDE_EFFECT_BOUNDARY_VIOLATION", true);
        attributes.put("turnAttempts", List.of(turnAttempt("turn-2-master", "workitem-1-master",
                "MASTER_ORCHESTRATOR", "BLOCKED_BY_RUNTIME_POLICY",
                "WAIT_FOR_RUNTIME_RECOVERY_FACT", "manager_call_master_orchestrator",
                List.of("TURN_CHECKPOINT_REQUIRED"))));
        attributes.put("blockedAttemptCount", 1);
        attributes.put("waitingAttemptCount", 0);
        attributes.put("controlPlaneHandoffCount", 0);
        return eventRecord(identityKey, "agent_turn_runner_recorded", projectId,
                runId, sequence, "error", attributes);
    }

    private Map<String, Object> baseAttributes(String runStatus, boolean sideEffectViolation) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventPayloadVersion", "v1");
        attributes.put("snapshotType", "CONTROLLED_MULTI_AGENT_TURN_RUNNER_VIEW");
        attributes.put("schemaVersion", "datasmart.multi-agent.turn-runner.v1");
        attributes.put("status", "ready");
        attributes.put("runStatus", runStatus);
        attributes.put("sessionStatus", "WAITING_APPROVAL_OR_HANDOFF");
        attributes.put("durablePhase", "waiting_control_plane");
        attributes.put("currentTurnDepth", 1);
        attributes.put("maxTurnDepth", 3);
        attributes.put("maxConcurrentAgentTurns", 5);
        attributes.put("turnAttemptCount", 2);
        attributes.put("waitingAttemptCount", 1);
        attributes.put("blockedAttemptCount", 0);
        attributes.put("controlPlaneHandoffCount", 2);
        attributes.put("managerAsToolsCount", 2);
        attributes.put("turnAttemptsTruncatedCount", 0);
        attributes.put("nextActions", List.of("WAIT_FOR_JAVA_CONTROL_PLANE_FACTS"));
        attributes.put("toolExecutedByPython", sideEffectViolation);
        attributes.put("modelCalledByTurnRunner", false);
        attributes.put("outboxWrittenByPython", false);
        attributes.put("approvalCreatedByPython", false);
        attributes.put("workerDispatchedByPython", false);
        attributes.put("javaControlPlaneRequiredForSideEffects", true);
        attributes.put("workerReceiptRequiredForSideEffects", true);
        attributes.put("executionBoundary", "CONTROLLED_MULTI_AGENT_TURN_RUNNER_NO_SIDE_EFFECTS");
        attributes.put("payloadPolicy", "LOW_SENSITIVE_MULTI_AGENT_TURN_RUNNER_ONLY");
        attributes.put("turnRunnerCheckpoint", checkpointLocator(runStatus));
        return attributes;
    }

    private Map<String, Object> checkpointLocator(String runStatus) {
        Map<String, Object> recoveryStatuses = new LinkedHashMap<>();
        recoveryStatuses.put("MASTER_ORCHESTRATOR", "OBSERVING_TURN_RUNNER");
        recoveryStatuses.put("DATA_QUALITY_AGENT", runStatus.startsWith("BLOCKED")
                ? "BLOCKED_BY_RUNTIME_POLICY"
                : "WAITING_APPROVAL");

        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("threadId", "turn-runner:session-agent");
        checkpoint.put("checkpointId", runStatus.startsWith("BLOCKED")
                ? "lgcp:turn:session-agent:blocked-001"
                : "lgcp:turn:session-agent:001");
        checkpoint.put("parentCheckpointId", null);
        checkpoint.put("graphName", "datasmart.agent.multi-agent-turn-runner");
        checkpoint.put("graphVersion", "v1");
        checkpoint.put("nodeName", runStatus.startsWith("BLOCKED")
                ? "multi_agent_turn_blocked"
                : "multi_agent_turn_wait_human");
        checkpoint.put("checkpointStatus", runStatus.startsWith("BLOCKED") ? "failed" : "waiting_human");
        checkpoint.put("checkpointVersion", 1);
        checkpoint.put("nextNodes", List.of("wait_approval_fact", "resume_after_human_decision"));
        checkpoint.put("resumeRequirementKeys", List.of(
                "requiredEvidenceCodes",
                "javaControlPlaneRequired",
                "workerReceiptRequired"
        ));
        checkpoint.put("stateTopLevelKeys", List.of(
                "multiAgentState",
                "turnRunner",
                "resumeRequirements"
        ));
        checkpoint.put("recoveryFound", true);
        checkpoint.put("recoveryStatus", runStatus.startsWith("BLOCKED") ? "failed" : "waiting_human");
        checkpoint.put("recoveryAgentRoles", List.of("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT"));
        checkpoint.put("recoveryAgentStatuses", recoveryStatuses);
        checkpoint.put("handoffRequired", true);
        checkpoint.put("payloadPolicy", "LOW_SENSITIVE_MULTI_AGENT_TURN_RUNNER_CHECKPOINT_LOCATOR_ONLY");
        return checkpoint;
    }

    private Map<String, Object> turnAttempt(String turnId,
                                            String workItemId,
                                            String role,
                                            String status,
                                            String resumeAction,
                                            String managerToolName,
                                            List<String> requiredEvidenceCodes) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("turnId", turnId);
        attempt.put("workItemId", workItemId);
        attempt.put("agentRole", role);
        attempt.put("deliveryTier", "must_do");
        attempt.put("turnStatus", status);
        attempt.put("resumeAction", resumeAction);
        attempt.put("managerToolName", managerToolName);
        attempt.put("requiredEvidenceCodes", requiredEvidenceCodes);
        attempt.put("waitingReasonCodes", List.of("CONTROL_PLANE_FEEDBACK_NOT_COLLECTED"));
        attempt.put("blockedBy", List.of());
        attempt.put("plannedToolCount", 1);
        attempt.put("visibleSkillCount", 1);
        attempt.put("memoryDependencyCount", 1);
        attempt.put("payloadPolicy", "LOW_SENSITIVE_AGENT_TURN_ATTEMPT_ONLY");
        attempt.put("toolArguments", Map.of("sql", "select * from hidden_customer"));
        return attempt;
    }

    private AgentRuntimeEventProjectionRecord nonTurnRunnerRuntimeEvent(String identityKey,
                                                                        String projectId,
                                                                        String runId,
                                                                        long sequence) {
        return eventRecord(identityKey, "agent_execution_session_recorded", projectId, runId, sequence,
                "info", Map.of("runStatus", "READY"));
    }

    private AgentRuntimeEventProjectionRecord eventRecord(String identityKey,
                                                          String eventType,
                                                          String projectId,
                                                          String runId,
                                                          long sequence,
                                                          String severity,
                                                          Map<String, Object> attributes) {
        Instant timestamp = Instant.parse("2026-07-02T12:10:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                "record_agent_turn_runner",
                "已记录本轮受控多 Agent turn runner 合同。",
                severity,
                "10",
                projectId,
                "1001",
                "request-agent-turn-runner",
                runId,
                "session-agent",
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
                "trace-agent-turn-runner-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
