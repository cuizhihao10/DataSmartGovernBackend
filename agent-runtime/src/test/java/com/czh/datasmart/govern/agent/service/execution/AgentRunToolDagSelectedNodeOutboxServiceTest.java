/**
 * @Author : Cui
 * @Date: 2026/06/01 10:38
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectedNodeOutboxServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRunToolDagConfirmationProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.InMemoryAgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.authorization.AgentToolServiceAuthorizationPreviewService;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.InMemoryAgentRunToolDagConfirmationStore;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentRuntimeEventProjectionStore;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAG 选中节点异步 outbox 确认入箱服务测试。
 *
 * <p>这些测试保护真实副作用入口的四条底线：
 * 1. 只有显式选择、已经 dry-run、指纹未变化的异步节点才能入箱；
 * 2. 重复确认必须复用稳定 commandId，不能重复创建 outbox；
 * 3. 过期指纹必须 fail-closed；
 * 4. 一个批次只要混入同步、等待依赖、未命中或超限节点，就整批拒绝，避免部分成功造成误解。</p>
 */
class AgentRunToolDagSelectedNodeOutboxServiceTest {

    private static final String SESSION_ID = "session-selected-outbox-001";
    private static final String RUN_ID = "run-selected-outbox-001";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 6, 1, 10, 38);

    @Test
    void confirmedAsyncNodeShouldEnterOutboxWithoutAcceptingExternalEndpoint() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(asyncAudit("audit-async", "data-sync-execute", Map.of()));
        AgentRunToolDagExecutionDryRunResponse dryRun = fixture.dryRun(List.of("data-sync-execute"));

        AgentRunToolDagSelectedNodeOutboxEnqueueResponse response = fixture.confirm(
                List.of("data-sync-execute"),
                dryRun.selectionFingerprint()
        );

        assertEquals(1, response.outbox().enqueuedCount());
        assertEquals(0, response.outbox().duplicateCount());
        assertEquals(dryRun.selectionFingerprint(), response.selectionFingerprint());
        assertTrue(response.confirmationId().startsWith("dag-confirmation:"));
        assertEquals(List.of("audit-async"), response.selectedAuditIds());
        AgentRunToolDagConfirmationRecord confirmation = fixture.confirmationStore
                .findByConfirmationId(response.confirmationId())
                .orElseThrow();
        assertEquals(dryRun.selectionFingerprint(), confirmation.selectionFingerprint());
        assertEquals(List.of("audit-async"), confirmation.selectedAuditIds());
        AgentAsyncTaskCommandOutboxRecord record = fixture.store.list(RUN_ID, null, 10).getFirst();
        assertEquals("audit-async", record.auditId());
        assertEquals("/internal/data-sync/agent/tasks/execute", record.targetEndpoint());
        assertTrue(record.payloadJson().contains("\"payloadReference\""));
    }

    @Test
    void repeatedConfirmationShouldReuseStableOutboxCommand() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(asyncAudit("audit-async", "data-sync-execute", Map.of()));
        AgentRunToolDagExecutionDryRunResponse dryRun = fixture.dryRun(List.of("data-sync-execute"));

        AgentRunToolDagSelectedNodeOutboxEnqueueResponse first =
                fixture.confirm(List.of("data-sync-execute"), dryRun.selectionFingerprint());
        AgentRunToolDagSelectedNodeOutboxEnqueueResponse second =
                fixture.confirm(List.of("data-sync-execute"), dryRun.selectionFingerprint());

        assertEquals(1, first.outbox().enqueuedCount());
        assertEquals(0, second.outbox().enqueuedCount());
        assertEquals(1, second.outbox().duplicateCount());
        assertEquals(first.confirmationId(), second.confirmationId());
        assertEquals(1, fixture.store.list(RUN_ID, null, 10).size());
        assertEquals(1, fixture.confirmationStore.listByRun(RUN_ID, 10).size());
    }

    @Test
    void staleDryRunFingerprintShouldFailClosed() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(asyncAudit("audit-async", "data-sync-execute", Map.of()));

        assertThrows(PlatformBusinessException.class, () ->
                fixture.confirm(List.of("data-sync-execute"), "dag-selection:stale"));
        assertTrue(fixture.store.list(RUN_ID, null, 10).isEmpty());
        assertTrue(fixture.confirmationStore.listByRun(RUN_ID, 10).isEmpty());
    }

    @Test
    void mixedAsyncAndBlockedNodesShouldRejectWholeBatch() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(
                asyncAudit("audit-async", "data-sync-execute", Map.of()),
                syncAudit("audit-blocked", "quality-followup", Map.of("dependsOn", List.of("data-sync-execute")))
        );
        List<String> selectedNodes = List.of("data-sync-execute", "quality-followup");
        AgentRunToolDagExecutionDryRunResponse dryRun = fixture.dryRun(selectedNodes);

        assertThrows(PlatformBusinessException.class, () ->
                fixture.confirm(selectedNodes, dryRun.selectionFingerprint()));
        assertTrue(fixture.store.list(RUN_ID, null, 10).isEmpty());
        assertTrue(fixture.confirmationStore.listByRun(RUN_ID, 10).isEmpty());
    }

    @Test
    void confirmationMustExplicitlySelectNodes() {
        TestFixture fixture = newFixture();

        assertThrows(PlatformBusinessException.class, () ->
                fixture.service.enqueueSelectedAsyncNodes(
                        SESSION_ID,
                        RUN_ID,
                        new AgentRunToolDagSelectedNodeOutboxEnqueueRequest(
                                List.of(),
                                List.of(),
                                null,
                                "dag-selection:any",
                                true
                        ),
                        "trace-selected"
                ));
    }

    private TestFixture newFixture() {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        AgentAsyncTaskCommandOutboxProperties outboxProperties = new AgentAsyncTaskCommandOutboxProperties();
        AgentRunToolDagConfirmationProperties confirmationProperties = new AgentRunToolDagConfirmationProperties();
        AgentToolServiceAuthorizationProperties authorizationProperties = new AgentToolServiceAuthorizationProperties();
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
        AgentRunToolPlanDagService dagService = new AgentRunToolPlanDagService(policyService, auditService);
        AgentRunAsyncTaskCommandPlanningService planningService = new AgentRunAsyncTaskCommandPlanningService(
                runtimeProperties,
                policyService,
                auditService
        );
        AgentRunToolDagExecutionPreviewService previewService = new AgentRunToolDagExecutionPreviewService(
                runtimeProperties,
                auditService,
                dagService,
                policyService,
                planningService,
                new AgentToolServiceAuthorizationPreviewService(authorizationProperties, request -> null)
        );
        AgentRunToolDagExecutionDryRunService dryRunService = new AgentRunToolDagExecutionDryRunService(
                previewService,
                new AgentRunToolDagExecutionDryRunEventPublisher(
                        new InMemoryAgentRuntimeEventProjectionStore(50, 200),
                        sessionStore
                ),
                new AgentRunToolDagSelectionFingerprintSupport()
        );
        InMemoryAgentAsyncTaskCommandOutboxStore store = new InMemoryAgentAsyncTaskCommandOutboxStore(10, 100);
        AgentRunAsyncTaskCommandOutboxService outboxService = new AgentRunAsyncTaskCommandOutboxService(
                outboxProperties,
                planningService,
                store,
                new ObjectMapper()
        );
        InMemoryAgentRunToolDagConfirmationStore confirmationStore =
                new InMemoryAgentRunToolDagConfirmationStore(10, 100);
        AgentRunToolDagSelectedNodeOutboxService service = new AgentRunToolDagSelectedNodeOutboxService(
                dryRunService,
                outboxService,
                confirmationStore,
                confirmationProperties
        );
        AgentSessionRecord session = new AgentSessionRecord(
                SESSION_ID,
                10L,
                20L,
                30L,
                "actor-selected",
                "PYTHON_AI_RUNTIME",
                "DAG 选中节点 outbox 测试",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                BASE_TIME
        );
        session.addRun(new AgentRunRecord(
                RUN_ID,
                SESSION_ID,
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "测试 DAG 选中节点 outbox 确认",
                true,
                false,
                List.of(),
                Map.of(),
                BASE_TIME,
                "Run 已创建"
        ));
        sessionStore.save(session);
        return new TestFixture(service, dryRunService, store, confirmationStore, auditStore);
    }

    private AgentToolExecutionAuditRecord asyncAudit(String auditId,
                                                     String nodeId,
                                                     Map<String, Object> additionalHints) {
        return audit(
                auditId,
                nodeId,
                AgentToolExecutionMode.ASYNC_TASK,
                AgentToolRiskLevel.MEDIUM,
                false,
                false,
                true,
                additionalHints
        );
    }

    private AgentToolExecutionAuditRecord syncAudit(String auditId,
                                                    String nodeId,
                                                    Map<String, Object> additionalHints) {
        return audit(
                auditId,
                nodeId,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                additionalHints
        );
    }

    private AgentToolExecutionAuditRecord audit(String auditId,
                                                String nodeId,
                                                AgentToolExecutionMode mode,
                                                AgentToolRiskLevel riskLevel,
                                                boolean requiresApproval,
                                                boolean readOnly,
                                                boolean idempotent,
                                                Map<String, Object> additionalHints) {
        Map<String, Object> hints = new java.util.LinkedHashMap<>(additionalHints);
        hints.put("planNodeId", nodeId);
        return new AgentToolExecutionAuditRecord(
                auditId,
                SESSION_ID,
                RUN_ID,
                "plan:" + RUN_ID + ":" + auditId,
                mode == AgentToolExecutionMode.ASYNC_TASK ? "data-sync.execute" : "quality.scan.preview",
                "INTERNAL_API",
                mode == AgentToolExecutionMode.ASYNC_TASK ? "data-sync" : "data-quality",
                mode == AgentToolExecutionMode.ASYNC_TASK ? "/internal/data-sync/agent/tasks/execute" : "/quality/preview",
                1001L,
                10L,
                20L,
                30L,
                "actor-selected",
                riskLevel.name(),
                mode.name(),
                requiresApproval,
                readOnly,
                idempotent,
                List.of(mode == AgentToolExecutionMode.ASYNC_TASK ? "CREATE_TASK" : "READ"),
                "DAG selected-node outbox 测试工具",
                Map.of("datasourceId", 1001L),
                hints,
                Map.of(),
                AgentToolExecutionState.PLANNED,
                "trace-selected",
                "工具计划已生成",
                BASE_TIME
        );
    }

    private record TestFixture(AgentRunToolDagSelectedNodeOutboxService service,
                               AgentRunToolDagExecutionDryRunService dryRunService,
                               InMemoryAgentAsyncTaskCommandOutboxStore store,
                               InMemoryAgentRunToolDagConfirmationStore confirmationStore,
                               AgentToolExecutionAuditMemoryStore auditStore) {

        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }

        AgentRunToolDagExecutionDryRunResponse dryRun(List<String> nodeIds) {
            return dryRunService.dryRunDagExecution(
                    SESSION_ID,
                    RUN_ID,
                    new AgentRunToolDagExecutionDryRunRequest(nodeIds, List.of(), null, false),
                    "trace-selected"
            );
        }

        AgentRunToolDagSelectedNodeOutboxEnqueueResponse confirm(List<String> nodeIds, String fingerprint) {
            return service.enqueueSelectedAsyncNodes(
                    SESSION_ID,
                    RUN_ID,
                    new AgentRunToolDagSelectedNodeOutboxEnqueueRequest(
                            nodeIds,
                            List.of(),
                            null,
                            fingerprint,
                            true
                    ),
                    "trace-selected"
            );
        }
    }
}
