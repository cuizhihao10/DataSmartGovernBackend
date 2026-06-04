/**
 * @Author : Cui
 * @Date: 2026/06/04 19:16
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotProjectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentSkillVisibilitySnapshotProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillVisibilitySnapshotProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 可见性快照投影查询服务测试。
 *
 * <p>这组测试保护 Java 控制面对 Python `SKILL_VISIBILITY_SNAPSHOT_RECORDED` 事件的产品级解释能力。
 * 通用 runtime event 查询只能证明“事件被收到了”；本测试进一步证明 Java 能按 Skill 可见性语义：
 * 1. 固定过滤 eventType；
 * 2. 遵守租户/项目数据范围；
 * 3. 把自由 Map attributes 转为强类型 DTO；
 * 4. 输出低敏聚合摘要。</p>
 */
class AgentSkillVisibilitySnapshotProjectionServiceTest {

    @Test
    void querySnapshotsShouldParseSkillVisibilityAttributesAndAggregateReturnedWindow() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(skillVisibilityRecord("skill-snapshot-1", "20", "run-skill", 1, true));
        store.append(nonSkillRuntimeEvent("tool-event-1", "20", "run-skill", 2));
        store.append(skillVisibilityRecord("skill-snapshot-other-project", "30", "run-skill", 3, false));
        AgentSkillVisibilitySnapshotProjectionService service = new AgentSkillVisibilitySnapshotProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentSkillVisibilitySnapshotProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-skill", null, null, null, 20),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(1L, response.availableSnapshotCount());
        assertEquals(0L, response.unavailableSnapshotCount());
        assertEquals(2, response.totalVisibleSkillCount());
        assertEquals(1, response.totalHiddenSkillCount());
        assertEquals(1L, response.permissionFactSourceCounts().get("trusted-control-plane"));
        assertEquals(1, response.hiddenAdmissionStatusCounts().get("DENIED_MISSING_PERMISSION"));

        AgentSkillVisibilitySnapshotProjectionView snapshot = response.snapshots().getFirst();
        assertEquals("skill_visibility_snapshot_recorded", snapshot.eventType());
        assertEquals("SESSION_SKILL_VISIBILITY_SNAPSHOT", snapshot.snapshotType());
        assertEquals("trusted-control-plane", snapshot.permissionFactSource());
        assertEquals("PROJECT_OWNER", snapshot.actorRole());
        assertEquals(List.of("datasource.profiling", "quality.rule.design"), snapshot.visibleSkillCodes());
        assertEquals(List.of("compliance.masking"), snapshot.hiddenSkillCodes());
        assertEquals(1, snapshot.visibleRiskLevelCounts().get("LOW"));
        assertEquals(1, snapshot.visibleDomainCounts().get("QUALITY"));
        assertEquals(2, snapshot.recommendedActionCount());
    }

    @Test
    void querySnapshotsShouldUseReplaySequenceForIncrementalRead() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(skillVisibilityRecord("skill-snapshot-1", "20", "run-skill", 1, true));
        store.append(skillVisibilityRecord("skill-snapshot-2", "20", "run-skill", 2, false));
        AgentSkillVisibilitySnapshotProjectionService service = new AgentSkillVisibilitySnapshotProjectionService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );

        AgentSkillVisibilitySnapshotProjectionQueryResponse response = service.querySnapshots(
                new AgentRuntimeEventProjectionQuery("10", null, null, null,
                        "run-skill", null, null, null, 20, 1L),
                projectOwnerContext()
        );

        assertEquals(1, response.totalMatched());
        assertEquals(0L, response.availableSnapshotCount());
        assertEquals(1L, response.unavailableSnapshotCount());
        assertEquals(2L, response.snapshots().getFirst().replaySequence());
        assertTrue(response.snapshots().getFirst().hiddenSkillCount() > 0);
    }

    private AgentRuntimeEventProjectionRecord skillVisibilityRecord(String identityKey,
                                                                    String projectId,
                                                                    String runId,
                                                                    long sequence,
                                                                    boolean available) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventPayloadVersion", "v1");
        attributes.put("snapshotType", "SESSION_SKILL_VISIBILITY_SNAPSHOT");
        attributes.put("snapshotSource", "agent-plan-skill-admission");
        attributes.put("available", available);
        attributes.put("availableSkillCount", 4);
        attributes.put("visibleSkillCount", 2);
        attributes.put("hiddenSkillCount", available ? 1 : 2);
        attributes.put("conditionalVisibleSkillCount", available ? 0 : 1);
        attributes.put("permissionFactSource", available ? "trusted-control-plane" : "legacy-request-variables");
        attributes.put("actorRoleSource", "trusted-control-plane");
        attributes.put("actorRole", "PROJECT_OWNER");
        attributes.put("grantedPermissionCount", 3);
        attributes.put("tenantSkillEnabled", true);
        attributes.put("workspaceRiskLevel", "NORMAL");
        attributes.put("tenantPlanCode", "STANDARD");
        attributes.put("policyVersion", "policy-v1");
        attributes.put("legacyRequestVariablesDetected", !available);
        attributes.put("modelGatewayAvailable", true);
        attributes.put("toolBudgetAllowed", true);
        attributes.put("visibleSkillCodes", List.of("datasource.profiling", "quality.rule.design"));
        attributes.put("visibleSkillCodesTruncatedCount", 0);
        attributes.put("hiddenSkillCodes", List.of("compliance.masking"));
        attributes.put("hiddenSkillCodesTruncatedCount", 0);
        attributes.put("visibleRiskLevelCounts", Map.of("LOW", 1, "MEDIUM", 1));
        attributes.put("visibleDomainCounts", Map.of("DATASOURCE", 1, "QUALITY", 1));
        attributes.put("hiddenAdmissionStatusCounts", Map.of("DENIED_MISSING_PERMISSION", available ? 1 : 2));
        attributes.put("displaySummary", "当前会话已生成可见 Skill 能力快照。");
        attributes.put("recommendedActionCount", 2);
        return eventRecord(
                identityKey,
                "skill_visibility_snapshot_recorded",
                projectId,
                runId,
                sequence,
                available ? "info" : "audit",
                attributes
        );
    }

    private AgentRuntimeEventProjectionRecord nonSkillRuntimeEvent(String identityKey,
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
        Instant timestamp = Instant.parse("2026-06-04T11:00:0" + sequence + "Z");
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                "agent-runtime-event.v1",
                "python-ai-runtime",
                eventType,
                "record_skill_visibility_snapshot",
                "已记录本轮会话级 Skill 可见性快照。",
                severity,
                "10",
                projectId,
                "1001",
                "request-skill",
                runId,
                "session-skill",
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
                "trace-skill-visibility-test",
                "PROJECT",
                List.of(20L)
        );
    }
}
