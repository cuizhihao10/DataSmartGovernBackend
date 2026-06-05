/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Master Agent handoff DAG 服务测试。
 *
 * <p>这组测试保护的是“多 Agent 调度事实如何被 Java 控制面解释为交接图”的产品契约。
 * 它刻意不测试真实 Agent 执行，因为当前阶段的目标是先稳定只读 DAG、权限收口和低敏展示字段。
 * 等后续接入 MCP/A2A/LangGraph/OpenClaw worker 时，可以继续复用这些断言，确保新执行器不会破坏
 * 管理台和审计台已经依赖的控制面语义。</p>
 */
class AgentSessionHandoffDagServiceTest {

    @Test
    void readyDataQualitySchedulingShouldBuildExecutableMasterHandoffDag() {
        AgentSessionHandoffDagService service = serviceWithRecords(
                schedulingRecord("scheduling-ready", "20", "run-ready", 1, "READY", false)
        );

        AgentSessionHandoffDagQueryResponse response = service.queryHandoffDags(query("run-ready"), projectOwnerContext());

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.readyCount());
        assertEquals(1L, response.executableCount());
        AgentSessionHandoffDagView dag = response.dags().getFirst();
        assertTrue(dag.executable());
        assertEquals("READY", dag.dagExecutionState());
        assertNodeExists(dag, "master");
        assertNodeExists(dag, "memory-context");
        assertNodeExists(dag, "specialist-data-quality");
        assertNodeExists(dag, "tool-control");
        assertNodeExists(dag, "feedback");
        assertNodeExists(dag, "second-turn");
        assertTrue(dag.edges().stream().anyMatch(edge -> "MASTER_TO_SPECIALIST".equals(edge.edgeType())));
        assertTrue(dag.edges().stream().anyMatch(edge -> "SPECIALIST_TO_TOOL_CONTROL".equals(edge.edgeType())));
    }

    @Test
    void approvalRequiredSchedulingShouldInsertGuardrailNodeAndBlockExecution() {
        AgentSessionHandoffDagService service = serviceWithRecords(
                schedulingRecord("scheduling-approval", "20", "run-approval", 1, "APPROVAL_REQUIRED", true)
        );

        AgentSessionHandoffDagQueryResponse response = service.queryHandoffDags(query("run-approval"), projectOwnerContext());

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.approvalRequiredCount());
        assertEquals(0L, response.executableCount());
        assertEquals(1L, response.handoffRequiredCount());
        AgentSessionHandoffDagView dag = response.dags().getFirst();
        assertFalse(dag.executable());
        AgentSessionHandoffDagNodeView approval = findNode(dag, "approval-handoff");
        AgentSessionHandoffDagNodeView toolControl = findNode(dag, "tool-control");
        assertFalse(approval.executable());
        assertEquals(List.of("approval-handoff"), toolControl.blockedByNodeIds());
        assertTrue(dag.edges().stream().anyMatch(edge -> "GUARDRAIL_TO_TOOL_CONTROL".equals(edge.edgeType())));
    }

    @Test
    void degradedAndBlockedSchedulingShouldRemainNonExecutable() {
        AgentSessionHandoffDagService service = serviceWithRecords(
                schedulingRecord("scheduling-degraded", "20", "run-audit", 1, "DEGRADED", false),
                schedulingRecord("scheduling-blocked", "20", "run-audit", 2, "BLOCKED", false)
        );

        AgentSessionHandoffDagQueryResponse response = service.queryHandoffDags(query("run-audit"), projectOwnerContext());

        assertEquals(2, response.totalMatched());
        assertEquals(1L, response.degradedCount());
        assertEquals(1L, response.blockedCount());
        assertEquals(0L, response.executableCount());
        assertTrue(response.dags().stream().noneMatch(AgentSessionHandoffDagView::executable));
    }

    private AgentSessionHandoffDagService serviceWithRecords(AgentRuntimeEventProjectionRecord... records) {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        for (AgentRuntimeEventProjectionRecord record : records) {
            store.append(record);
        }
        AgentSessionSchedulingProjectionService schedulingService = new AgentSessionSchedulingProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );
        return new AgentSessionHandoffDagService(schedulingService);
    }

    private AgentRuntimeEventProjectionQuery query(String runId) {
        return new AgentRuntimeEventProjectionQuery("10", null, null, null,
                runId, null, null, null, 20);
    }

    private AgentRuntimeEventProjectionRecord schedulingRecord(String identityKey,
                                                               String projectId,
                                                               String runId,
                                                               long sequence,
                                                               String status,
                                                               boolean handoffRequired) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventPayloadVersion", "v1");
        attributes.put("snapshotType", "AGENT_SESSION_SCHEDULING_POLICY_VIEW");
        attributes.put("available", !"BLOCKED".equals(status));
        attributes.put("status", status);
        attributes.put("primaryAgentRole", "MASTER_ORCHESTRATOR");
        attributes.put("participatingAgentCount", 3);
        attributes.put("participatingAgentRoles", handoffRequired
                ? List.of("MASTER_ORCHESTRATOR", "TASK_AGENT", "PERMISSION_AGENT")
                : List.of("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT", "MEMORY_AGENT"));
        attributes.put("participatingAgentRolesTruncatedCount", 0);
        attributes.put("participationModeCounts", Map.of("PRIMARY", 1, "SPECIALIST", 1, "GUARDRAIL", 1));
        attributes.put("agentStatusCounts", Map.of(status, 3));
        attributes.put("handoffRequired", handoffRequired);
        attributes.put("handoffAgentRoles", handoffRequired ? List.of("TASK_AGENT", "PERMISSION_AGENT") : List.of());
        attributes.put("intentDomains", handoffRequired ? List.of("task_management") : List.of("data_quality"));
        attributes.put("selectedSkillCodes", handoffRequired ? List.of("governed.task.creation") : List.of("quality.rule.design"));
        attributes.put("visibleSkillCodes", handoffRequired ? List.of("governed.task.creation") : List.of("quality.rule.design"));
        attributes.put("plannedToolNames", handoffRequired ? List.of("task.create.draft") : List.of("quality.rule.suggest"));
        attributes.put("memoryDependencies", List.of("episodic"));
        attributes.put("modelGatewayAvailable", true);
        attributes.put("skillAdmissionAllowed", true);
        attributes.put("toolBudgetAllowed", true);
        attributes.put("approvalRequired", handoffRequired);
        attributes.put("tenantScoped", true);
        attributes.put("projectScoped", true);
        attributes.put("displaySummary", "智能网关已记录多 Agent 会话调度。");
        attributes.put("recommendedActionCount", handoffRequired ? 1 : 0);
        Instant timestamp = Instant.parse("2026-06-05T12:00:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "agent_session_scheduling_recorded",
                "record_agent_session_scheduling",
                "已记录本轮多 Agent 会话调度策略视图。",
                handoffRequired ? "audit" : "info",
                "10",
                projectId,
                "1001",
                "request-agent-session",
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
                "trace-agent-session-handoff-dag-test",
                "PROJECT",
                List.of(20L)
        );
    }

    private void assertNodeExists(AgentSessionHandoffDagView dag, String nodeId) {
        assertTrue(dag.nodes().stream().anyMatch(node -> nodeId.equals(node.nodeId())),
                "Expected node " + nodeId + " to exist");
    }

    private AgentSessionHandoffDagNodeView findNode(AgentSessionHandoffDagView dag, String nodeId) {
        return dag.nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected node " + nodeId + " to exist"));
    }
}
