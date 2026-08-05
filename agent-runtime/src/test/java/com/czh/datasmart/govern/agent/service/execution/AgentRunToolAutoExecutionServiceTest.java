/**
 * @Author : Cui
 * @Date: 2026/05/29 22:15
 * @Description DataSmart Govern Backend - AgentRunToolAutoExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolAutoExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolAutoExecutionResponse;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolBindingStatus;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.agent.service.tool.AgentToolAdapter;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionContext;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionGuard;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutcome;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutputStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 级同步工具自动执行服务测试。
 *
 * <p>该测试验证“自动执行入口是否足够保守”。它不是测试某个具体 datasource 或 task 工具的下游 HTTP 逻辑，
 * 而是固定批次执行器的安全边界：只有 LOW + 只读 + 幂等 + 同步 + policy 候选的工具会被执行；
 * dryRun 不应修改审计状态；批次上限应阻止一次请求执行过多工具。</p>
 */
class AgentRunToolAutoExecutionServiceTest {

    @Test
    void shouldExecuteOnlyLowRiskReadOnlyIdempotentSyncCandidate() {
        TestFixture fixture = newFixture(5);
        fixture.saveAudits(
                audit("atea-auto-low", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "low-root")),
                audit("atea-auto-medium", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.MEDIUM, false, true, true,
                        Map.of("planNodeId", "medium-child", "dependsOn", List.of("low-root"))),
                audit("atea-auto-approval", "datasource.metadata.read",
                        AgentToolExecutionState.WAITING_APPROVAL, AgentToolExecutionMode.APPROVAL_REQUIRED,
                        AgentToolRiskLevel.HIGH, true, false, false,
                        Map.of("planNodeId", "approval-child", "dependsOn", List.of("medium-child")))
        );

        AgentRunToolAutoExecutionResponse response = fixture.service.executeEligibleSyncTools(
                "session-auto-001",
                "run-auto-001",
                new AgentRunToolAutoExecutionRequest(null, 5, false),
                "trace-auto"
        );

