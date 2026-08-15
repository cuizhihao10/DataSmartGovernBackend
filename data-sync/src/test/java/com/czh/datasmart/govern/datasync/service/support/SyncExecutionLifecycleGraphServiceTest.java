/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncExecutionLifecycleGraphServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncAgentExecutionCorrelation;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.agent.AgentRuntimeAuditObservation;
import com.czh.datasmart.govern.datasync.integration.agent.AgentRuntimeCommandObservation;
import com.czh.datasmart.govern.datasync.integration.agent.HttpAgentRuntimeAuditObservationClient;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证统一图只聚合已有事实，并对缺失来源给出显式状态。 */
class SyncExecutionLifecycleGraphServiceTest {

    /** Agent + Kafka Recovery + worker 成功时应形成可验证的完整有向链。 */
    @Test
    void shouldProjectRecoveredAgentExecutionAsCompleteLifecycle() {
        Fixture fixture = fixture();
        SyncAgentExecutionCorrelation correlation = correlation();
        when(fixture.recoveryQuery.query(fixture.task, 41L)).thenReturn(recovered());
        when(fixture.correlationSupport.findLatest(11L, 31L, 41L)).thenReturn(correlation);
        when(fixture.auditClient.observe("session-1", "run-1", "audit-1", fixture.actorContext))
                .thenReturn(audit());
        when(fixture.auditClient.observeCommand("session-1", "run-1", "command-1", fixture.actorContext))
                .thenReturn(command());

        SyncExecutionLifecycleGraphView graph = fixture.service.query(fixture.task, 41L, fixture.actorContext);

        assertThat(graph.sourceStatus()).isEqualTo("COMPLETE");
        assertThat(graph.overallState()).isEqualTo("VERIFIED");
        assertThat(graph.nodes()).extracting(SyncExecutionLifecycleGraphView.LifecycleNode::nodeType)
                .containsSubsequence("USER_GOAL", "AGENT", "COMMAND_DISPATCH", "JAVA_AUDIT",
                        "WORKER", "KAFKA_EVENT", "RECOVERY", "FINAL_VERIFICATION");
        assertThat(graph.nodes()).filteredOn(node -> "COMMAND_DISPATCH".equals(node.nodeType()))
                .singleElement().extracting(SyncExecutionLifecycleGraphView.LifecycleNode::state)
                .isEqualTo("PUBLISHED");
        assertThat(graph.nodes()).filteredOn(node -> "KAFKA_EVENT".equals(node.nodeType()))
                .singleElement().extracting(SyncExecutionLifecycleGraphView.LifecycleNode::state)
                .isEqualTo("CONSUMED");
        assertThat(graph.nodes()).filteredOn(node -> "JAVA_AUDIT".equals(node.nodeType()))
                .singleElement().extracting(SyncExecutionLifecycleGraphView.LifecycleNode::state)
                .isEqualTo("SUCCEEDED");
        assertThat(graph.edges()).allSatisfy(edge -> {
            assertThat(edge.fromNodeId()).isNotBlank();
            assertThat(edge.toNodeId()).isNotBlank();
        });
        assertThat(graph.evidence()).extracting(SyncExecutionLifecycleGraphView.LifecycleEvidence::kind)
                .contains("AGENT_EXECUTION_CORRELATION", "TOOL_EXECUTION_AUDIT", "WORKER_EXECUTION",
                        "AUTOPILOT_RECOVERY_KAFKA", "AUTOPILOT_RECOVERY_CASE");
    }

