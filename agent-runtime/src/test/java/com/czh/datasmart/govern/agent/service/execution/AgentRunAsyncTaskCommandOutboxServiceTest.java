/**
 * @Author : Cui
 * @Date: 2026/05/31 17:28
 * @Description DataSmart Govern Backend - AgentRunAsyncTaskCommandOutboxServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.InMemoryAgentAsyncTaskCommandOutboxStore;
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
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Run 异步命令 outbox 服务测试。
 *
 * <p>该测试保护“只读 command plan”到“可恢复 outbox 记录”的边界：
 * 只有真正可下发的 ASYNC_TASK 会进入 outbox；重复入箱按 commandId 幂等复用；
 * payload 只包含 payloadReference 和字段名，不包含原始工具参数值。</p>
 */
class AgentRunAsyncTaskCommandOutboxServiceTest {

    @Test
    void dispatchableAsyncCommandShouldBeEnqueuedWithSafePayload() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-outbox-001",
                AgentToolExecutionState.PLANNED,
                true,
                Map.of("datasourceId", 1001L, "credentialRef", "secret://mysql-prod")
        ));

        AgentRunAsyncTaskCommandOutboxEnqueueResponse response =
                fixture.service.enqueueRunAsyncTaskCommands("session-outbox-001", "run-outbox-001");

        assertEquals(1, response.enqueuedCount());
        assertEquals(0, response.duplicateCount());
        assertEquals(1, response.items().size());
        AgentAsyncTaskCommandOutboxRecord record = fixture.store.list("run-outbox-001", null, 10).getFirst();
        assertTrue(record.payloadJson().contains("\"schemaVersion\":\"datasmart.agent.async-task-command.v1\""));
        assertTrue(record.payloadJson().contains("\"payloadReference\":\"agent-tool-audit://session-outbox-001/run-outbox-001/atea-outbox-001/plan-arguments\""));
        assertTrue(record.payloadJson().contains("\"credentialRef\""));
        assertFalse(record.payloadJson().contains("secret://mysql-prod"));
    }

    @Test
    void repeatedEnqueueShouldReuseExistingOutboxRecord() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-outbox-duplicate",
                AgentToolExecutionState.PLANNED,
                true,
                Map.of()
        ));

        AgentRunAsyncTaskCommandOutboxEnqueueResponse first =
                fixture.service.enqueueRunAsyncTaskCommands("session-outbox-001", "run-outbox-001");
        AgentRunAsyncTaskCommandOutboxEnqueueResponse second =
                fixture.service.enqueueRunAsyncTaskCommands("session-outbox-001", "run-outbox-001");

        assertEquals(1, first.enqueuedCount());
        assertEquals(0, first.duplicateCount());
        assertEquals(0, second.enqueuedCount());
        assertEquals(1, second.duplicateCount());
        assertEquals(1, fixture.store.list("run-outbox-001", null, 10).size());
    }

    /**
     * selected-node 入箱应把 confirmation 与授权证据写入 command payload。
     *
     * <p>这条测试保护 4.74 的跨服务契约：task-management 不能只收到“创建任务”命令，
     * 还应看到该命令来自哪次 selected-node 确认、当时命中了哪些策略版本、服务账号代表谁执行。
     * 这些字段仍然是低敏摘要，不包含工具参数值。</p>
     */
    @Test
    void selectedNodeEnqueueShouldAttachExecutionEvidenceToPayload() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-outbox-evidence",
                AgentToolExecutionState.PLANNED,
                true,
                Map.of("datasourceId", 1001L)
        ));
        Map<String, AgentAsyncTaskCommandExecutionEvidence> evidence = Map.of(
                "atea-outbox-evidence",
                new AgentAsyncTaskCommandExecutionEvidence(
                        "dag-confirmation:test-001",
                        List.of("route-policy:860"),
                        List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-outbox"),
                        null
                )
        );

        fixture.service.enqueueSelectedRunAsyncTaskCommands(
                "session-outbox-001",
                "run-outbox-001",
                List.of("atea-outbox-evidence"),
                evidence
        );

        AgentAsyncTaskCommandOutboxRecord record = fixture.store.list("run-outbox-001", null, 10).getFirst();
        assertTrue(record.payloadJson().contains("\"confirmationId\":\"dag-confirmation:test-001\""));
        assertTrue(record.payloadJson().contains("\"policyVersions\":[\"route-policy:860\"]"));
        assertTrue(record.payloadJson().contains("datasmart-agent-runtime"));
        assertFalse(record.payloadJson().contains("\"datasourceId\":1001"));
    }

    @Test
    void blockedAsyncCommandShouldNotEnterOutbox() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(audit(
                "atea-outbox-blocked",
                AgentToolExecutionState.PLANNED,
                false,
                Map.of()
        ));

        AgentRunAsyncTaskCommandOutboxEnqueueResponse response =
                fixture.service.enqueueRunAsyncTaskCommands("session-outbox-001", "run-outbox-001");

        assertEquals(0, response.enqueuedCount());
        assertEquals(1, response.blockedCount());
        assertTrue(fixture.store.list("run-outbox-001", null, 10).isEmpty());
    }

    /**
     * 租户级 backlog 过高时，应在 append 前拒绝继续入箱。
     *
     * <p>这条测试保护 4.73 的容量治理语义：outbox 不是无限队列。
     * 如果同一租户已有大量 PENDING/PUBLISHING/FAILED command，再继续写入会把压力传给 dispatcher、Kafka、
     * task-management 和下游 worker。服务层应在形成新 command 事实前直接拒绝。</p>
     */
    @Test
    void enqueueShouldBeRejectedWhenTenantBacklogExceedsLimit() {
        TestFixture fixture = newFixture();
        fixture.properties.setMaxActiveCommandsPerTenant(1);
        fixture.store.append(existingRecord("existing-command-001", "other-run", AgentAsyncTaskCommandOutboxStatus.PENDING));
        fixture.saveAudits(audit(
                "atea-outbox-capacity",
                AgentToolExecutionState.PLANNED,
                true,
                Map.of()
        ));

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service.enqueueRunAsyncTaskCommands("session-outbox-001", "run-outbox-001"));
        assertEquals(0, fixture.store.list("run-outbox-001", null, 10).size());
    }

    private TestFixture newFixture() {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        AgentAsyncTaskCommandOutboxProperties outboxProperties = new AgentAsyncTaskCommandOutboxProperties();
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentRunToolExecutionPolicyService policyService = new AgentRunToolExecutionPolicyService(
                runtimeProperties,
                sessionStore,
                auditService
        );
        AgentRunAsyncTaskCommandPlanningService planningService = new AgentRunAsyncTaskCommandPlanningService(
                runtimeProperties,
                policyService,
                auditService
        );
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        AgentRunAsyncTaskCommandOutboxService service = new AgentRunAsyncTaskCommandOutboxService(
                outboxProperties,
                planningService,
                store,
                new AgentAsyncTaskCommandOutboxCapacityGuard(outboxProperties, store),
                new ObjectMapper()
        );
        AgentSessionRecord session = new AgentSessionRecord(
                "session-outbox-001",
                10L,
                20L,
                30L,
                "actor-outbox",
                "PYTHON_AI_RUNTIME",
                "异步命令 outbox 测试",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        session.addRun(new AgentRunRecord(
                "run-outbox-001",
                "session-outbox-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "异步命令 outbox 测试",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "Run 已创建"
        ));
        sessionStore.save(session);
        return new TestFixture(service, store, auditStore, outboxProperties);
    }

    private AgentAsyncTaskCommandOutboxRecord existingRecord(String commandId,
                                                             String runId,
                                                             AgentAsyncTaskCommandOutboxStatus status) {
        return new AgentAsyncTaskCommandOutboxRecord(
                "async-command-outbox:" + commandId,
                commandId,
                "idempotency:" + commandId,
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ASYNC_TASK_REQUESTED",
                runId,
                "datasmart.agent.async-task-commands",
                "task-management",
                "session-outbox-001",
                runId,
                "audit-" + commandId,
                "data-sync.execute",
                "data-sync",
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-outbox",
                "trace-outbox",
                "agent-tool-audit://session-outbox-001/" + runId + "/audit-" + commandId + "/plan-arguments",
                status,
                0,
                java.time.Instant.now(),
                java.time.Instant.now(),
                null,
                null,
                "",
                128,
                false,
                "{}"
        );
    }

    private AgentToolExecutionAuditRecord audit(String auditId,
                                                AgentToolExecutionState state,
                                                boolean idempotent,
                                                Map<String, Object> arguments) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-outbox-001",
                "run-outbox-001",
                "binding-" + auditId,
                "data-sync.execute",
                "INTERNAL_API",
                "data-sync",
                "/sync-tasks",
                1001L,
                10L,
                20L,
                30L,
                "actor-outbox",
                AgentToolRiskLevel.MEDIUM.name(),
                AgentToolExecutionMode.ASYNC_TASK.name(),
                false,
                true,
                idempotent,
                List.of("CREATE_TASK"),
                "异步命令 outbox 测试工具",
                arguments,
                Map.of("sensitiveFields", List.of("credentialRef")),
                Map.of(),
                state,
                "trace-outbox",
                "工具计划已生成。",
                LocalDateTime.now()
        );
    }

    private record TestFixture(AgentRunAsyncTaskCommandOutboxService service,
                               InMemoryAgentAsyncTaskCommandOutboxStore store,
                               AgentToolExecutionAuditMemoryStore auditStore,
                               AgentAsyncTaskCommandOutboxProperties properties) {

        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }
    }
}
