/**
 * @Author : Cui
 * @Date: 2026/06/01 00:12
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionDryRunServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
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
import com.czh.datasmart.govern.agent.service.authorization.AgentToolServiceAuthorizationPreviewService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentRuntimeEventProjectionStore;
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
 * DAG-aware 执行 dry-run 服务测试。
 *
 * <p>这些测试重点保护“无副作用批次预演”语义：dry-run 可以把 preview 候选翻译成同步执行 dryRun 或异步 outbox 预案，
 * 但不会触发真实执行、不会写 task-management、不会写 outbox。测试通过内存仓储构造会话和工具审计记录，
 * 让服务完整复用 DAG preview、policy 和 async command planning 的真实逻辑，而不是 mock 出一条脱离生产代码的捷径。</p>
 */
class AgentRunToolDagExecutionDryRunServiceTest {

    private static final String SESSION_ID = "session-dry-run-001";
    private static final String RUN_ID = "run-dry-run-001";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 6, 1, 0, 12);

    @Test
    void noExplicitSelectionShouldDryRunOnlyExecutableCandidates() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(
                audit("audit-sync", 1, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true, Map.of("planNodeId", "metadata-read")),
                audit("audit-blocked", 2, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true, Map.of("planNodeId", "quality-rule", "dependsOn", List.of("metadata-read")))
        );

        AgentRunToolDagExecutionDryRunResponse response = fixture.dryRunService.dryRunDagExecution(SESSION_ID, RUN_ID, null);

        assertEquals(1, response.selectedCount());
        assertEquals(1, response.syncDryRunCandidateCount());
        assertEquals(0, response.asyncEnqueuePreviewCount());
        assertEquals(1, response.notSelectedCount());
        assertEquals(1, response.items().size());
        AgentToolDagExecutionDryRunItemView item = response.items().getFirst();
        assertEquals("metadata-read", item.nodeId());
        assertEquals(AgentToolDagExecutionDryRunAction.SYNC_AUTO_EXECUTE_DRY_RUN.name(), item.dryRunAction());
        assertTrue(item.executionPath().contains("auto-execute-sync"));
        assertTrue(item.executionPath().contains("dryRun=true"));
        assertTrue(item.targetWouldTriggerSideEffect());
    }

    @Test
    void dryRunShouldAppendRuntimeEventSummary() {
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

        AgentRunToolDagExecutionDryRunResponse response = fixture.dryRunService.dryRunDagExecution(
                SESSION_ID,
                RUN_ID,
                null,
                "trace-dry-run-event"
        );

        List<AgentRuntimeEventProjectionRecord> events = fixture.projectionStore.listByRunId(RUN_ID);
        assertEquals(1, events.size());
        AgentRuntimeEventProjectionRecord event = events.getFirst();
        assertEquals(AgentRunToolDagExecutionDryRunEventPublisher.EVENT_TYPE, event.eventType());
        assertEquals("dag_execution_dry_run_completed", event.stage());
        assertEquals("trace-dry-run-event", event.requestId());
        assertEquals("10", event.tenantId());
        assertEquals("20", event.projectId());
        assertEquals("actor-dry-run", event.actorId());
        assertEquals(response.selectedCount(), event.attributes().get("selectedCount"));
        assertEquals(response.syncDryRunCandidateCount(), event.attributes().get("syncDryRunCandidateCount"));
        assertEquals(response.selectionFingerprint(), event.attributes().get("selectionFingerprint"));
        assertTrue(response.selectionFingerprint().startsWith("dag-selection:"));
        assertEquals("SUMMARY_ONLY_NO_TOOL_ARGUMENTS_NO_EXECUTION_PATH", event.attributes().get("eventPayloadPolicy"));
        assertEquals(1, ((List<?>) event.attributes().get("items")).size());
    }

    @Test
    void selectedAsyncNodeShouldReturnOutboxEnqueuePreviewWithoutSideEffect() {
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

        AgentRunToolDagExecutionDryRunResponse response = fixture.dryRunService.dryRunDagExecution(
                SESSION_ID,
                RUN_ID,
                new AgentRunToolDagExecutionDryRunRequest(List.of("data-sync-execute"), List.of(), null, false)
        );

        assertEquals(1, response.selectedCount());
        assertEquals(1, response.asyncEnqueuePreviewCount());
        AgentToolDagExecutionDryRunItemView item = response.items().getFirst();
        assertEquals(AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW.name(), item.dryRunAction());
        assertTrue(item.asyncDispatchable());
        assertTrue(item.asyncCommandId().startsWith("aatc_"));
        assertTrue(item.executionPath().contains("仅预演"));
    }

    @Test
    void selectedBlockedNodeShouldStayBlockedByPreview() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(
                audit("audit-first", 1, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true, Map.of("planNodeId", "first")),
                audit("audit-second", 2, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true, Map.of("planNodeId", "second", "dependsOn", List.of("first")))
        );

        AgentRunToolDagExecutionDryRunResponse response = fixture.dryRunService.dryRunDagExecution(
                SESSION_ID,
                RUN_ID,
                new AgentRunToolDagExecutionDryRunRequest(List.of("second"), List.of(), null, false)
        );

        assertEquals(1, response.selectedCount());
        assertEquals(1, response.blockedCount());
        AgentToolDagExecutionDryRunItemView item = response.items().getFirst();
        assertEquals("second", item.nodeId());
        assertEquals(AgentToolDagExecutionDryRunAction.BLOCKED_BY_PREVIEW.name(), item.dryRunAction());
        assertEquals(AgentToolDagExecutionPreviewAction.WAIT_DEPENDENCIES.name(), item.previewAction());
        assertFalse(item.targetWouldTriggerSideEffect());
    }

    @Test
    void unknownNodeAndAuditSelectorsShouldBeReturnedAsDiagnostics() {
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

        AgentRunToolDagExecutionDryRunResponse response = fixture.dryRunService.dryRunDagExecution(
                SESSION_ID,
                RUN_ID,
                new AgentRunToolDagExecutionDryRunRequest(List.of("missing-node"), List.of("missing-audit"), null, false)
        );

        assertEquals(0, response.selectedCount());
        assertEquals(2, response.notFoundCount());
        assertTrue(response.items().stream()
                .allMatch(item -> AgentToolDagExecutionDryRunAction.REQUESTED_NODE_OR_AUDIT_NOT_FOUND.name().equals(item.dryRunAction())));
    }

    @Test
    void maxNodesShouldKeepBatchLimitVisible() {
        TestFixture fixture = newFixture();
        fixture.saveAudits(
                audit("audit-anchor", 1, AgentToolExecutionState.SUCCEEDED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true, Map.of("planNodeId", "metadata-anchor")),
                audit("audit-sync-1", 2, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "metadata-read-1", "dependsOn", List.of("metadata-anchor"))),
                audit("audit-sync-2", 3, AgentToolExecutionState.PLANNED, AgentToolExecutionMode.SYNC,
                        AgentToolRiskLevel.LOW, false, true, true,
                        Map.of("planNodeId", "metadata-read-2", "dependsOn", List.of("metadata-anchor")))
        );

        AgentRunToolDagExecutionDryRunResponse response = fixture.dryRunService.dryRunDagExecution(
                SESSION_ID,
                RUN_ID,
                new AgentRunToolDagExecutionDryRunRequest(List.of(), List.of(), 1, false)
        );

        assertEquals(1, response.selectedCount());
        assertEquals(1, response.batchLimitReachedCount());
        assertEquals(2, response.items().size());
        assertTrue(response.items().stream()
                .anyMatch(item -> AgentToolDagExecutionDryRunAction.BATCH_LIMIT_REACHED.name().equals(item.dryRunAction())));
    }

    @Test
    void explicitSandboxRejectedNodeShouldStayBlockedAndExposeSandboxIssues() {
        TestFixture fixture = newSandboxFixture();
        fixture.saveAudits(audit(
                "audit-sandbox",
                1,
                AgentToolExecutionState.PLANNED,
                AgentToolExecutionMode.SYNC,
                AgentToolRiskLevel.LOW,
                false,
                true,
                true,
                Map.of("planNodeId", "sandbox-blocked")
        ));

        AgentRunToolDagExecutionDryRunResponse response = fixture.dryRunService.dryRunDagExecution(
                SESSION_ID,
                RUN_ID,
                new AgentRunToolDagExecutionDryRunRequest(List.of("sandbox-blocked"), List.of(), null, false)
        );

        assertEquals(1, response.selectedCount());
        assertEquals(1, response.blockedCount());
        AgentToolDagExecutionDryRunItemView item = response.items().getFirst();
        assertEquals(AgentToolDagExecutionDryRunAction.BLOCKED_BY_PREVIEW.name(), item.dryRunAction());
        assertFalse(item.sandboxAllowed());
        assertTrue(item.sandboxIssueCodes().contains("ARGUMENT_BYTES_EXCEED_LIMIT"));
        assertTrue(response.summaryReasons().stream().anyMatch(reason -> reason.contains("沙箱拒绝")));
    }

    private TestFixture newFixture() {
        return newFixture(false);
    }

    private TestFixture newSandboxFixture() {
        return newFixture(true);
    }

    private TestFixture newFixture(boolean enableSandbox) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        if (enableSandbox) {
            properties.getToolSandbox().setMaxArgumentBytes(16);
            properties.getToolServiceBaseUrls().put("datasource-management", "http://localhost:8082");
            properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        }
        AgentToolServiceAuthorizationProperties authorizationProperties = new AgentToolServiceAuthorizationProperties();
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentRunToolExecutionPolicyService policyService = enableSandbox
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
        AgentRunToolPlanDagService dagService = new AgentRunToolPlanDagService(policyService, auditService);
        AgentRunAsyncTaskCommandPlanningService asyncPlanningService = new AgentRunAsyncTaskCommandPlanningService(
                properties,
                policyService,
                auditService
        );
        AgentToolServiceAuthorizationPreviewService authorizationPreviewService = new AgentToolServiceAuthorizationPreviewService(
                authorizationProperties,
                request -> null
        );
        AgentRunToolDagExecutionPreviewService previewService = new AgentRunToolDagExecutionPreviewService(
                properties,
                auditService,
                dagService,
                policyService,
                asyncPlanningService,
                authorizationPreviewService
        );
        InMemoryAgentRuntimeEventProjectionStore projectionStore = new InMemoryAgentRuntimeEventProjectionStore(50, 200);
        AgentRunToolDagExecutionDryRunEventPublisher dryRunEventPublisher = new AgentRunToolDagExecutionDryRunEventPublisher(
                projectionStore,
                sessionStore
        );
        AgentRunToolDagExecutionDryRunService dryRunService = new AgentRunToolDagExecutionDryRunService(
                previewService,
                dryRunEventPublisher,
                new AgentRunToolDagSelectionFingerprintSupport()
        );
        AgentSessionRecord session = new AgentSessionRecord(
                SESSION_ID,
                10L,
                20L,
                30L,
                "actor-dry-run",
                "PYTHON_AI_RUNTIME",
                "DAG 执行 dry-run 测试会话",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                BASE_TIME
        );
        session.addRun(new AgentRunRecord(
                RUN_ID,
                SESSION_ID,
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "测试 DAG-aware execution dry-run",
                true,
                false,
                List.of(),
                Map.of(),
                BASE_TIME,
                "Run 已创建"
        ));
        sessionStore.save(session);
        return new TestFixture(dryRunService, auditStore, projectionStore);
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
                "actor-dry-run",
                riskLevel.name(),
                mode.name(),
                requiresApproval,
                readOnly,
                idempotent,
                List.of("READ"),
                "DAG execution dry-run 测试工具计划 " + sequence,
                Map.of("datasourceId", 1000L + sequence, "syncConfig", Map.of("mode", "FULL")),
                governanceHints,
                Map.of(),
                state,
                "trace-dry-run",
                "工具计划已生成",
                BASE_TIME.plusSeconds(sequence)
        );
    }

    private record TestFixture(AgentRunToolDagExecutionDryRunService dryRunService,
                               AgentToolExecutionAuditMemoryStore auditStore,
                               InMemoryAgentRuntimeEventProjectionStore projectionStore) {

        /**
         * 保存测试审计记录。
         *
         * <p>dry-run 服务只应读取这些记录并形成预案，不应在测试过程中修改它们。这里直接写入内存仓储，
         * 是为了让用例聚焦“读取现有审计事实 -> 生成拟执行批次”的核心业务路径。</p>
         */
        void saveAudits(AgentToolExecutionAuditRecord... records) {
            auditStore.saveAll(List.of(records));
        }
    }
}
