/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionReadinessProjectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionReadinessProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionReadinessProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 工具执行准备度投影查询服务测试。
 *
 * <p>这组测试保护 Java 控制面对 Python `tool_execution_readiness_recorded` 事件的解释能力。
 * 通用 runtime event 查询只能证明事件被收到；本测试进一步证明 Java 能按工具执行前治理语义解析
 * 可执行、审批、澄清、草案、限流和阻断，并且不会把敏感参数值带入 DTO。</p>
 */
class AgentToolExecutionReadinessProjectionServiceTest {

    @Test
    void querySnapshotsShouldParseReadinessAttributesAndAggregateReturnedWindow() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(readinessRecord("readiness-1", "20", "run-readiness", 1, false));
        store.append(nonReadinessRuntimeEvent("tool-event-1", "20", "run-readiness", 2));
        store.append(readinessRecord("readiness-other-project", "30", "run-readiness", 3, true));
        AgentToolExecutionReadinessProjectionService service = new AgentToolExecutionReadinessProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentToolExecutionReadinessProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-readiness", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals("runtime-event-projection-fallback", response.indexSource());
        assertEquals(1L, response.executableWindowCount());
        assertEquals(1L, response.approvalRequiredWindowCount());
        assertEquals(1L, response.draftOnlyWindowCount());
        assertEquals(0L, response.blockedWindowCount());
        assertEquals(1L, response.decisionCounts().get("ready_to_execute"));
        assertEquals(1L, response.decisionCounts().get("waiting_approval"));
        assertEquals(1L, response.toolNameCounts().get("datasource.metadata.read"));
        assertEquals(1L, response.nextActionCounts().get("EXECUTE_READY_TOOLS"));

        AgentToolExecutionReadinessProjectionView snapshot = response.snapshots().getFirst();
        assertEquals("tool_execution_readiness_recorded", snapshot.eventType());
        assertEquals("TOOL_EXECUTION_READINESS", snapshot.snapshotType());
        assertEquals("LOW_SENSITIVE_METADATA_ONLY", snapshot.payloadPolicy());
        assertEquals(3, snapshot.totalCount());
        assertEquals(1, snapshot.executableCount());
        assertEquals(1, snapshot.approvalRequiredCount());
        assertEquals(List.of("EXECUTE_READY_TOOLS", "CREATE_OR_WAIT_APPROVAL", "SHOW_DRAFT_FOR_REVIEW"),
                snapshot.nextActions());
        assertEquals(1, snapshot.decisionCounts().get("ready_to_execute"));
        assertEquals(1, snapshot.riskLevelCounts().get("high"));
        assertEquals(1, snapshot.executionModeCounts().get("approval_required"));
        assertEquals(List.of("datasource.metadata.read", "quality.rule.suggest", "task.create.draft"),
                snapshot.toolNames());
        assertEquals("task.create.draft", snapshot.decisionSummaries().get(2).toolName());
        assertEquals("waiting_approval", snapshot.decisionSummaries().get(2).decision());
        assertEquals(List.of("payload"), snapshot.decisionSummaries().get(2).sensitiveArgumentNames());

