/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmart Govern Backend - AgentExecutionSessionProjectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentExecutionSessionProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentExecutionSessionProjectionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentExecutionSessionWorkItemProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控多 Agent 执行会话投影查询服务测试。
 *
 * <p>该测试保护 Java 控制面对 Python `agent_execution_session_recorded` 事件的产品级解释能力：
 * 通用 runtime event 查询只能证明“事件被接收”，本测试进一步证明 Java 能按执行会话语义解析状态、
 * work item、roster coverage、resumeAction 和 sideEffectBoundary，并保持敏感字段不外泄。</p>
 */
class AgentExecutionSessionProjectionServiceTest {

    @Test
    void querySnapshotsShouldParseExecutionSessionAttributesAndAggregateWindow() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(executionSessionRecord("execution-session-1", "20", "run-agent", 1));
        store.append(nonExecutionSessionRuntimeEvent("tool-event-1", "20", "run-agent", 2));
        store.append(executionSessionRecord("execution-session-other-project", "30", "run-agent", 3));
        AgentExecutionSessionProjectionService service = new AgentExecutionSessionProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentExecutionSessionProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-agent", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals("runtime-event-projection-fallback", response.indexSource());
        assertEquals(1L, response.waitingApprovalOrHandoffCount());
        assertEquals(0L, response.readyForAgentTurnsCount());
        assertEquals(1L, response.handoffRequiredSessionCount());
        assertEquals(1L, response.statusCounts().get("WAITING_APPROVAL_OR_HANDOFF"));
        assertEquals(1L, response.durablePhaseCounts().get("waiting_control_plane"));
        assertEquals(2L, response.deliveryTierCounts().get("must_do"));
        assertEquals(2L, response.resumeActionCounts().get("WAIT_FOR_APPROVAL_OR_HANDOFF_FACT"));
        assertEquals(1L, response.activeMustDoRoleCounts().get("MASTER_ORCHESTRATOR"));
        assertEquals(1L, response.standbyMustDoRoleCounts().get("TASK_AGENT"));

        AgentExecutionSessionProjectionView snapshot = response.snapshots().getFirst();
        assertEquals("agent_execution_session_recorded", snapshot.eventType());
        assertEquals("AGENT_EXECUTION_SESSION_CONTROL_PLANE_VIEW", snapshot.snapshotType());
        assertEquals("WAITING_APPROVAL_OR_HANDOFF", snapshot.status());
        assertEquals("waiting_control_plane", snapshot.durablePhase());
        assertEquals("langgraph_multi_agent_execution_plan", snapshot.executionSessionSource());
        assertEquals(List.of("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT", "PERMISSION_AGENT"),
                snapshot.activeRoles());
        assertEquals(List.of("DATASOURCE_AGENT", "TASK_AGENT"), snapshot.standbyMustDoRoles());
        assertEquals(3, snapshot.workItemCount());
        assertFalse(snapshot.toolExecutedByPython());
        assertFalse(snapshot.outboxWrittenByPython());
        assertTrue(snapshot.javaControlPlaneRequiredForSideEffects());

        AgentExecutionSessionWorkItemProjectionView item = snapshot.workItems().get(1);
        assertEquals("DATA_QUALITY_AGENT", item.agentRole());
        assertEquals("WAITING_APPROVAL_OR_HANDOFF", item.sessionStatus());
        assertEquals("WAIT_FOR_APPROVAL_OR_HANDOFF_FACT", item.resumeAction());
        assertEquals(List.of("CONTROL_PLANE_FEEDBACK_NOT_COLLECTED"), item.waitingReasonCodes());
        assertEquals(1, item.plannedToolCount());