        assertEquals(1, response.executedCount());
        assertEquals(0, response.failedCount());
        assertEquals(2, response.skippedCount());
        assertEquals("SUCCEEDED", fixture.auditService.getExecutionAudit("session-auto-001", "run-auto-001", "atea-auto-low").state());
        assertEquals("PLANNED", fixture.auditService.getExecutionAudit("session-auto-001", "run-auto-001", "atea-auto-medium").state());
        assertTrue(response.items().stream().anyMatch(item -> "atea-auto-medium".equals(item.auditId())
                && item.reason().contains("LOW 风险")));
    }

    @Test
    void dryRunShouldReportCandidateWithoutChangingAuditState() {
        TestFixture fixture = newFixture(5);
        fixture.saveAudits(audit("atea-auto-dry-run", AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW, false, true, true));

        AgentRunToolAutoExecutionResponse response = fixture.service.executeEligibleSyncTools(
                "session-auto-001",
                "run-auto-001",
                new AgentRunToolAutoExecutionRequest(null, 5, true),
                "trace-auto"
        );

        assertEquals(0, response.executedCount());
        assertEquals(1, response.skippedCount());
        assertEquals("DRY_RUN_CANDIDATE", response.items().getFirst().action());
        assertEquals("PLANNED", fixture.auditService.getExecutionAudit("session-auto-001", "run-auto-001", "atea-auto-dry-run").state());
    }

    @Test
    void shouldRespectServerSideBatchLimit() {
        TestFixture fixture = newFixture(1);
        fixture.saveAudits(
                audit("atea-auto-limit-1", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "limit-root")),
                audit("atea-auto-limit-2", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "limit-child", "dependsOn", List.of("limit-root")))
        );

        AgentRunToolAutoExecutionResponse response = fixture.service.executeEligibleSyncTools(
                "session-auto-001",
                "run-auto-001",
                new AgentRunToolAutoExecutionRequest(null, 5, false),
                "trace-auto"
        );

        assertEquals(1, response.effectiveLimit());
        assertEquals(1, response.executedCount());
        assertEquals(1, response.skippedCount());
        assertTrue(response.items().stream().anyMatch(item -> "BATCH_LIMIT_REACHED".equals(item.action())));
    }

    @Test
    void auditIdWhitelistShouldOnlyExecuteSelectedCandidate() {
        TestFixture fixture = newFixture(5);
        fixture.saveAudits(
                audit("atea-auto-selected", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "selected-root")),
                audit("atea-auto-not-selected", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "not-selected-child", "dependsOn", List.of("selected-root")))
        );

        AgentRunToolAutoExecutionResponse response = fixture.service.executeEligibleSyncTools(
                "session-auto-001",
                "run-auto-001",
                new AgentRunToolAutoExecutionRequest(List.of("atea-auto-selected"), 5, false),
                "trace-auto"
        );

        assertEquals(1, response.executedCount());
        assertEquals(1, response.skippedCount());
        assertEquals("SUCCEEDED", fixture.auditService.getExecutionAudit("session-auto-001", "run-auto-001", "atea-auto-selected").state());
        assertEquals("PLANNED", fixture.auditService.getExecutionAudit("session-auto-001", "run-auto-001", "atea-auto-not-selected").state());
    }

    @Test
    void shouldKeepReadOnlyPrecheckPlannedWhileDraftDependencyAwaitsApproval() {
        TestFixture fixture = newFixture(5);
        fixture.saveAudits(
                audit(
                        "atea-draft-awaiting-approval",
                        "sync.task.draft.save",
                        AgentToolExecutionState.WAITING_APPROVAL,
                        AgentToolExecutionMode.APPROVAL_REQUIRED,
                        AgentToolRiskLevel.HIGH,
                        true,
                        false,
                        false,
                        Map.of("planNodeId", "sync-task-draft-save")
                ),
                audit(
                        "atea-precheck-dependent",
                        "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED,
                        AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW,
                        false,
                        true,
                        true,
                        Map.of(
                                "planNodeId", "sync-task-precheck",
                                "dependsOn", List.of("sync-task-draft-save")
                        )
                )
        );

        AgentRunToolAutoExecutionResponse response = fixture.service.executeEligibleSyncTools(
                "session-auto-001",
                "run-auto-001",
                new AgentRunToolAutoExecutionRequest(null, 5, false),
                "trace-auto"
        );

        assertEquals(0, response.executedCount());
        assertEquals(0, response.failedCount());
        assertTrue(response.items().stream().anyMatch(item ->
                "atea-precheck-dependent".equals(item.auditId())
                        && "DEPENDENCY_BLOCKED".equals(item.action())
                        && item.reason().contains("sync-task-draft-save")));
        assertEquals("PLANNED", fixture.auditService.getExecutionAudit(
                "session-auto-001", "run-auto-001", "atea-precheck-dependent").state());
    }

    @Test
    void shouldCompleteRunWhenEveryReadOnlyToolSucceeded() {
        TestFixture fixture = newFixture(5);
        fixture.saveAudits(
                audit("atea-auto-complete-1", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "complete-root")),
                audit("atea-auto-complete-2", "datasource.metadata.read",
                        AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "complete-child", "dependsOn", List.of("complete-root")))
        );

        AgentRunToolAutoExecutionResponse response = fixture.service.executeEligibleSyncTools(
                "session-auto-001",
                "run-auto-001",
                new AgentRunToolAutoExecutionRequest(null, 5, false),
                "trace-auto"
        );

        assertEquals(2, response.executedCount());
        assertEquals(AgentRunState.SUCCEEDED, fixture.run.getState());
        assertTrue(fixture.run.getMessage().contains("同一会话继续下一轮"));
    }

    /**
     * Verifies that an attempted read-only tool failure closes the durable Run.
     *
     * <p>Without this transition the audit is FAILED while the parent Run remains
     * PLANNING, so the same-session active-run guard rejects every later user repair.
     * The assertion therefore covers both the terminal state and the human-readable
     * failure summary that the history page uses to offer Agent diagnosis.</p>
     */
    @Test
    void shouldFailRunWhenARealAutoExecutedToolFails() {
        TestFixture fixture = newFixture(5);
        fixture.saveAudits(audit(
                "atea-auto-failed",
                "datasource.unsupported.read",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                Map.of("planNodeId", "failed-root")
        ));

        AgentRunToolAutoExecutionResponse response = fixture.service.executeEligibleSyncTools(
                "session-auto-001",
                "run-auto-001",
                new AgentRunToolAutoExecutionRequest(null, 5, false),
                "trace-auto"
        );

        assertEquals(1, response.failedCount());
        assertEquals(AgentRunState.FAILED, fixture.run.getState());
        assertTrue(fixture.run.getMessage().contains("datasource.unsupported.read"));
        assertTrue(fixture.run.getFinishTime() != null);
    }

    private TestFixture newFixture(int maxAutoExecutions) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxSyncAutoExecutionsPerRun(maxAutoExecutions);
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
        AgentRunToolPlanDagService toolPlanDagService = new AgentRunToolPlanDagService(
                policyService,
                auditService
        );
        AgentToolExecutionService executionService = new AgentToolExecutionService(
                auditService,
                List.of(new TestMetadataToolAdapter()),
                new AgentToolExecutionGuard(),
                new AgentToolExecutionOutputStore()
        );
        AgentRunToolAutoExecutionService autoExecutionService = new AgentRunToolAutoExecutionService(
                properties,
                sessionStore,
                policyService,
                toolPlanDagService,
                executionService
        );
        AgentSessionRecord session = sessionWithRun();
        sessionStore.save(session);
        return new TestFixture(
                autoExecutionService,
                auditStore,
                auditService,
                session.getRuns().getFirst()
        );
    }

    private AgentSessionRecord sessionWithRun() {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-auto-001",
                10L,
                20L,
                30L,
                "actor-auto",
                "PYTHON_AI_RUNTIME",
                "自动执行安全同步工具",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        session.addToolBinding(new AgentToolBindingRecord(
                "binding-datasource-metadata-read",
                "datasource.metadata.read",
                AgentToolType.DATASOURCE_METADATA,
                "datasource.metadata.read",
                "datasource-management",
                "/metadata",
                1001L,
                true,
                AgentToolRiskLevel.LOW.name(),
                AgentToolExecutionMode.SYNC.name(),
                false,
                true,
                AgentToolBindingStatus.ENABLED,
                List.of("READ"),
                LocalDateTime.now()
        ));
        session.addRun(new AgentRunRecord(
                "run-auto-001",
                "session-auto-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "自动执行安全同步工具",
                true,
                false,
                List.of(),
                Map.of("datasourceId", 1001L),
                LocalDateTime.now(),
                "Run 已创建"
        ));
        return session;
    }

    private AgentToolExecutionAuditRecord audit(String auditId,
                                                AgentToolExecutionState state,
                                                AgentToolExecutionMode mode,
                                                AgentToolRiskLevel riskLevel,
                                                boolean requiresApproval,
                                                boolean readOnly,
                                                boolean idempotent) {
        return audit(
                auditId,
                "datasource.metadata.read",
                state,
                mode,
                riskLevel,
                requiresApproval,
                readOnly,
                idempotent,
                Map.of()
        );
    }

    private AgentToolExecutionAuditRecord audit(String auditId,
                                                String toolCode,
                                                AgentToolExecutionState state,
                                                AgentToolExecutionMode mode,
                                                AgentToolRiskLevel riskLevel,
                                                boolean requiresApproval,
                                                boolean readOnly,
                                                boolean idempotent,
                                                Map<String, Object> governanceHints) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-auto-001",
                "run-auto-001",
                "binding-" + auditId,
                toolCode,
                "INTERNAL_API",
                "datasource-management",
                "/metadata",
                1001L,
                10L,
                20L,
                30L,
                "actor-auto",
                riskLevel.name(),
                mode.name(),
                requiresApproval,
                readOnly,
                idempotent,
                List.of("READ"),
                "自动执行测试计划",
                Map.of("datasourceId", 1001L),
                governanceHints(governanceHints),
                Map.of(),
                state,
                "trace-auto",
                "工具计划已生成。",
                LocalDateTime.now()
        );
    }

    private Map<String, Object> governanceHints(Map<String, Object> additionalHints) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("tenantScoped", true);
        hints.put("projectScoped", true);
        hints.putAll(additionalHints);
        return Map.copyOf(hints);
    }

    private record TestFixture(AgentRunToolAutoExecutionService service,
                               AgentToolExecutionAuditMemoryStore auditStore,
                               AgentToolExecutionAuditService auditService,
                               AgentRunRecord run) {

        /**
         * 保存审计记录，模拟 AgentPlan ingestion 已经生成可执行计划。
         */
        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }
    }

    private static class TestMetadataToolAdapter implements AgentToolAdapter {

        @Override
        public boolean supports(String toolCode) {
            return "datasource.metadata.read".equals(toolCode);
        }

        @Override
        public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
            return AgentToolExecutionOutcome.succeeded(
                    "测试元数据工具执行成功。",
                    Map.of("datasourceId", 1001L, "tableCount", 3)
            );
        }
    }
}