        String serialized = response.toString();
        assertFalse(serialized.contains("ds-sensitive-001"));
        assertFalse(serialized.contains("手机号唯一性"));
        assertFalse(serialized.contains("select * from"));
        assertFalse(serialized.contains("toolArguments"));
        assertFalse(serialized.contains("internalEndpoint"));
    }

    @Test
    void querySnapshotsShouldUseReplaySequenceForIncrementalRead() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(readinessRecord("readiness-1", "20", "run-readiness", 1, false));
        store.append(readinessRecord("readiness-2", "20", "run-readiness", 2, true));
        AgentToolExecutionReadinessProjectionService service = new AgentToolExecutionReadinessProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentToolExecutionReadinessProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-readiness", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.blockedWindowCount());
        assertEquals(2L, response.snapshots().getFirst().replaySequence());
        assertEquals("blocked", response.snapshots().getFirst().decisionSummaries().getFirst().decision());
        assertEquals(List.of("ESCALATE_TO_OPERATOR"), response.snapshots().getFirst().nextActions());
    }

    private AgentRuntimeEventProjectionRecord readinessRecord(String identityKey,
                                                              String projectId,
                                                              String runId,
                                                              long sequence,
                                                              boolean blocked) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventPayloadVersion", "v1");
        attributes.put("snapshotType", "TOOL_EXECUTION_READINESS");
        attributes.put("payloadPolicy", "LOW_SENSITIVE_METADATA_ONLY");
        attributes.put("totalCount", blocked ? 1 : 3);
        attributes.put("executableCount", blocked ? 0 : 1);
        attributes.put("approvalRequiredCount", blocked ? 0 : 1);
        attributes.put("clarificationRequiredCount", 0);
        attributes.put("draftOnlyCount", blocked ? 0 : 1);
        attributes.put("queuedAsyncCount", 0);
        attributes.put("throttledCount", 0);
        attributes.put("blockedCount", blocked ? 1 : 0);
        attributes.put("hasBlockingDecision", blocked);
        attributes.put("nextActions", blocked
                ? List.of("ESCALATE_TO_OPERATOR")
                : List.of("EXECUTE_READY_TOOLS", "CREATE_OR_WAIT_APPROVAL", "SHOW_DRAFT_FOR_REVIEW"));
        attributes.put("decisionCounts", blocked
                ? Map.of("blocked", 1)
                : Map.of("ready_to_execute", 1, "draft_only", 1, "waiting_approval", 1));
        attributes.put("riskLevelCounts", blocked ? Map.of("critical", 1) : Map.of("low", 1, "medium", 1, "high", 1));
        attributes.put("executionModeCounts", blocked ? Map.of("sync", 1) : Map.of("sync", 1, "draft_only", 1, "approval_required", 1));
        attributes.put("toolNames", blocked
                ? List.of("dangerous.export.all")
                : List.of("datasource.metadata.read", "quality.rule.suggest", "task.create.draft"));
        attributes.put("toolNamesTruncatedCount", 0);
        attributes.put("decisionSummaries", blocked ? List.of(
                Map.ofEntries(
                        Map.entry("toolName", "dangerous.export.all"),
                        Map.entry("decision", "blocked"),
                        Map.entry("executable", false),
                        Map.entry("queueRequired", false),
                        Map.entry("requiresHumanApproval", false),
                        Map.entry("reasonCodes", List.of("CRITICAL_RISK_BLOCKED")),
                        Map.entry("issueCodes", List.of()),
                        Map.entry("parameterIssueCount", 0),
                        Map.entry("sensitiveArgumentNames", List.of("sql")),
                        Map.entry("retryHint", "NO_RUNTIME_RETRY_BEFORE_EXECUTION"),
                        Map.entry("arguments", Map.of("sql", "select * from sensitive_table"))
                )
        ) : List.of(
                Map.of(
                        "toolName", "datasource.metadata.read",
                        "decision", "ready_to_execute",
                        "executable", true,
                        "queueRequired", false,
                        "requiresHumanApproval", false,
                        "reasonCodes", List.of("READY_LOW_RISK_SYNC"),
                        "issueCodes", List.of(),
                        "parameterIssueCount", 0,
                        "sensitiveArgumentNames", List.of("datasourceId"),
                        "retryHint", "NO_RUNTIME_RETRY_BEFORE_EXECUTION"
                ),
                Map.of(
                        "toolName", "quality.rule.suggest",
                        "decision", "draft_only",
                        "executable", false,
                        "queueRequired", false,
                        "requiresHumanApproval", false,
                        "reasonCodes", List.of("DRAFT_TOOL_REVIEW_REQUIRED"),
                        "issueCodes", List.of(),
                        "parameterIssueCount", 0,
                        "sensitiveArgumentNames", List.of("datasourceId"),
                        "retryHint", "WAIT_FOR_CONTROL_PLANE"
                ),
                Map.ofEntries(
                        Map.entry("toolName", "task.create.draft"),
                        Map.entry("decision", "waiting_approval"),
                        Map.entry("executable", false),
                        Map.entry("queueRequired", false),
                        Map.entry("requiresHumanApproval", true),
                        Map.entry("reasonCodes", List.of("HUMAN_APPROVAL_REQUIRED")),
                        Map.entry("issueCodes", List.of()),
                        Map.entry("parameterIssueCount", 0),
                        Map.entry("sensitiveArgumentNames", List.of("payload")),
                        Map.entry("retryHint", "WAIT_FOR_CONTROL_PLANE"),
                        Map.entry("payload", Map.of("datasourceId", "ds-sensitive-001"))
                )
        ));
        attributes.put("toolArguments", Map.of("businessGoal", "手机号唯一性"));
        attributes.put("sql", "select * from customer");
        attributes.put("internalEndpoint", "http://internal-agent-runtime");
        return eventRecord(identityKey, "tool_execution_readiness_recorded", projectId,
                runId, sequence, blocked ? "error" : "audit", attributes);
    }

    private AgentRuntimeEventProjectionRecord nonReadinessRuntimeEvent(String identityKey,
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
        Instant timestamp = Instant.parse("2026-06-06T12:10:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                "record_tool_execution_readiness",
                "已记录本轮工具执行准备度治理快照。",
                severity,
                "10",
                projectId,
                "1001",
                "request-readiness",
                runId,
                "session-readiness",
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
                "trace-tool-readiness-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