        String serialized = snapshot.toString() + response.toString();
        assertFalse(serialized.contains("secret objective"));
        assertFalse(serialized.contains("select * from hidden_customer"));
        assertFalse(serialized.contains("toolArguments"));
        assertFalse(serialized.contains("secret model output"));
    }

    @Test
    void querySnapshotsShouldUseReplaySequenceForIncrementalRead() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(executionSessionRecord("execution-session-1", "20", "run-agent", 1));
        store.append(blockedExecutionSessionRecord("execution-session-2", "20", "run-agent", 2));
        AgentExecutionSessionProjectionService service = new AgentExecutionSessionProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentExecutionSessionProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-agent", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.blockedWaitingRecoveryCount());
        assertEquals(2L, response.snapshots().getFirst().replaySequence());
        assertEquals("BLOCKED_WAITING_RECOVERY", response.snapshots().getFirst().status());
        assertEquals(1L, response.resumeActionCounts().get("WAIT_FOR_RUNTIME_RECOVERY_FACT"));
    }

    private AgentRuntimeEventProjectionRecord executionSessionRecord(String identityKey,
                                                                     String projectId,
                                                                     String runId,
                                                                     long sequence) {
        Map<String, Object> attributes = baseAttributes("WAITING_APPROVAL_OR_HANDOFF");
        attributes.put("workItems", List.of(
                workItem("workitem-1-master", "MASTER_ORCHESTRATOR",
                        "READY_FOR_CONTROL_PLANE_HANDOFF", "HANDOFF_TO_JAVA_CONTROL_PLANE", false),
                workItem("workitem-2-quality", "DATA_QUALITY_AGENT",
                        "WAITING_APPROVAL_OR_HANDOFF", "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT", true),
                workItem("workitem-3-permission", "PERMISSION_AGENT",
                        "WAITING_APPROVAL_OR_HANDOFF", "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT", true)
        ));
        attributes.put("workItemStatusCounts", Map.of(
                "READY_FOR_CONTROL_PLANE_HANDOFF", 1,
                "WAITING_APPROVAL_OR_HANDOFF", 2
        ));
        attributes.put("deliveryTierCounts", Map.of("must_do", 2, "controlled_scope", 1));
        attributes.put("resumeActionCounts", Map.of(
                "HANDOFF_TO_JAVA_CONTROL_PLANE", 1,
                "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT", 2
        ));
        attributes.put("sourceStatusCounts", Map.of("PLANNED_READY", 1, "WAITING_HUMAN_OR_PERMISSION_HANDOFF", 2));
        attributes.put("handoffRequiredWorkItemCount", 2);
        attributes.put("prompt", "secret objective should not leak");
        attributes.put("sql", "select * from hidden_customer");
        attributes.put("toolArguments", Map.of("datasourceId", "ds-secret"));
        attributes.put("modelOutput", "secret model output");
        return eventRecord(identityKey, "agent_execution_session_recorded", projectId,
                runId, sequence, "audit", attributes);
    }

    private AgentRuntimeEventProjectionRecord blockedExecutionSessionRecord(String identityKey,
                                                                            String projectId,
                                                                            String runId,
                                                                            long sequence) {
        Map<String, Object> attributes = baseAttributes("BLOCKED_WAITING_RECOVERY");
        attributes.put("workItems", List.of(workItem("workitem-1-master", "MASTER_ORCHESTRATOR",
                "BLOCKED_WAITING_RECOVERY", "WAIT_FOR_RUNTIME_RECOVERY_FACT", false)));
        attributes.put("workItemStatusCounts", Map.of("BLOCKED_WAITING_RECOVERY", 1));
        attributes.put("deliveryTierCounts", Map.of("must_do", 1));
        attributes.put("resumeActionCounts", Map.of("WAIT_FOR_RUNTIME_RECOVERY_FACT", 1));
        attributes.put("handoffRequiredWorkItemCount", 0);
        return eventRecord(identityKey, "agent_execution_session_recorded", projectId,
                runId, sequence, "error", attributes);
    }

    private Map<String, Object> baseAttributes(String status) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventPayloadVersion", "v1");
        attributes.put("snapshotType", "AGENT_EXECUTION_SESSION_CONTROL_PLANE_VIEW");
        attributes.put("available", true);
        attributes.put("status", status);
        attributes.put("durablePhase", "waiting_control_plane");
        attributes.put("durableResumeAction", "wait_event_replay");
        attributes.put("executionPlanStatus", "WAITING_HUMAN_OR_PERMISSION_HANDOFF");
        attributes.put("executionSessionSource", "langgraph_multi_agent_execution_plan");
        attributes.put("workItemCount", 3);
        attributes.put("activeRoles", List.of("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT", "PERMISSION_AGENT"));
        attributes.put("activeRolesTruncatedCount", 0);
        attributes.put("waitingReasonCodes", List.of("CONTROL_PLANE_FEEDBACK_NOT_COLLECTED"));
        attributes.put("blockedByCodes", List.of());
        attributes.put("activeMustDoRoles", List.of("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT"));
        attributes.put("standbyMustDoRoles", List.of("DATASOURCE_AGENT", "TASK_AGENT"));
        attributes.put("activeControlledScopeRoles", List.of("PERMISSION_AGENT"));
        attributes.put("standbyControlledScopeRoles", List.of("MEMORY_AGENT"));
        attributes.put("deferredLightweightRoles", List.of("ETL_DEVELOPMENT_AGENT"));
        attributes.put("activeRoleCount", 3);
        attributes.put("mustDoRoleCount", 5);
        attributes.put("activeMustDoRoleCount", 2);
        attributes.put("coveragePolicy", "ACTIVATE_BY_INTENT_KEEP_NON_MATCHED_AGENTS_STANDBY");
        attributes.put("collaborationEdgeCount", 4);
        attributes.put("handoffContractCount", 2);
        attributes.put("nextActions", List.of("WAIT_FOR_PERMISSION_OR_HUMAN_HANDOFF_FACT"));
        attributes.put("toolExecutedByPython", false);
        attributes.put("modelCalledByExecutionSession", false);
        attributes.put("outboxWrittenByPython", false);
        attributes.put("approvalCreatedByPython", false);
        attributes.put("workerDispatchedByPython", false);
        attributes.put("checkpointMutatedByExecutionSession", false);
        attributes.put("javaControlPlaneRequiredForSideEffects", true);
        attributes.put("workerReceiptRequiredForSideEffects", true);
        attributes.put("executionBoundary", "CONTROLLED_MULTI_AGENT_SESSION_NO_SIDE_EFFECTS");
        attributes.put("payloadPolicy", "LOW_SENSITIVE_MULTI_AGENT_EXECUTION_SESSION_ONLY");
        return attributes;
    }

    private Map<String, Object> workItem(String id,
                                         String role,
                                         String status,
                                         String resumeAction,
                                         boolean handoffRequired) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("workItemId", id);
        item.put("agentRole", role);
        item.put("deliveryTier", "must_do");
        item.put("participationMode", "SPECIALIST");
        item.put("sessionStatus", status);
        item.put("resumeAction", resumeAction);
        item.put("executionLane", "SPECIALIST_AGENT_LANE");
        item.put("dependsOnRoles", List.of("MASTER_ORCHESTRATOR"));
        item.put("handoffRequired", handoffRequired);
        item.put("plannedToolCount", 1);
        item.put("visibleSkillCount", 1);
        item.put("memoryDependencyCount", 1);
        item.put("waitingReasonCodes", List.of("CONTROL_PLANE_FEEDBACK_NOT_COLLECTED"));
        item.put("blockedBy", List.of());
        item.put("durablePhase", "waiting_control_plane");
        item.put("sourceStatus", handoffRequired ? "WAITING_HUMAN_OR_PERMISSION_HANDOFF" : "PLANNED_READY");
        item.put("payloadPolicy", "LOW_SENSITIVE_MULTI_AGENT_SESSION_WORK_ITEM_ONLY");
        item.put("toolArguments", Map.of("sql", "select * from hidden_customer"));
        return item;
    }

    private AgentRuntimeEventProjectionRecord nonExecutionSessionRuntimeEvent(String identityKey,
                                                                              String projectId,
                                                                              String runId,
                                                                              long sequence) {
        return eventRecord(identityKey, "agent_session_scheduling_recorded", projectId, runId, sequence,
                "info", Map.of("status", "READY"));
    }

    private AgentRuntimeEventProjectionRecord eventRecord(String identityKey,
                                                          String eventType,
                                                          String projectId,
                                                          String runId,
                                                          long sequence,
                                                          String severity,
                                                          Map<String, Object> attributes) {
        Instant timestamp = Instant.parse("2026-07-02T12:00:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                "record_agent_execution_session",
                "已记录本轮受控多 Agent 执行会话状态视图。",
                severity,
                "10",
                projectId,
                "1001",
                "request-agent-execution-session",
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
                "trace-agent-execution-session-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
