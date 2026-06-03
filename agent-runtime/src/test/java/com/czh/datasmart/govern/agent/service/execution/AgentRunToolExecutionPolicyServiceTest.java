/**
 * @Author : Cui
 * @Date: 2026/05/29 18:56
 * @Description DataSmart Govern Backend - AgentRunToolExecutionPolicyServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.sandbox.AgentToolSandboxPolicyService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Run 工具执行策略预检测试。
 *
 * <p>该测试只验证“策略解释”本身，不调用真实工具，也不推进审计状态。
 * 这样可以把未来自动执行器的安全前置规则先固定下来：哪些工具可自动执行、哪些必须等审批、
 * 哪些缺参数必须阻断、哪些异步工具不能在同步线程里直接跑、失败后是否能重试。</p>
 */
class AgentRunToolExecutionPolicyServiceTest {

    @Test
    void syncPlannedToolShouldBeAutoExecutableCandidate() {
        TestFixture fixture = newFixture(AgentRunState.PLANNING);
        fixture.saveAudits(audit(
                "atea-policy-sync",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                Map.of()
        ));

        AgentRunToolExecutionPolicyView policy = fixture.service.inspectRunPolicy("session-policy-001", "run-policy-001");

        assertEquals(1, policy.autoExecutableCount());
        assertFalse(policy.blocksRun());
        assertEquals(AgentRunToolExecutionDecision.AUTO_EXECUTABLE.name(), policy.items().getFirst().decision());
        assertTrue(policy.items().getFirst().autoExecutable());
    }

    @Test
    void waitingApprovalToolShouldRequireHumanAndBlockRun() {
        TestFixture fixture = newFixture(AgentRunState.WAITING_HUMAN);
        fixture.saveAudits(audit(
                "atea-policy-approval",
                AgentToolExecutionState.WAITING_APPROVAL,
                AgentToolExecutionMode.APPROVAL_REQUIRED,
                AgentToolRiskLevel.HIGH,
                true,
                false,
                false,
                Map.of()
        ));

        AgentRunToolExecutionPolicyView policy = fixture.service.inspectRunPolicy("session-policy-001", "run-policy-001");

        assertEquals(1, policy.humanActionCount());
        assertTrue(policy.blocksRun());
        assertEquals(AgentRunToolExecutionDecision.WAITING_APPROVAL.name(), policy.items().getFirst().decision());
    }

    @Test
    void missingParametersShouldBlockAutoExecutionEvenWhenToolIsSync() {
        TestFixture fixture = newFixture(AgentRunState.PLANNING);
        fixture.saveAudits(audit(
                "atea-policy-missing-param",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                Map.of("missingFields", List.of("datasourceId"))
        ));

        AgentRunToolExecutionPolicyView policy = fixture.service.inspectRunPolicy("session-policy-001", "run-policy-001");

        assertEquals(0, policy.autoExecutableCount());
        assertEquals(1, policy.blockingCount());
        assertEquals(AgentRunToolExecutionDecision.WAITING_PARAMETER_COMPLETION.name(), policy.items().getFirst().decision());
    }

    @Test
    void asyncToolShouldWaitForAsyncExecutorInsteadOfSyncAutoExecution() {
        TestFixture fixture = newFixture(AgentRunState.PLANNING);
        fixture.saveAudits(audit(
                "atea-policy-async",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.ASYNC_TASK,
                AgentToolRiskLevel.MEDIUM,
                false,
                true,
                true,
                Map.of()
        ));

        AgentRunToolExecutionPolicyView policy = fixture.service.inspectRunPolicy("session-policy-001", "run-policy-001");

        assertEquals(0, policy.autoExecutableCount());
        assertFalse(policy.blocksRun());
        assertEquals(AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR.name(), policy.items().getFirst().decision());
    }

    @Test
    void nonIdempotentFailedToolShouldBlockRunForManualReview() {
        TestFixture fixture = newFixture(AgentRunState.TOOL_CALLING);
        fixture.saveAudits(audit(
                "atea-policy-failed",
                AgentToolExecutionState.FAILED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.MEDIUM,
                false,
                false,
                false,
                Map.of()
        ));

        AgentRunToolExecutionPolicyView policy = fixture.service.inspectRunPolicy("session-policy-001", "run-policy-001");

        assertEquals(1, policy.blockingCount());
        assertEquals(AgentRunToolExecutionDecision.FAILED_BLOCKS_RUN.name(), policy.items().getFirst().decision());
    }

    @Test
    void terminalRunShouldBlockEvenIfToolLooksExecutable() {
        TestFixture fixture = newFixture(AgentRunState.CANCELLED);
        fixture.saveAudits(audit(
                "atea-policy-terminal",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                Map.of()
        ));

        AgentRunToolExecutionPolicyView policy = fixture.service.inspectRunPolicy("session-policy-001", "run-policy-001");

        assertEquals(0, policy.autoExecutableCount());
        assertTrue(policy.runTerminal());
        assertEquals(AgentRunToolExecutionDecision.RUN_TERMINAL_BLOCKED.name(), policy.items().getFirst().decision());
    }

