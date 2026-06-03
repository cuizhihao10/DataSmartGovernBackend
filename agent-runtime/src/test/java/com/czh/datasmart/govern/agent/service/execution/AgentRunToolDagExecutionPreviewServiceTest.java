/**
 * @Author : Cui
 * @Date: 2026/05/31 23:45
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionPreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionPreviewItemView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolServiceAuthorizationDecision;
import com.czh.datasmart.govern.agent.model.AgentToolServiceAuthorizationMode;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.authorization.AgentToolServiceAuthorizationRemoteRequest;
import com.czh.datasmart.govern.agent.service.authorization.AgentToolServiceAuthorizationRemoteResult;
import com.czh.datasmart.govern.agent.service.authorization.AgentToolServiceAuthorizationPreviewService;
import com.czh.datasmart.govern.agent.service.authorization.PermissionAdminServiceAuthorizationClient;
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
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

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
        assertTrue(item.runtimeProtectionAllowed());
        assertEquals("LOW", item.riskLevel());
        assertEquals(0, preview.serviceAuthorizationEvaluatedCount());
        assertEquals(AgentToolServiceAuthorizationDecision.NOT_EVALUATED.name(), item.serviceAuthorization().decision());
    }

    @Test
    void localServiceAuthorizationPreviewShouldExplainServiceAccountContext() {
        TestFixture fixture = newFixture(properties -> properties.setEnabled(true));
        fixture.saveAudits(audit(
                "audit-local-auth",
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

        AgentToolDagExecutionPreviewItemView item = item(preview, "metadata-read");
        assertEquals(1, preview.serviceAuthorizationEvaluatedCount());
        assertEquals(1, preview.serviceAuthorizationAllowedCount());
        assertEquals(AgentToolServiceAuthorizationDecision.LOCAL_PREVIEW_ALLOWED.name(), item.serviceAuthorization().decision());
        assertTrue(item.serviceAuthorization().allowed());
        assertEquals("datasmart-agent-runtime", item.serviceAuthorization().serviceAccountCode());
        assertEquals("actor-preview", item.serviceAuthorization().representedActorId());
        assertEquals(List.of("VIEW"), item.serviceAuthorization().requiredActions());
    }

    @Test
    void permissionAdminPreviewShouldSendDelegationContextToPermissionAdmin() {
        AtomicReference<AgentToolServiceAuthorizationRemoteRequest> capturedRequest = new AtomicReference<>();
        TestFixture fixture = newFixture(properties -> {
            properties.setEnabled(true);
            properties.setMode(AgentToolServiceAuthorizationMode.PERMISSION_ADMIN_EVALUATE);
        }, request -> {
            capturedRequest.set(request);
            return new AgentToolServiceAuthorizationRemoteResult(
                    true,
                    "允许服务账号代表用户预检工具动作",
                    "ALLOW",
                    "TENANT",
                    List.of(),
                    false,
                    "route-policy:860:updatedAt:unknown:priority:860:effect:ALLOW",
                    true,
                    "delegationType=SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR;serviceAccount=datasmart-agent-runtime"
            );
        });
        fixture.saveAudits(audit(
                "audit-remote-auth",
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

        AgentToolDagExecutionPreviewItemView item = item(preview, "metadata-read");
        assertEquals(AgentToolServiceAuthorizationDecision.PERMISSION_ADMIN_ALLOWED.name(), item.serviceAuthorization().decision());
        assertEquals("datasmart-agent-runtime", capturedRequest.get().serviceAccountCode());
        assertEquals("actor-preview", capturedRequest.get().representedActorId());
        assertEquals("SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR", capturedRequest.get().delegationType());
        assertTrue(capturedRequest.get().delegationReason().contains("AGENT_RUNTIME_TOOL_PREVIEW"));
        assertTrue(item.serviceAuthorization().delegationReason().contains("AGENT_RUNTIME_TOOL_PREVIEW"));
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

    @Test
    void sandboxRejectedNodeShouldBeVisibleAndBlockedInPreview() {
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

        AgentRunToolDagExecutionPreviewView preview = fixture.previewService.previewRunDagExecution(SESSION_ID, RUN_ID);

        assertFalse(preview.hasExecutableCandidates());
        assertEquals(1, preview.blockedCount());
        AgentToolDagExecutionPreviewItemView item = item(preview, "sandbox-blocked");
        assertEquals(AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY.name(), item.previewAction());
        assertFalse(item.sandboxAllowed());
        assertTrue(item.sandboxIssueCodes().contains("ARGUMENT_BYTES_EXCEED_LIMIT"));
        assertTrue(preview.summaryReasons().stream().anyMatch(reason -> reason.contains("工具调用沙箱未通过")));
    }

    private AgentToolDagExecutionPreviewItemView item(AgentRunToolDagExecutionPreviewView preview, String nodeId) {
        return preview.items().stream()
                .filter(item -> nodeId.equals(item.nodeId()))
                .findFirst()
                .orElseThrow();
    }

    private TestFixture newFixture() {
        return newFixture(properties -> {
        });
    }

    private TestFixture newSandboxFixture() {
        return newFixture(properties -> {
        }, request -> null, true);
    }

    private TestFixture newFixture(Consumer<AgentToolServiceAuthorizationProperties> authorizationCustomizer) {
        return newFixture(authorizationCustomizer, request -> null, false);
    }

    private TestFixture newFixture(Consumer<AgentToolServiceAuthorizationProperties> authorizationCustomizer,
                                   PermissionAdminServiceAuthorizationClient authorizationClient) {
        return newFixture(authorizationCustomizer, authorizationClient, false);
    }

    private TestFixture newFixture(Consumer<AgentToolServiceAuthorizationProperties> authorizationCustomizer,
                                   PermissionAdminServiceAuthorizationClient authorizationClient,
                                   boolean enableSandbox) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        if (enableSandbox) {
            properties.getToolSandbox().setMaxArgumentBytes(16);
            properties.getToolServiceBaseUrls().put("datasource-management", "http://localhost:8082");
            properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        }
        AgentToolServiceAuthorizationProperties authorizationProperties = new AgentToolServiceAuthorizationProperties();
        authorizationCustomizer.accept(authorizationProperties);
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
                authorizationClient
        );
        AgentRunToolDagExecutionPreviewService previewService = new AgentRunToolDagExecutionPreviewService(
                properties,
                auditService,
                dagService,
                policyService,
                asyncPlanningService,
                authorizationPreviewService
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
