/**
 * @Author : Cui
 * @Date: 2026/05/31 14:28
 * @Description DataSmart Govern Backend - AgentRunAsyncTaskCommandPlanningServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandPlanItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandPlanView;
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
 * Run 级异步工具命令草案规划测试。
 *
 * <p>测试重点不是 Kafka producer，而是投递前安全契约：
 * ASYNC_TASK 能否识别、commandId 是否稳定、参数值是否不会暴露、非幂等工具是否保守阻断、
 * 等待审批的异步工具是否不能绕过审批、同步工具是否不会混进异步命令列表。</p>
 */
class AgentRunAsyncTaskCommandPlanningServiceTest {

    @Test
    void asyncIdempotentToolShouldProduceDispatchableCommandPlan() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-async-001",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.ASYNC_TASK,
                AgentToolRiskLevel.MEDIUM,
                false,
                true,
                Map.of("datasourceId", 1001L, "credentialRef", "secret://mysql-prod"),
                Map.of("sensitiveFields", List.of("credentialRef"))
        ));

        AgentRunAsyncTaskCommandPlanView plan = fixture.service.planRunAsyncTaskCommands("session-async-001", "run-async-001");
        AgentAsyncTaskCommandPlanItemView item = plan.items().getFirst();

        assertEquals(1, plan.totalAsyncTools());
        assertEquals(1, plan.dispatchableCount());
        assertTrue(item.dispatchable());
        assertEquals("AGENT_TOOL_ASYNC_TASK_REQUESTED", item.commandType());
        assertEquals("KAFKA_COMMAND", item.dispatchChannel());
        assertEquals("datasmart.agent.tool.async.commands", item.commandTopic());
        assertEquals("task-management", item.consumerService());
        assertEquals(List.of("credentialRef", "datasourceId"), item.argumentNames());
        assertEquals(List.of("credentialRef"), item.sensitiveArgumentNames());
        assertFalse(item.toString().contains("secret://mysql-prod"));
    }

    @Test
    void sameAuditShouldGenerateStableCommandIdAndIdempotencyKey() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-async-stable",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.ASYNC_TASK,
                AgentToolRiskLevel.LOW,
                false,
                true,
                Map.of(),
                Map.of()
        ));

        AgentAsyncTaskCommandPlanItemView first = fixture.service
                .planRunAsyncTaskCommands("session-async-001", "run-async-001").items().getFirst();
        AgentAsyncTaskCommandPlanItemView second = fixture.service
                .planRunAsyncTaskCommands("session-async-001", "run-async-001").items().getFirst();

        assertEquals(first.commandId(), second.commandId());
        assertEquals(first.idempotencyKey(), second.idempotencyKey());
        assertTrue(first.commandId().startsWith("aatc_"));
        assertEquals("agent-tool-async:session-async-001:run-async-001:atea-async-stable", first.idempotencyKey());
    }

    @Test
    void nonIdempotentAsyncToolShouldBeBlockedInStrictMode() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-async-non-idempotent",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.ASYNC_TASK,
                AgentToolRiskLevel.MEDIUM,
                false,
                false,
                Map.of(),
                Map.of()
        ));

        AgentRunAsyncTaskCommandPlanView plan = fixture.service.planRunAsyncTaskCommands("session-async-001", "run-async-001");

        assertEquals(0, plan.dispatchableCount());
        assertEquals(1, plan.blockedCount());
        assertFalse(plan.items().getFirst().dispatchable());
        assertTrue(plan.items().getFirst().reasons().stream().anyMatch(reason -> reason.contains("未声明幂等")));
    }

    @Test
    void waitingApprovalAsyncToolShouldNotBypassHumanDecision() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-async-approval",
                AgentToolExecutionState.WAITING_APPROVAL,
                AgentToolExecutionMode.ASYNC_TASK,
                AgentToolRiskLevel.HIGH,
                true,
                true,
                Map.of(),
                Map.of()
        ));

        AgentRunAsyncTaskCommandPlanView plan = fixture.service.planRunAsyncTaskCommands("session-async-001", "run-async-001");

        assertFalse(plan.items().getFirst().dispatchable());
        assertEquals(AgentRunToolExecutionDecision.WAITING_APPROVAL.name(), plan.items().getFirst().policyDecision());
    }

    @Test
    void syncToolShouldBeIgnoredByAsyncCommandPlanning() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-sync-ignore",
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                Map.of(),
                Map.of()
        ));

        AgentRunAsyncTaskCommandPlanView plan = fixture.service.planRunAsyncTaskCommands("session-async-001", "run-async-001");

        assertEquals(0, plan.totalAsyncTools());
        assertEquals(1, plan.ignoredNonAsyncToolCount());
        assertTrue(plan.items().isEmpty());
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
        AgentRunAsyncTaskCommandPlanningService service = new AgentRunAsyncTaskCommandPlanningService(
                properties,
                policyService,
                auditService
        );
        AgentSessionRecord session = new AgentSessionRecord(
                "session-async-001",
                10L,
                20L,
                30L,
                "actor-async",
                "PYTHON_AI_RUNTIME",
                "规划异步工具命令草案",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        session.addRun(new AgentRunRecord(
                "run-async-001",
                "session-async-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "规划异步工具命令草案",
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

    private AgentToolExecutionAuditRecord audit(String auditId,
                                                AgentToolExecutionState state,
                                                AgentToolExecutionMode mode,
                                                AgentToolRiskLevel riskLevel,
                                                boolean requiresApproval,
                                                boolean idempotent,
                                                Map<String, Object> arguments,
                                                Map<String, Object> governanceHints) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-async-001",
                "run-async-001",
                "binding-" + auditId,
                "data-sync.execute",
                "INTERNAL_API",
                "data-sync",
                "/sync-tasks",
                1001L,
                10L,
                20L,
                30L,
                "actor-async",
                riskLevel.name(),
                mode.name(),
                requiresApproval,
                true,
                idempotent,
                List.of("CREATE_TASK"),
                "异步工具命令草案测试",
                arguments,
                governanceHints,
                Map.of(),
                state,
                "trace-async",
                "工具计划已生成。",
                LocalDateTime.now()
        );
    }

    private record TestFixture(AgentRunAsyncTaskCommandPlanningService service,
                               AgentToolExecutionAuditMemoryStore auditStore) {

        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }
    }
}