    @Test
    void sandboxRejectedToolShouldBecomePolicyBlockedWithIssueCodes() {
        TestFixture fixture = newSandboxFixture(AgentRunState.PLANNING);
        fixture.saveAudits(audit(
                "atea-policy-sandbox-oversize",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                Map.of()
        ));

        AgentRunToolExecutionPolicyView policy = fixture.service.inspectRunPolicy("session-policy-001", "run-policy-001");

        assertEquals(0, policy.autoExecutableCount());
        assertEquals(1, policy.blockingCount());
        assertEquals(AgentRunToolExecutionDecision.BLOCKED_BY_POLICY.name(), policy.items().getFirst().decision());
        assertFalse(policy.items().getFirst().sandboxAllowed());
        assertTrue(policy.items().getFirst().sandboxIssueCodes().contains("ARGUMENT_BYTES_EXCEED_LIMIT"));
    }

    /**
     * 构造测试上下文。
     *
     * <p>这里显式创建内存会话仓储、审计仓储和策略服务，是为了让测试边界足够清楚：
     * 策略服务只依赖当前 Run 和审计快照，不需要 Spring 容器、HTTP Controller 或真实数据库。
     */
    private TestFixture newFixture(AgentRunState runState) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        return newFixture(runState, properties, false);
    }

    private TestFixture newSandboxFixture(AgentRunState runState) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolSandbox().setMaxArgumentBytes(16);
        properties.getToolServiceBaseUrls().put("datasource-management", "http://localhost:8082");
        properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        return newFixture(runState, properties, true);
    }

    /**
     * 构造测试上下文。
     *
     * <p>enableSandbox=false 使用旧兼容构造函数，保护历史 execution-policy 状态机测试；
     * enableSandbox=true 使用显式沙箱服务，验证新接入的执行前安全 verdict 会参与 Run 级策略判断。</p>
     */
    private TestFixture newFixture(AgentRunState runState,
                                   AgentRuntimeProperties properties,
                                   boolean enableSandbox) {
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentRunToolExecutionPolicyService service = enableSandbox
                ? new AgentRunToolExecutionPolicyService(
                        properties,
                        sessionStore,
                        auditService,
                        new AgentToolSandboxPolicyService(properties)
                )
                : new AgentRunToolExecutionPolicyService(
                        properties,
                        sessionStore,
                        auditService
                );
        AgentSessionRecord session = new AgentSessionRecord(
                "session-policy-001",
                10L,
                20L,
                30L,
                "actor-policy",
                "PYTHON_AI_RUNTIME",
                "工具执行策略预检测试",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        session.addRun(new AgentRunRecord(
                "run-policy-001",
                "session-policy-001",
                runState,
                "AGENT_REASONING",
                "测试工具执行策略",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "Run 已创建"
        ));
        sessionStore.save(session);
        return new TestFixture(service, auditStore);
    }

    private AgentRuntimeProperties.ToolDefinitionProperties datasourceMetadataTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setEnabled(true);
        tool.setToolCode("datasource.metadata.read");
        tool.setToolType(AgentToolType.DATASOURCE_METADATA);
        tool.setTargetService("datasource-management");
        tool.setTargetEndpoint("/metadata");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.LOW);
        tool.setExecutionMode(AgentToolExecutionMode.SYNC);
        tool.setRequiresApproval(false);
        tool.setIdempotent(true);
        tool.setTimeoutMs(10000L);
        tool.setMaxRetries(0);
        tool.setAllowedActions(List.of("READ"));
        return tool;
    }

    private AgentToolExecutionAuditRecord audit(String auditId,
                                                AgentToolExecutionState state,
                                                AgentToolExecutionMode mode,
                                                AgentToolRiskLevel riskLevel,
                                                boolean requiresApproval,
                                                boolean readOnly,
                                                boolean idempotent,
                                                Map<String, Object> parameterValidation) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-policy-001",
                "run-policy-001",
                "binding-" + auditId,
                "datasource.metadata.read",
                "INTERNAL_API",
                "datasource-management",
                "/metadata",
                1001L,
                10L,
                20L,
                30L,
                "actor-policy",
                riskLevel.name(),
                mode.name(),
                requiresApproval,
                readOnly,
                idempotent,
                List.of("READ"),
                "策略预检测试工具计划",
                Map.of("datasourceId", 1001L),
                Map.of("tenantScoped", true, "projectScoped", true),
                parameterValidation,
                state,
                "trace-policy",
                "工具计划已生成。",
                LocalDateTime.now()
        );
    }

    private record TestFixture(AgentRunToolExecutionPolicyService service,
                               AgentToolExecutionAuditMemoryStore auditStore) {

        /**
         * 保存审计记录。
         *
         * <p>测试通过仓储直接写入审计快照，模拟 AgentPlan ingestion 后已经产生工具审计的状态。
         * 策略服务读取这些事实后只做判断，不应该修改这些记录。</p>
         */
        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }
    }
}
