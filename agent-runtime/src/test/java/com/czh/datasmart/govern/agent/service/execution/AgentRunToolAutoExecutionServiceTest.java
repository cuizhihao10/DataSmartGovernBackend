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
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.AgentToolAdapter;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionContext;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionGuard;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutcome;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutputStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
                audit("atea-auto-low", AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true),
                audit("atea-auto-medium", AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.MEDIUM, false, true, true),
                audit("atea-auto-approval", AgentToolExecutionState.WAITING_APPROVAL, AgentToolExecutionMode.APPROVAL_REQUIRED,
                        AgentToolRiskLevel.HIGH, true, false, false)
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
                audit("atea-auto-limit-1", AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true),
                audit("atea-auto-limit-2", AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true)
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
                audit("atea-auto-selected", AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true),
                audit("atea-auto-not-selected", AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true)
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
                executionService
        );
        AgentSessionRecord session = sessionWithRun();
        sessionStore.save(session);
        return new TestFixture(autoExecutionService, auditStore, auditService);
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
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-auto-001",
                "run-auto-001",
                "binding-" + auditId,
                "datasource.metadata.read",
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
                Map.of("tenantScoped", true, "projectScoped", true),
                Map.of(),
                state,
                "trace-auto",
                "工具计划已生成。",
                LocalDateTime.now()
        );
    }

    private record TestFixture(AgentRunToolAutoExecutionService service,
                               AgentToolExecutionAuditMemoryStore auditStore,
                               AgentToolExecutionAuditService auditService) {

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
