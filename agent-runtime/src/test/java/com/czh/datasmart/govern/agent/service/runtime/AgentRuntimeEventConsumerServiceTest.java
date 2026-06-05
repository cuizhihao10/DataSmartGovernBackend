/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumerServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent runtime event 消费服务单元测试。
 *
 * <p>这组测试不启动 Kafka broker，也不启动 Spring 容器。
 * 原因是当前最需要保护的是业务契约本身：
 * Python 发布的 JSON 是否能被 Java 解析？重复投递是否会被幂等跳过？
 * 缺少关键关联 ID 的消息是否会被拒绝？投影窗口是否会限制内存增长？
 * 这些规则比 Kafka 网络联通更基础，应优先用低成本单测固定下来。</p>
 */
class AgentRuntimeEventConsumerServiceTest {

    @Test
    void consumeShouldAcceptPythonRuntimeEventAndCreateRunProjection() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);

        AgentRuntimeEventConsumeResult result = service.consume(runtimeEventPayload("run-001", 7));

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        assertEquals(1, store.size());
        List<AgentRuntimeEventProjectionRecord> records = store.listByRunId("run-001");
        assertEquals(1, records.size());
        AgentRuntimeEventProjectionRecord record = records.getFirst();
        assertEquals("agent-runtime-event.v1", record.schemaVersion());
        assertEquals("python-ai-runtime", record.source());
        assertEquals("tool_planned", record.eventType());
        assertEquals("audit", record.severity());
        assertEquals("tenant-a", record.tenantId());
        assertEquals("project-a", record.projectId());
        assertEquals("actor-a", record.actorId());
        assertEquals(7L, record.sequence());
        assertEquals(128, record.attributes().get("tokens"));
        assertTrue(store.findByIdentityKey(result.identityKey()).isPresent());
        assertEquals(1, stats.snapshot().acceptedCount());
    }

    @Test
    void consumeShouldAcceptSkillVisibilitySnapshotRuntimeEvent() {
        /*
         * Python Runtime 6.14 会把 intelligentGatewayGovernance.skillVisibility 压缩成
         * skill_visibility_snapshot_recorded 事件。这里用 JSON payload 测试真实入站契约，
         * 确认 Java consumer 不需要专门改 schema 就能接收数组、嵌套对象和低敏聚合属性。
         * 6.16 后事件还会携带 Manifest 指纹绑定字段，用来证明本轮会话使用了哪版 Skill 发布目录。
         */
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                store,
                new AgentRuntimeEventConsumerStats()
        );

        AgentRuntimeEventConsumeResult result = service.consume(skillVisibilityRuntimeEventPayload());

        assertTrue(result.accepted());
        AgentRuntimeEventProjectionRecord record = store.listByRunId("run-skill-001").getFirst();
        assertEquals("skill_visibility_snapshot_recorded", record.eventType());
        assertEquals("audit", record.severity());
        assertEquals(2, record.attributes().get("visibleSkillCount"));
        assertEquals("BOUND_REMOTE_MANIFEST", record.attributes().get("manifestBindingStatus"));
        assertEquals("skill-manifest-fp-test", record.attributes().get("manifestFingerprint"));
        assertEquals(List.of("datasource.profiling", "quality.rule.design"), record.attributes().get("visibleSkillCodes"));
        assertTrue(record.attributes().containsKey("hiddenAdmissionStatusCounts"));
    }

    @Test
    void consumeShouldAcceptAgentSessionSchedulingRuntimeEvent() {
        /*
         * Python Runtime 5.14 会把 intelligentGatewayGovernance.agentSessionScheduling 压缩成
         * agent_session_scheduling_recorded 事件。Java consumer 不应把它当作未知异常事件拒绝；
         * 它要进入通用 projection，供后续强类型查询服务和 Java projection/index 使用。
         */
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                store,
                new AgentRuntimeEventConsumerStats()
        );

        AgentRuntimeEventConsumeResult result = service.consume(agentSessionSchedulingRuntimeEventPayload());

        assertTrue(result.accepted());
        AgentRuntimeEventProjectionRecord record = store.listByRunId("run-agent-session-001").getFirst();
        assertEquals("agent_session_scheduling_recorded", record.eventType());
        assertEquals("audit", record.severity());
        assertEquals("APPROVAL_REQUIRED", record.attributes().get("status"));
        assertEquals("MASTER_ORCHESTRATOR", record.attributes().get("primaryAgentRole"));
        assertEquals(List.of("MASTER_ORCHESTRATOR", "TASK_AGENT", "PERMISSION_AGENT"), record.attributes().get("participatingAgentRoles"));
        assertEquals(List.of("TASK_AGENT", "PERMISSION_AGENT"), record.attributes().get("handoffAgentRoles"));
        assertTrue(record.attributes().containsKey("participationModeCounts"));
    }

    @Test
    void consumeShouldMaterializeSkillVisibilitySnapshotIntoDedicatedIndex() {
        /*
         * 专用索引是从通用 runtime event 热窗口走向长期审计索引的第一步。
         * 这个测试证明：consumer 首次接收 Skill 可见性事件时，会把已经分配 replaySequence 的记录同步写入索引；
         * 同一 payload 再次投递时，通用 projection 先识别 duplicate，专用索引也不会被重复放大。
         */
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentSkillVisibilitySnapshotIndexStore indexStore =
                new InMemoryAgentSkillVisibilitySnapshotIndexStore(10, 100);
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                projectionStore,
                new AgentRuntimeEventConsumerStats(),
                Optional.of(indexStore)
        );

        AgentRuntimeEventConsumeResult first = service.consume(skillVisibilityRuntimeEventPayload());
        AgentRuntimeEventConsumeResult duplicate = service.consume(skillVisibilityRuntimeEventPayload());

        assertTrue(first.accepted());
        assertTrue(duplicate.duplicate());
        assertEquals(1, indexStore.size());
        List<AgentRuntimeEventProjectionRecord> indexedSnapshots = indexStore.query(
                new AgentRuntimeEventProjectionQuery("10", "20", null, null,
                        "run-skill-001", null, null, null, 20)
        );
        assertEquals(1, indexedSnapshots.size());
        assertEquals(1L, indexedSnapshots.getFirst().replaySequence());
        assertEquals("skill-manifest-fp-test", indexedSnapshots.getFirst().attributes().get("manifestFingerprint"));
    }

    @Test
    void duplicateProjectionShouldStillRetrySkillVisibilityIndexMaterialization() {
        /*
         * 这个测试覆盖 6.19 的可靠性修正：
         * 第一次消费时 projection 已经写入成功，但专用索引模拟 MySQL 故障抛错；
         * Kafka 随后重放同一 payload 时，projection 会判定 duplicate。
         *
         * 如果 duplicate 分支直接返回，专用索引就永远缺失这条低敏快照；
         * 修正后 duplicate 分支仍会幂等尝试专用索引 append，从而让 MySQL 恢复后有补写机会。
         */
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        RecoveringSkillVisibilitySnapshotIndexStore indexStore = new RecoveringSkillVisibilitySnapshotIndexStore();
        AgentSkillVisibilitySnapshotIndexTelemetry telemetry =
                AgentSkillVisibilitySnapshotIndexTelemetry.inMemoryOnly();
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                projectionStore,
                new AgentRuntimeEventConsumerStats(),
                Optional.of(indexStore),
                telemetry
        );

        assertThrows(IllegalStateException.class, () -> service.consume(skillVisibilityRuntimeEventPayload()));
        AgentRuntimeEventConsumeResult retry = service.consume(skillVisibilityRuntimeEventPayload());

        assertTrue(retry.duplicate());
        assertEquals(1, projectionStore.size());
        assertEquals(1, indexStore.size());
        assertEquals(2, indexStore.appendAttempts());
        assertEquals(1L, telemetry.snapshot().failedMaterializationCount());
        assertEquals(1L, telemetry.snapshot().materializedCount());
    }

    @Test
    void consumeShouldTreatSameEventAsDuplicate() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        AgentRuntimeEventConsumerStats stats = new AgentRuntimeEventConsumerStats();
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(objectMapper(), store, stats);
        String payload = runtimeEventPayload("run-duplicate", 1);

        AgentRuntimeEventConsumeResult first = service.consume(payload);
        AgentRuntimeEventConsumeResult second = service.consume(payload);

        assertTrue(first.accepted());
        assertTrue(second.duplicate());
        assertEquals(first.identityKey(), second.identityKey());
        assertEquals(1, store.size());
        assertEquals(1, stats.snapshot().acceptedCount());
        assertEquals(1, stats.snapshot().duplicateCount());
    }

    @Test
    void consumeShouldRejectMalformedOrUnsupportedPayload() {
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100),
                new AgentRuntimeEventConsumerStats()
        );

        assertEquals("EMPTY_PAYLOAD", service.consume(" ").reason());
        assertEquals("MALFORMED_JSON", service.consume("{not-json").reason());
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", service.consume("""
                {"schemaVersion":"agent-runtime-event.v2","eventType":"tool_planned","runId":"run-a"}
                """).reason());
        assertEquals("MISSING_EVENT_TYPE", service.consume("""
                {"schemaVersion":"agent-runtime-event.v1","runId":"run-a"}
                """).reason());
        assertEquals("MISSING_CORRELATION_ID", service.consume("""
                {"schemaVersion":"agent-runtime-event.v1","eventType":"tool_planned"}
                """).reason());
    }

    @Test
    void projectionStoreShouldTrimPerRunAndGlobalWindows() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(2, 3);
        AgentRuntimeEventConsumerService service = new AgentRuntimeEventConsumerService(
                objectMapper(),
                store,
                new AgentRuntimeEventConsumerStats()
        );

        service.consume(runtimeEventPayload("run-window", 1));
        service.consume(runtimeEventPayload("run-window", 2));
        service.consume(runtimeEventPayload("run-window", 3));

        List<AgentRuntimeEventProjectionRecord> runWindow = store.listByRunId("run-window");
        assertEquals(2, runWindow.size());
        assertEquals(2L, runWindow.get(0).sequence());
        assertEquals(3L, runWindow.get(1).sequence());

        service.consume(runtimeEventPayload("run-a", 1));
        service.consume(runtimeEventPayload("run-b", 1));

        assertEquals(3, store.size());
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private String runtimeEventPayload(String runId, long sequence) {
        return """
                {
                  "schemaVersion": "agent-runtime-event.v1",
                  "source": "python-ai-runtime",
                  "publishedAt": "2026-05-27T00:00:01Z",
                  "eventType": "tool_planned",
                  "stage": "plan_tools",
                  "message": "已生成工具调用计划",
                  "severity": "audit",
                  "tenantId": "tenant-a",
                  "projectId": "project-a",
                  "actorId": "actor-a",
                  "requestId": "request-a",
                  "runId": "%s",
                  "sessionId": "session-a",
                  "sequence": %d,
                  "attributes": {"tokens": 128, "toolName": "datasource.metadata.read"},
                  "createdAt": "2026-05-27T00:00:%02dZ"
                }
                """.formatted(runId, sequence, sequence);
    }

    private String skillVisibilityRuntimeEventPayload() {
        return """
                {
                  "schemaVersion": "agent-runtime-event.v1",
                  "source": "python-ai-runtime",
                  "publishedAt": "2026-06-04T11:00:01Z",
                  "eventType": "skill_visibility_snapshot_recorded",
                  "stage": "record_skill_visibility_snapshot",
                  "message": "已记录本轮会话级 Skill 可见性快照。",
                  "severity": "audit",
                  "tenantId": "10",
                  "projectId": "20",
                  "actorId": "1001",
                  "requestId": "request-skill-001",
                  "runId": "run-skill-001",
                  "sessionId": "session-skill-001",
                  "sequence": 9,
                  "attributes": {
                    "eventPayloadVersion": "v1",
                    "snapshotType": "SESSION_SKILL_VISIBILITY_SNAPSHOT",
                    "snapshotSource": "agent-plan-skill-admission",
                    "available": false,
                    "visibleSkillCount": 2,
                    "hiddenSkillCount": 1,
                    "conditionalVisibleSkillCount": 1,
                    "permissionFactSource": "legacy-request-variables",
                    "manifestBindingStatus": "BOUND_REMOTE_MANIFEST",
                    "manifestStatus": "REMOTE_READY",
                    "manifestSource": "java-agent-runtime",
                    "manifestFingerprint": "skill-manifest-fp-test",
                    "manifestSchemaVersion": "agent-skill-publication-manifest.v1",
                    "manifestSkillCount": 6,
                    "manifestReadySkillCount": 5,
                    "manifestNonReadySkillCount": 1,
                    "manifestFallback": false,
                    "visibleSkillCodes": ["datasource.profiling", "quality.rule.design"],
                    "hiddenSkillCodes": ["compliance.masking"],
                    "visibleRiskLevelCounts": {"LOW": 1, "MEDIUM": 1},
                    "visibleDomainCounts": {"DATASOURCE": 1, "QUALITY": 1},
                    "hiddenAdmissionStatusCounts": {"DENIED_MISSING_PERMISSION": 1},
                    "recommendedActionCount": 2
                  },
                  "createdAt": "2026-06-04T11:00:00Z"
                }
                """;
    }

    private String agentSessionSchedulingRuntimeEventPayload() {
        return """
                {
                  "schemaVersion": "agent-runtime-event.v1",
                  "source": "python-ai-runtime",
                  "publishedAt": "2026-06-05T12:00:01Z",
                  "eventType": "agent_session_scheduling_recorded",
                  "stage": "record_agent_session_scheduling",
                  "message": "已记录本轮多 Agent 会话调度策略视图。",
                  "severity": "audit",
                  "tenantId": "10",
                  "projectId": "20",
                  "actorId": "1001",
                  "requestId": "request-agent-session-001",
                  "runId": "run-agent-session-001",
                  "sessionId": "session-agent-session-001",
                  "sequence": 10,
                  "attributes": {
                    "eventPayloadVersion": "v1",
                    "snapshotType": "AGENT_SESSION_SCHEDULING_POLICY_VIEW",
                    "available": true,
                    "status": "APPROVAL_REQUIRED",
                    "primaryAgentRole": "MASTER_ORCHESTRATOR",
                    "participatingAgentCount": 3,
                    "participatingAgentRoles": ["MASTER_ORCHESTRATOR", "TASK_AGENT", "PERMISSION_AGENT"],
                    "participatingAgentRolesTruncatedCount": 0,
                    "participationModeCounts": {"PRIMARY": 1, "SPECIALIST": 1, "GUARDRAIL": 1},
                    "agentStatusCounts": {"APPROVAL_REQUIRED": 3},
                    "handoffRequired": true,
                    "handoffAgentRoles": ["TASK_AGENT", "PERMISSION_AGENT"],
                    "intentDomains": ["task_management"],
                    "selectedSkillCodes": ["governed.task.creation"],
                    "visibleSkillCodes": ["governed.task.creation"],
                    "plannedToolNames": ["task.create.draft"],
                    "memoryDependencies": ["episodic", "procedural"],
                    "modelGatewayAvailable": true,
                    "skillAdmissionAllowed": true,
                    "toolBudgetAllowed": true,
                    "approvalRequired": true,
                    "tenantScoped": true,
                    "projectScoped": true,
                    "displaySummary": "智能网关已调度 3 个 Agent，其中部分动作需要人工审批后才能执行。",
                    "recommendedActionCount": 1
                  },
                  "createdAt": "2026-06-05T12:00:00Z"
                }
                """;
    }

    private static class RecoveringSkillVisibilitySnapshotIndexStore implements AgentSkillVisibilitySnapshotIndexStore {

        private final InMemoryAgentSkillVisibilitySnapshotIndexStore delegate =
                new InMemoryAgentSkillVisibilitySnapshotIndexStore(10, 100);
        private final AtomicInteger appendAttempts = new AtomicInteger();

        @Override
        public boolean append(AgentRuntimeEventProjectionRecord record) {
            if (appendAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("模拟 Skill 可见性 MySQL 索引暂时不可用");
            }
            return delegate.append(record);
        }

        @Override
        public List<AgentRuntimeEventProjectionRecord> query(AgentRuntimeEventProjectionQuery query) {
            return delegate.query(query);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        private int appendAttempts() {
            return appendAttempts.get();
        }
    }
}