    /** 手工执行没有 Agent 关联时仍展示 worker 终态，但不能伪造 Agent 或 Kafka 成功。 */
    @Test
    void shouldExposeManualExecutionAsNotLinkedInsteadOfFabricatingAgentFacts() {
        Fixture fixture = fixture();
        when(fixture.recoveryQuery.query(fixture.task, 41L)).thenReturn(
                SyncAutopilotRecoveryStatusView.unavailable(
                        31L, 41L, "SUCCEEDED", LocalDateTime.of(2026, 8, 15, 2, 0)));
        when(fixture.correlationSupport.findLatest(11L, 31L, 41L)).thenReturn(null);

        SyncExecutionLifecycleGraphView graph = fixture.service.query(fixture.task, 41L, fixture.actorContext);

        assertThat(graph.sourceStatus()).isEqualTo("NOT_LINKED");
        assertThat(graph.missingReason()).isEqualTo("AGENT_EXECUTION_NOT_LINKED");
        assertThat(graph.overallState()).isEqualTo("VERIFIED");
        assertThat(graph.nodes()).filteredOn(node -> "JAVA_AUDIT".equals(node.nodeType()))
                .singleElement().extracting(SyncExecutionLifecycleGraphView.LifecycleNode::state)
                .isEqualTo("NOT_APPLICABLE");
        verify(fixture.auditClient, org.mockito.Mockito.never()).observe(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fixture.auditClient, org.mockito.Mockito.never()).observeCommand(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /** 直接 Agent 工具入口只要求 Java 审计完整，不能伪造初始 command/Kafka 事实。 */
    @Test
    void shouldProjectDirectAgentToolWithoutInitialCommandAsComplete() {
        Fixture fixture = fixture();
        SyncAgentExecutionCorrelation correlation = correlation();
        correlation.setEntryMode("DIRECT_AGENT_TOOL");
        correlation.setCommandId(null);
        when(fixture.recoveryQuery.query(fixture.task, 41L)).thenReturn(
                SyncAutopilotRecoveryStatusView.unavailable(
                        31L, 41L, "SUCCEEDED", LocalDateTime.of(2026, 8, 15, 2, 0)));
        when(fixture.correlationSupport.findLatest(11L, 31L, 41L)).thenReturn(correlation);
        when(fixture.auditClient.observe("session-1", "run-1", "audit-1", fixture.actorContext))
                .thenReturn(audit());

        SyncExecutionLifecycleGraphView graph = fixture.service.query(fixture.task, 41L, fixture.actorContext);

        assertThat(graph.sourceStatus()).isEqualTo("COMPLETE");
        assertThat(graph.nodes()).filteredOn(node -> "COMMAND_DISPATCH".equals(node.nodeType()))
                .singleElement().satisfies(node -> {
                    assertThat(node.state()).isEqualTo("NOT_APPLICABLE");
                    assertThat(node.reasonCode()).isEqualTo("DIRECT_AGENT_TOOL_WITHOUT_INITIAL_COMMAND");
                });
        assertThat(graph.evidence()).extracting(SyncExecutionLifecycleGraphView.LifecycleEvidence::kind)
                .doesNotContain("COMMAND_OUTBOX");
        verify(fixture.auditClient, org.mockito.Mockito.never()).observeCommand(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** Recovery 创建新 execution 时必须保留根失败，再展示恢复和当前重跑，不能把因果顺序画反。 */
    @Test
    void shouldPlaceRootFailureBeforeRecoveryAndCurrentReplay() {
        Fixture fixture = fixture();
        SyncExecution root = new SyncExecution();
        root.setId(41L);
        root.setExecutionState("FAILED");
        root.setFinishedAt(LocalDateTime.of(2026, 8, 15, 1, 5));
        when(fixture.executionMapper.selectById(41L)).thenReturn(root);
        when(fixture.recoveryQuery.query(fixture.task, 41L)).thenReturn(recoveredWithReplay());
        when(fixture.correlationSupport.findLatest(11L, 31L, 41L)).thenReturn(correlation());
        when(fixture.auditClient.observe("session-1", "run-1", "audit-1", fixture.actorContext))
                .thenReturn(audit());
        when(fixture.auditClient.observeCommand(
                "session-1", "run-1", "command-1", fixture.actorContext)).thenReturn(command());

        SyncExecutionLifecycleGraphView graph = fixture.service.query(fixture.task, 41L, fixture.actorContext);

        assertThat(graph.nodes()).extracting(SyncExecutionLifecycleGraphView.LifecycleNode::nodeId)
                .containsSubsequence("root-worker-execution", "recovery-kafka-event", "recovery",
                        "current-worker-execution", "final-verification");
        assertThat(graph.nodes()).filteredOn(node -> "root-worker-execution".equals(node.nodeId()))
                .singleElement().extracting(SyncExecutionLifecycleGraphView.LifecycleNode::state)
                .isEqualTo("FAILED");
        assertThat(graph.nodes()).filteredOn(node -> "current-worker-execution".equals(node.nodeId()))
                .singleElement().extracting(SyncExecutionLifecycleGraphView.LifecycleNode::state)
                .isEqualTo("SUCCEEDED");
        assertThat(graph.evidence()).extracting(SyncExecutionLifecycleGraphView.LifecycleEvidence::kind)
                .contains("ROOT_WORKER_EXECUTION", "WORKER_EXECUTION");
    }

    /** 只有 Recovery case 而没有 outbox/consumer 时，不能反向伪造 Kafka 已触发。 */
    @Test
    void shouldNotFabricateKafkaFactFromRecoveryCaseAlone() {
        Fixture fixture = fixture();
        when(fixture.recoveryQuery.query(fixture.task, 41L)).thenReturn(recoveredWithoutKafkaFact());
        when(fixture.correlationSupport.findLatest(11L, 31L, 41L)).thenReturn(correlation());
        when(fixture.auditClient.observe("session-1", "run-1", "audit-1", fixture.actorContext))
                .thenReturn(audit());
        when(fixture.auditClient.observeCommand(
                "session-1", "run-1", "command-1", fixture.actorContext)).thenReturn(command());

        SyncExecutionLifecycleGraphView graph = fixture.service.query(fixture.task, 41L, fixture.actorContext);

        assertThat(graph.nodes()).filteredOn(node -> "KAFKA_EVENT".equals(node.nodeType()))
                .singleElement().satisfies(node -> {
                    assertThat(node.state()).isEqualTo("NOT_RECORDED");
                    assertThat(node.reasonCode()).isEqualTo("RECOVERY_KAFKA_NOT_RECORDED");
                    assertThat(node.evidenceId()).isNull();
                    assertThat(node.occurredAt()).isNull();
                });
        assertThat(graph.evidence()).extracting(SyncExecutionLifecycleGraphView.LifecycleEvidence::kind)
                .contains("AUTOPILOT_RECOVERY_CASE")
                .doesNotContain("AUTOPILOT_RECOVERY_KAFKA");
    }

    private Fixture fixture() {
        SyncAutopilotRecoveryStatusQueryService recoveryQuery = mock(SyncAutopilotRecoveryStatusQueryService.class);
        SyncAgentExecutionCorrelationSupport correlationSupport = mock(SyncAgentExecutionCorrelationSupport.class);
        HttpAgentRuntimeAuditObservationClient auditClient = mock(HttpAgentRuntimeAuditObservationClient.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(11L);
        task.setProjectId(13L);
        SyncActorContext actorContext = new SyncActorContext(11L, 7L, "ORDINARY_USER", "trace-1");
        return new Fixture(new SyncExecutionLifecycleGraphService(
                recoveryQuery, correlationSupport, auditClient, executionMapper),
                recoveryQuery, correlationSupport, auditClient, executionMapper, task, actorContext);
    }

    private SyncAgentExecutionCorrelation correlation() {
        SyncAgentExecutionCorrelation correlation = new SyncAgentExecutionCorrelation();
        correlation.setId(91L);
        correlation.setTenantId(11L);
        correlation.setProjectId(13L);
        correlation.setSyncTaskId(31L);
        correlation.setSyncExecutionId(41L);
        correlation.setCommandId("command-1");
        correlation.setEntryMode("ASYNC_AGENT_COMMAND");
        correlation.setSessionId("session-1");
        correlation.setRunId("run-1");
        correlation.setAuditId("audit-1");
        correlation.setCreateTime(LocalDateTime.of(2026, 8, 15, 1, 0));
        return correlation;
    }

    private AgentRuntimeAuditObservation audit() {
        return new AgentRuntimeAuditObservation(true, true, "SUCCEEDED", "data-sync.execute",
                "LOW", false, LocalDateTime.of(2026, 8, 15, 1, 1),
                LocalDateTime.of(2026, 8, 15, 1, 2), LocalDateTime.of(2026, 8, 15, 1, 2),
                null, "工具执行完成", "AGENT_RUNTIME_AUDIT");
    }

    private AgentRuntimeCommandObservation command() {
        return new AgentRuntimeCommandObservation(true, true, "PUBLISHED", 1,
                LocalDateTime.of(2026, 8, 15, 1, 2), LocalDateTime.of(2026, 8, 15, 1, 2),
                "AGENT_RUNTIME_COMMAND_OUTBOX");
    }

    private SyncAutopilotRecoveryStatusView recovered() {
        LocalDateTime finished = LocalDateTime.of(2026, 8, 15, 1, 10);
        return new SyncAutopilotRecoveryStatusView(
                true, 31L, 41L, 41L, "SUCCEEDED", finished,
                81L, "RECOVERED", 1, 3, "REPAIR_FIELD_MAPPING", "LOW", null,
                LocalDateTime.of(2026, 8, 15, 2, 0), 2L,
                LocalDateTime.of(2026, 8, 15, 1, 3), finished,
                "DELIVERED", 1, 8, null, null, null,
                "RECOVERY_STARTED", "AUTOPILOT_REPAIR_APPLIED", LocalDateTime.of(2026, 8, 15, 1, 4),
                "SKIP", "STRUCTURED_DIAGNOSTIC", 0, null,
                null, null, null, null, null);
    }

    private SyncAutopilotRecoveryStatusView recoveredWithReplay() {
        LocalDateTime finished = LocalDateTime.of(2026, 8, 15, 1, 10);
        return new SyncAutopilotRecoveryStatusView(
                true, 31L, 41L, 42L, "SUCCEEDED", finished,
                81L, "RECOVERED", 1, 3, "REPLAY_FAILED_SHARD", "LOW", null,
                LocalDateTime.of(2026, 8, 15, 2, 0), 2L,
                LocalDateTime.of(2026, 8, 15, 1, 3), finished,
                "DELIVERED", 1, 8, null, null, null,
                "RECOVERY_STARTED", "AUTOPILOT_REPAIR_APPLIED", LocalDateTime.of(2026, 8, 15, 1, 4),
                "SEARCH", "HYBRID", 2, "digest",
                null, null, null, null, null);
    }

    private SyncAutopilotRecoveryStatusView recoveredWithoutKafkaFact() {
        LocalDateTime finished = LocalDateTime.of(2026, 8, 15, 1, 10);
        return new SyncAutopilotRecoveryStatusView(
                true, 31L, 41L, 41L, "SUCCEEDED", finished,
                81L, "RECOVERED", 1, 3, "REPAIR_FIELD_MAPPING", "LOW", null,
                LocalDateTime.of(2026, 8, 15, 2, 0), 2L,
                LocalDateTime.of(2026, 8, 15, 1, 3), finished,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null);
    }

    private record Fixture(
            SyncExecutionLifecycleGraphService service,
            SyncAutopilotRecoveryStatusQueryService recoveryQuery,
            SyncAgentExecutionCorrelationSupport correlationSupport,
            HttpAgentRuntimeAuditObservationClient auditClient,
            SyncExecutionMapper executionMapper,
            SyncTask task,
            SyncActorContext actorContext) {
    }
}
