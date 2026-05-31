/**
 * @Author : Cui
 * @Date: 2026/05/31 23:45
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionPreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionPreviewItemView;
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
 * DAG-aware 执行预览服务测试。
 *
 * <p>这些测试保护“只读 preview”边界：服务会合并 DAG、policy 和 async command plan，
 * 但不会调用真实工具、不会创建 task-management 任务、不会修改审计状态。这样后续做真正 DAG worker
 * 前，可以先固定安全解释语义。</p>
 */
class AgentRunToolDagExecutionPreviewServiceTest {

    private static final String SESSION_ID = "session-preview-001";
    private static final String RUN_ID = "run-preview-001";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 5, 31, 23, 45);

    @Test
    void readyLowRiskSyncNodeShouldBecomeSyncAutoExecuteCandidate() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "audit-sync",
                1,
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                Map.of("planNodeId", "metadata-read")
        ));

        AgentRunToolDagExecutionPreviewView preview = fixture.previewService.previewRunDagExecution(SESSION_ID, RUN_ID);

        assertEquals(1, preview.syncAutoExecutableCount());
        assertTrue(preview.hasExecutableCandidates());
        AgentToolDagExecutionPreviewItemView item = item(preview, "metadata-read");
        assertEquals(AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE.name(), item.previewAction());
        assertTrue(item.readyForExecution());
        assertTrue(item.wouldTriggerSideEffect());
        assertEquals("LOW", item.riskLevel());
    }

    @Test
    void dagDependencyShouldBlockDownstreamNodeBeforePrerequisiteSucceeds() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(
                audit("audit-first", 1, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true, Map.of("planNodeId", "first")),
                audit("audit-second", 2, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true, Map.of("planNodeId", "second", "dependsOn", List.of("first")))
        );

        AgentRunToolDagExecutionPreviewView preview = fixture.previewService.previewRunDagExecution(SESSION_ID, RUN_ID);

        assertEquals(1, preview.syncAutoExecutableCount());
        assertEquals(1, preview.blockedCount());
        AgentToolDagExecutionPreviewItemView second = item(preview, "second");
        assertEquals(AgentToolDagExecutionPreviewAction.WAIT_DEPENDENCIES.name(), second.previewAction());
        assertFalse(second.readyForExecution());
        assertEquals(List.of("first"), second.blockedByNodeIds());
    }

    @Test
    void readyAsyncTaskNodeShouldBecomeAsyncCommandDispatchCandidate() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "audit-async",
                1,
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.ASYNC_TASK,
                AgentToolRiskLevel.MEDIUM,
                false,
                false,
                true,
                Map.of("planNodeId", "data-sync-execute", "sensitiveFields", List.of("syncConfig"))
        ));

        AgentRunToolDagExecutionPreviewView preview = fixture.previewService.previewRunDagExecution(SESSION_ID, RUN_ID);

        assertEquals(1, preview.asyncDispatchableCount());
        AgentToolDagExecutionPreviewItemView item = item(preview, "data-sync-execute");
        assertEquals(AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE.name(), item.previewAction());
        assertTrue(item.asyncDispatchable());
        assertTrue(item.asyncCommandId().startsWith("aatc_"));
    }

    @Test
    void approvalNodeShouldWaitHumanActionInsteadOfBecomingCandidate() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "audit-approval",
                1,
                AgentToolExecutionState.WAITING_APPROVAL,
                AgentToolExecutionMode.APPROVAL_REQUIRED,
                AgentToolRiskLevel.HIGH,
                true,
                false,
                false,
                Map.of("planNodeId", "task-create")
        ));

        AgentRunToolDagExecutionPreviewView preview = fixture.previewService.previewRunDagExecution(SESSION_ID, RUN_ID);

        assertEquals(1, preview.humanActionCount());
        assertFalse(preview.hasExecutableCandidates());
        assertEquals(AgentToolDagExecutionPreviewAction.WAIT_HUMAN_ACTION.name(), item(preview, "task-create").previewAction());
    }

    private AgentToolDagExecutionPreviewItemView item(AgentRunToolDagExecutionPreviewView preview, String nodeId) {
        return preview.items().stream()
                .filter(item -> nodeId.equals(item.nodeId()))
                .findFirst()
                .orElseThrow();
    }

    private TestFixture newFixture() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentRunToolExecutionPolicyService policyService = new AgentRunToolExecutionPolicyService(
                properties,
                sessionStore,
                auditService
        );
        AgentRunToolPlanDagService dagService = new AgentRunToolPlanDagService(policyService, auditService);
        AgentRunAsyncTaskCommandPlanningService asyncPlanningService = new AgentRunAsyncTaskCommandPlanningService(
                properties,
                policyService,
                auditService
        );
        AgentRunToolDagExecutionPreviewService previewService = new AgentRunToolDagExecutionPreviewService(
                properties,
                dagService,
                policyService,
                asyncPlanningService
        );
        AgentSessionRecord session = new AgentSessionRecord(
                SESSION_ID,
                10L,
                20L,
                30L,
                "actor-preview",
                "PYTHON_AI_RUNTIME",
                "DAG 执行预览测试会话",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                BASE_TIME
        );
        session.addRun(new AgentRunRecord(
                RUN_ID,
                SESSION_ID,
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "测试 DAG-aware execution preview",
                true,
                false,
                List.of(),
                Map.of(),
                BASE_TIME,
                "Run 已创建"
        ));
        sessionStore.save(session);
        return new TestFixture(previewService, auditStore);
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
                mode == AgentToolExecutionMode.ASYNC_TASK ? "data-sync.execute" : "datasource.metadata.read",
                "INTERNAL_API",
                mode == AgentToolExecutionMode.ASYNC_TASK ? "data-sync" : "datasource-management",
                mode == AgentToolExecutionMode.ASYNC_TASK ? "/internal/data-sync/agent/tasks/execute" : "/metadata",
                1000L + sequence,
                10L,
                20L,
                30L,
                "actor-preview",
                riskLevel.name(),
                mode.name(),
                requiresApproval,
                readOnly,
                idempotent,
                List.of("READ"),
                "DAG execution preview 测试工具计划 " + sequence,
                Map.of("datasourceId", 1000L + sequence, "syncConfig", Map.of("mode", "FULL")),
                governanceHints,
                Map.of(),
                state,
                "trace-preview",
                "工具计划已生成。",
                BASE_TIME.plusSeconds(sequence)
        );
    }

    private record TestFixture(AgentRunToolDagExecutionPreviewService previewService,
                               AgentToolExecutionAuditMemoryStore auditStore) {

        /**
         * 保存测试审计记录。
         *
         * <p>preview 服务应该只读这些记录，因此测试通过内存仓储直接写入快照，不调用执行或审批方法。</p>
         */
        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }
    }
}
