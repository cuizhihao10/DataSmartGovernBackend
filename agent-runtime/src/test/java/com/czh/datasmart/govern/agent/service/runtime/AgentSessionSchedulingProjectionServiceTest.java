/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionSchedulingProjectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentSessionSchedulingProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionSchedulingProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 多 Agent 会话调度投影查询服务测试。
 *
 * <p>这组测试保护 Java 控制面对 Python `agent_session_scheduling_recorded` 事件的产品级解释能力。
 * 通用 runtime event 查询只能证明“事件被收到了”；本测试进一步证明 Java 能按多 Agent 调度语义：</p>
 *
 * <p>1. 固定过滤 `agent_session_scheduling_recorded`；</p>
 * <p>2. 遵守租户/项目访问范围；</p>
 * <p>3. 把自由 Map attributes 转成强类型 DTO；</p>
 * <p>4. 输出低敏聚合摘要，便于管理台和审计台直接消费。</p>
 */
class AgentSessionSchedulingProjectionServiceTest {

    @Test
    void querySnapshotsShouldParseSchedulingAttributesAndAggregateReturnedWindow() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(schedulingRecord("scheduling-1", "20", "run-agent", 1, "READY", false));
        store.append(nonSchedulingRuntimeEvent("tool-event-1", "20", "run-agent", 2));
        store.append(schedulingRecord("scheduling-other-project", "30", "run-agent", 3, "BLOCKED", true));
        AgentSessionSchedulingProjectionService service = new AgentSessionSchedulingProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentSessionSchedulingProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-agent", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals("runtime-event-projection-fallback", response.indexSource());
        assertEquals(1L, response.readyCount());
        assertEquals(0L, response.blockedCount());
        assertEquals(0L, response.handoffRequiredCount());
        assertEquals(1L, response.primaryAgentRoleCounts().get("MASTER_ORCHESTRATOR"));
        assertEquals(1L, response.participatingAgentRoleCounts().get("DATA_QUALITY_AGENT"));
        assertEquals(1L, response.intentDomainCounts().get("data_quality"));
        assertEquals(1L, response.plannedToolNameCounts().get("quality.rule.suggest"));
        assertEquals(1L, response.selectedSkillCodeCounts().get("quality.rule.design"));

        AgentSessionSchedulingProjectionView snapshot = response.snapshots().getFirst();
        assertEquals("agent_session_scheduling_recorded", snapshot.eventType());
        assertEquals("AGENT_SESSION_SCHEDULING_POLICY_VIEW", snapshot.snapshotType());
        assertEquals("READY", snapshot.status());
        assertEquals("MASTER_ORCHESTRATOR", snapshot.primaryAgentRole());
        assertEquals(List.of("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT", "MEMORY_AGENT"),
                snapshot.participatingAgentRoles());
        assertEquals(List.of("data_quality"), snapshot.intentDomains());
        assertEquals(List.of("quality.rule.design"), snapshot.selectedSkillCodes());
        assertEquals(List.of("quality.rule.suggest"), snapshot.plannedToolNames());
        assertEquals(1, snapshot.participationModeCounts().get("PRIMARY"));
        assertEquals(2, snapshot.agentStatusCounts().get("READY"));
        assertEquals(false, snapshot.handoffRequired());
        assertEquals(0, snapshot.recommendedActionCount());
    }

    @Test
    void querySnapshotsShouldUseReplaySequenceForIncrementalRead() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(schedulingRecord("scheduling-1", "20", "run-agent", 1, "READY", false));
        store.append(schedulingRecord("scheduling-2", "20", "run-agent", 2, "APPROVAL_REQUIRED", true));
        AgentSessionSchedulingProjectionService service = new AgentSessionSchedulingProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentSessionSchedulingProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-agent", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.approvalRequiredCount());
        assertEquals(1L, response.handoffRequiredCount());
        assertEquals(2L, response.snapshots().getFirst().replaySequence());
        assertEquals(List.of("TASK_AGENT", "PERMISSION_AGENT"), response.snapshots().getFirst().handoffAgentRoles());
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
        attributes.put("participatingAgentCount", handoffRequired ? 3 : 3);
        attributes.put("participatingAgentRoles", handoffRequired
                ? List.of("MASTER_ORCHESTRATOR", "TASK_AGENT", "PERMISSION_AGENT")
                : List.of("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT", "MEMORY_AGENT"));
        attributes.put("participatingAgentRolesTruncatedCount", 0);
        attributes.put("participationModeCounts", handoffRequired
                ? Map.of("PRIMARY", 1, "SPECIALIST", 1, "GUARDRAIL", 1)
                : Map.of("PRIMARY", 1, "SPECIALIST", 1, "GUARDRAIL", 1));
        attributes.put("agentStatusCounts", Map.of(status, handoffRequired ? 3 : 2));
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
        attributes.put("displaySummary", "智能网关已记录多 Agent 调度。");
        attributes.put("recommendedActionCount", handoffRequired ? 1 : 0);
        return eventRecord(identityKey, "agent_session_scheduling_recorded", projectId,
                runId, sequence, handoffRequired ? "audit" : "info", attributes);
    }

    private AgentRuntimeEventProjectionRecord nonSchedulingRuntimeEvent(String identityKey,
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
        Instant timestamp = Instant.parse("2026-06-05T12:00:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                "record_agent_session_scheduling",
                "已记录本轮多 Agent 会话调度策略视图。",
                severity,
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
                "trace-agent-session-scheduling-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
