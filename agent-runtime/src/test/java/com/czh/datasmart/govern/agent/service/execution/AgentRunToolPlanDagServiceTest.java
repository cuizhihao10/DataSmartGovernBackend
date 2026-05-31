/**
 * @Author : Cui
 * @Date: 2026/05/31 23:10
 * @Description DataSmart Govern Backend - AgentRunToolPlanDagServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolPlanDagView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanDagNodeView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent ToolPlan DAG 预检服务测试。
 *
 * <p>这些测试不执行真实工具，也不模拟 Kafka 投递。它们只验证控制面能否把“工具审计事实”解释成稳定的编排图。
 * 这样做的产品价值在于：后续要实现像 Codex/Claude Code 那样的多工具 Agent 时，真实执行器、前端可视化、
 * Python Runtime 和人工审计台都可以依赖同一个 DAG 解释结果，而不是各自重复推断依赖关系。</p>
 */
class AgentRunToolPlanDagServiceTest {

    private static final String SESSION_ID = "session-dag-001";
    private static final String RUN_ID = "run-dag-001";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 5, 31, 23, 10);

    @Test
    void legacyToolPlanShouldFallbackToLinearDag() {
        TestFixture fixture = newFixture(AgentRunState.PLANNING);
        fixture.saveAudits(
                plannedSyncAudit("audit-linear-1", 1, Map.of()),
                plannedSyncAudit("audit-linear-2", 2, Map.of()),
                plannedSyncAudit("audit-linear-3", 3, Map.of())
        );

        AgentRunToolPlanDagView view = fixture.dagService.inspectRunToolPlanDag(SESSION_ID, RUN_ID);

        assertEquals(AgentToolPlanDagDependencyMode.LEGACY_SEQUENCE.name(), view.dependencyMode());
        assertEquals(3, view.totalNodes());
        assertEquals(2, view.totalEdges());
        assertFalse(view.hasCycle());
        assertEquals(List.of("node-1", "node-2", "node-3"), view.topologicalNodeIds());
        assertEquals(List.of("node-1"), view.readyNodeIds());
        assertEquals(List.of("node-2", "node-3"), view.blockedNodeIds());
        assertEquals(List.of("node-1"), node(view, "node-2").blockedByNodeIds());
        assertEquals(List.of("node-2"), node(view, "node-3").blockedByNodeIds());
    }

    @Test
    void explicitDependenciesShouldAllowParallelPrerequisitesAndReadyJoinNode() {
        TestFixture fixture = newFixture(AgentRunState.TOOL_CALLING);
        fixture.saveAudits(
                succeededAudit("audit-read-source", 1, Map.of(
                        "planNodeId", "read-source",
                        "parallelGroup", "metadata-read"
                )),
                succeededAudit("audit-read-target", 2, Map.of(
                        "planNodeId", "read-target",
                        "parallelGroup", "metadata-read"
                )),
                plannedSyncAudit("audit-persist-draft", 3, Map.of(
                        "planNodeId", "persist-draft",
                        "dependsOn", List.of("read-source", "read-target"),
                        "parallelGroup", "draft-write",
                        "resultAlias", "draftRef"
                ))
        );

        AgentRunToolPlanDagView view = fixture.dagService.inspectRunToolPlanDag(SESSION_ID, RUN_ID);
        AgentToolPlanDagNodeView joinNode = node(view, "persist-draft");

        assertEquals(AgentToolPlanDagDependencyMode.EXPLICIT.name(), view.dependencyMode());
        assertEquals(3, view.totalNodes());
        assertEquals(2, view.totalEdges());
        assertFalse(view.hasCycle());
        assertEquals(List.of("read-source", "read-target", "persist-draft"), view.topologicalNodeIds());
        assertEquals(List.of("persist-draft"), view.readyNodeIds());
        assertEquals(List.of("read-source", "read-target"), joinNode.dependsOnNodeIds());
        assertTrue(joinNode.dependencySatisfied());
        assertTrue(joinNode.readyForExecution());
        assertEquals("draftRef", joinNode.resultAlias());
    }

    @Test
    void cyclicDependenciesShouldBeDetectedAndBlocked() {
        TestFixture fixture = newFixture(AgentRunState.PLANNING);
        fixture.saveAudits(
                plannedSyncAudit("audit-cycle-a", 1, Map.of(
                        "planNodeId", "cycle-a",
                        "dependsOn", "cycle-b"
                )),
                plannedSyncAudit("audit-cycle-b", 2, Map.of(
                        "planNodeId", "cycle-b",
                        "dependsOn", "cycle-a"
                ))
        );

        AgentRunToolPlanDagView view = fixture.dagService.inspectRunToolPlanDag(SESSION_ID, RUN_ID);

        assertEquals(AgentToolPlanDagDependencyMode.EXPLICIT.name(), view.dependencyMode());
        assertTrue(view.hasCycle());
        assertEquals(List.of("cycle-a", "cycle-b"), view.cycleNodeIds());
        assertEquals(List.of(), view.topologicalNodeIds());
        assertEquals(List.of(), view.readyNodeIds());
        assertTrue(node(view, "cycle-a").blockedByNodeIds().contains("CYCLE:cycle-a"));
        assertTrue(node(view, "cycle-b").blockedByNodeIds().contains("CYCLE:cycle-b"));
    }

    @Test
    void failedDependencyCanBeTreatedAsSatisfiedWhenFailurePolicyAllowsContinue() {
        TestFixture fixture = newFixture(AgentRunState.TOOL_CALLING);
        fixture.saveAudits(
                failedAudit("audit-optional-check", 1, true, Map.of(
                        "planNodeId", "optional-check",
                        "failurePolicy", "CONTINUE_ON_FAILURE",
                        "resultAlias", "optionalCheckResult"
                )),
                plannedSyncAudit("audit-final-summary", 2, Map.of(
                        "planNodeId", "final-summary",
                        "dependsOn", "optional-check"
                ))
        );

        AgentRunToolPlanDagView view = fixture.dagService.inspectRunToolPlanDag(SESSION_ID, RUN_ID);
        AgentToolPlanDagNodeView finalNode = node(view, "final-summary");

        assertEquals(AgentToolPlanDagDependencyMode.EXPLICIT.name(), view.dependencyMode());
        assertFalse(view.hasCycle());
        assertEquals(List.of("optional-check", "final-summary"), view.topologicalNodeIds());
        assertEquals(List.of("final-summary"), view.readyNodeIds());
        assertTrue(finalNode.dependencySatisfied());
        assertTrue(finalNode.readyForExecution());
        assertEquals(List.of(), finalNode.blockedByNodeIds());
    }

    /**
     * 通过节点 ID 从 DAG 视图中取节点。
     *
     * <p>测试里不直接按列表下标读取，是因为显式 DAG 模式允许未来按拓扑顺序、创建时间或展示顺序调整节点列表。
     * 使用 nodeId 定位能让测试更聚焦业务语义：某个具体计划节点是否 ready、是否被某些前置节点阻断。</p>
     */
    private AgentToolPlanDagNodeView node(AgentRunToolPlanDagView view, String nodeId) {
        return view.nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .findFirst()
                .orElseThrow();
    }

    private TestFixture newFixture(AgentRunState runState) {
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentRunToolExecutionPolicyService policyService = new AgentRunToolExecutionPolicyService(
                new AgentRuntimeProperties(),
                sessionStore,
                auditService
        );
        AgentRunToolPlanDagService dagService = new AgentRunToolPlanDagService(policyService, auditService);
        AgentSessionRecord session = new AgentSessionRecord(
                SESSION_ID,
                10L,
                20L,
                30L,
                "actor-dag",
                "PYTHON_AI_RUNTIME",
                "ToolPlan DAG 预检测试会话",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                BASE_TIME
        );
        session.addRun(new AgentRunRecord(
                RUN_ID,
                SESSION_ID,
                runState,
                "AGENT_REASONING",
                "测试 ToolPlan DAG 预检",
                true,
                false,
                List.of(),
                Map.of(),
                BASE_TIME,
                "Run 已创建"
        ));
        sessionStore.save(session);
        return new TestFixture(dagService, auditStore);
    }

    private AgentToolExecutionAuditRecord plannedSyncAudit(String auditId,
                                                           int sequence,
                                                           Map<String, Object> governanceHints) {
        return audit(
                auditId,
                sequence,
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                governanceHints
        );
    }

    private AgentToolExecutionAuditRecord succeededAudit(String auditId,
                                                         int sequence,
                                                         Map<String, Object> governanceHints) {
        return audit(
                auditId,
                sequence,
                AgentToolExecutionState.SUCCEEDED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                governanceHints
        );
    }

    private AgentToolExecutionAuditRecord failedAudit(String auditId,
                                                      int sequence,
                                                      boolean idempotent,
                                                      Map<String, Object> governanceHints) {
        return audit(
                auditId,
                sequence,
                AgentToolExecutionState.FAILED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.MEDIUM,
                false,
                true,
                idempotent,
                governanceHints
        );
    }

    private AgentToolExecutionAuditRecord audit(String auditId,
                                                int sequence,
                                                AgentToolExecutionState state,
                                                AgentToolExecutionMode mode,
                                                AgentToolRiskLevel riskLevel,
                                                boolean requiresApproval,
                                                boolean readOnly,
                                                boolean idempotent,
                                                Map<String, Object> governanceHints) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                SESSION_ID,
                RUN_ID,
                "plan:" + RUN_ID + ":" + sequence,
                "datasource.metadata.read",
                "INTERNAL_API",
                "datasource-management",
                "/metadata",
                1000L + sequence,
                10L,
                20L,
                30L,
                "actor-dag",
                riskLevel.name(),
                mode.name(),
                requiresApproval,
                readOnly,
                idempotent,
                List.of("READ"),
                "测试 ToolPlan DAG 节点 " + sequence,
                Map.of("datasourceId", 1000L + sequence),
                governanceHints,
                Map.of(),
                state,
                "trace-dag",
                "工具计划已生成。",
                BASE_TIME.plusSeconds(sequence)
        );
    }

    private record TestFixture(AgentRunToolPlanDagService dagService,
                               AgentToolExecutionAuditMemoryStore auditStore) {

        /**
         * 保存测试审计快照。
         *
         * <p>DAG 服务只读审计事实，不应该通过测试夹具触发任何审批或执行动作。
         * 这里直接写内存仓储，模拟 AgentPlan ingestion 已经把工具计划落成审计记录后的状态。</p>
         */
        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }
    }
}
