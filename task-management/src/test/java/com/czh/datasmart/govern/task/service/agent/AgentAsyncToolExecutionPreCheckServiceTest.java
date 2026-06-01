/**
 * @Author : Cui
 * @Date: 2026/06/01 21:18
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionPreCheckServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 异步工具执行前二次复核测试。
 *
 * <p>这些用例不调用真实 data-sync，也不发起真实 HTTP。
 * 目标是把 worker 执行前安全门固定下来：任务状态、Agent 审计状态、工具白名单、本地执行证据，
 * mock agent-runtime 客户端完成的 DAG selected-node confirmation 一致性回查，
 * 以及 mock permission-admin 客户端完成的服务账号委托授权实时复核。</p>
 */
class AgentAsyncToolExecutionPreCheckServiceTest {

    @Test
    void shouldAllowExecutablePayloadWithSelectedNodeEvidence() {
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class))).thenReturn(confirmationView());
        PermissionAdminAgentAsyncToolAuthorizationClient permissionClient = allowPermissionClient();
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()), confirmationClient, permissionClient);

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870")));

        assertTrue(result.allowed());
        assertTrue(result.validationMessages().stream().anyMatch(message -> message.contains("工具白名单复核通过")));
        assertTrue(result.validationMessages().stream().anyMatch(message -> message.contains("agent-runtime 确认回查通过")));
        assertTrue(result.validationMessages().stream().anyMatch(message -> message.contains("permission-admin 实时授权复核通过")));
    }

    @Test
    void shouldRejectPayloadWithoutWhitelistExecutor() {
        AgentAsyncToolExecutionPreCheckService service = service(List.of(), mock(AgentRuntimeToolDagConfirmationClient.class),
                mock(PermissionAdminAgentAsyncToolAuthorizationClient.class));

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("白名单适配器"));
    }

    @Test
    void shouldRejectWaitingApprovalAuditStateBeforeSideEffect() {
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()),
                mock(AgentRuntimeToolDagConfirmationClient.class),
                mock(PermissionAdminAgentAsyncToolAuthorizationClient.class));

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "WAITING_APPROVAL",
                "dag-confirmation:test-001", List.of("route-policy:870")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("审计状态不允许"));
    }

    @Test
    void shouldRejectSensitiveDelegationEvidence() {
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()),
                mock(AgentRuntimeToolDagConfirmationClient.class),
                mock(PermissionAdminAgentAsyncToolAuthorizationClient.class));

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870"),
                List.of("prompt: system secret")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("低敏审计摘要"));
    }

    @Test
    void shouldRejectWhenRemoteConfirmationDoesNotContainCommandId() {
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class))).thenReturn(confirmationView(
                List.of("audit-001"),
                List.of("other-command"),
                List.of("route-policy:870"),
                List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-agent")
        ));
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()), confirmationClient, allowPermissionClient());

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("commandId"));
    }

    @Test
    void shouldSkipRemoteConfirmationForCompatibilityCommandWithoutConfirmationId() {
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()), confirmationClient, allowPermissionClient());

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                null, List.of("route-policy:870")));

        assertTrue(result.allowed());
        verifyNoInteractions(confirmationClient);
    }

    @Test
    void shouldDeferByExceptionWhenRemoteConfirmationTemporarilyUnavailable() {
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class)))
                .thenThrow(new IllegalStateException("agent-runtime unavailable"));
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()), confirmationClient, allowPermissionClient());

        assertThrows(AgentAsyncToolPreCheckUnavailableException.class, () -> service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870"))));
    }

    @Test
    void shouldRejectWhenPermissionAdminDeniesExecution() {
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class))).thenReturn(confirmationView());
        PermissionAdminAgentAsyncToolAuthorizationClient permissionClient = mock(PermissionAdminAgentAsyncToolAuthorizationClient.class);
        when(permissionClient.evaluate(any(AgentAsyncToolPermissionAuthorizationRequest.class))).thenReturn(permissionResult(
                false,
                "命中显式拒绝策略",
                false,
                "route-policy:870:updatedAt:now:priority:870:effect:DENY"
        ));
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()), confirmationClient, permissionClient);

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("permission-admin 拒绝"));
    }

    @Test
    void shouldRejectWhenPermissionPolicyVersionDrifts() {
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class))).thenReturn(confirmationView());
        PermissionAdminAgentAsyncToolAuthorizationClient permissionClient = mock(PermissionAdminAgentAsyncToolAuthorizationClient.class);
        when(permissionClient.evaluate(any(AgentAsyncToolPermissionAuthorizationRequest.class))).thenReturn(permissionResult(
                true,
                "命中允许策略",
                false,
                "route-policy:871"
        ));
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()), confirmationClient, permissionClient);

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("策略版本"));
    }

    @Test
    void shouldDeferByExceptionWhenPermissionAdminTemporarilyUnavailable() {
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class))).thenReturn(confirmationView());
        PermissionAdminAgentAsyncToolAuthorizationClient permissionClient = mock(PermissionAdminAgentAsyncToolAuthorizationClient.class);
        when(permissionClient.evaluate(any(AgentAsyncToolPermissionAuthorizationRequest.class)))
                .thenThrow(new IllegalStateException("permission-admin unavailable"));
        AgentAsyncToolExecutionPreCheckService service = service(List.of(new FakeExecutor()), confirmationClient, permissionClient);

        assertThrows(AgentAsyncToolPreCheckUnavailableException.class, () -> service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:870"))));
    }

    private AgentAsyncToolExecutionPreCheckService service(List<AgentAsyncToolExecutor> executors,
                                                           AgentRuntimeToolDagConfirmationClient confirmationClient,
                                                           PermissionAdminAgentAsyncToolAuthorizationClient permissionClient) {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setConfirmationCheckEnabled(true);
        properties.setConfirmationCheckFailOpenOnError(false);
        properties.setPermissionCheckEnabled(true);
        properties.setPermissionCheckFailOpenOnError(false);
        return new AgentAsyncToolExecutionPreCheckService(executors, properties, confirmationClient, permissionClient);
    }

    private AgentAsyncToolResolvedPayload payload(String taskStatus,
                                                  String auditState,
                                                  String confirmationId,
                                                  List<String> policyVersions) {
        return payload(taskStatus, auditState, confirmationId, policyVersions,
                List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-agent"));
    }

    private AgentAsyncToolResolvedPayload payload(String taskStatus,
                                                  String auditState,
                                                  String confirmationId,
                                                  List<String> policyVersions,
                                                  List<String> delegationEvidence) {
        return new AgentAsyncToolResolvedPayload(
                9001L,
                taskStatus,
                AgentAsyncToolPayloadResolver.TASK_TYPE,
                "cmd-001",
                "agent-tool-audit://session-001/run-001/audit-001/plan-arguments",
                "plan-arguments",
                "session-001",
                "run-001",
                "audit-001",
                "data-sync.execute",
                "data-sync",
                "/internal/data-sync/agent/tasks/execute",
                10L,
                20L,
                30L,
                "1001",
                "trace-worker",
                "ASYNC_TASK",
                auditState,
                List.of("syncTemplateId"),
                List.of(),
                24,
                false,
                confirmationId,
                policyVersions,
                delegationEvidence,
                Map.of("syncTemplateId", 6001L),
                Map.of(),
                Map.of(),
                List.of("预检通过"),
                List.of(),
                LocalDateTime.now()
        );
    }

    private AgentRuntimeToolDagConfirmationView confirmationView() {
        return confirmationView(
                List.of("audit-001"),
                List.of("cmd-001"),
                List.of("route-policy:870"),
                List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-agent")
        );
    }

    private AgentRuntimeToolDagConfirmationView confirmationView(List<String> selectedAuditIds,
                                                                 List<String> commandIds,
                                                                 List<String> policyVersions,
                                                                 List<String> delegationEvidence) {
        return new AgentRuntimeToolDagConfirmationView(
                "dag-confirmation:test-001",
                "session-001",
                "run-001",
                "fingerprint-001",
                List.of("node-001"),
                selectedAuditIds,
                policyVersions,
                delegationEvidence,
                List.of("outbox-001"),
                commandIds,
                10L,
                20L,
                30L,
                "1001",
                "trace-worker",
                true,
                "CONFIRMED",
                Instant.now().plusSeconds(300),
                Instant.now(),
                Instant.now()
        );
    }

    private PermissionAdminAgentAsyncToolAuthorizationClient allowPermissionClient() {
        PermissionAdminAgentAsyncToolAuthorizationClient permissionClient =
                mock(PermissionAdminAgentAsyncToolAuthorizationClient.class);
        when(permissionClient.evaluate(any(AgentAsyncToolPermissionAuthorizationRequest.class))).thenReturn(permissionResult(
                true,
                "命中允许策略",
                false,
                "route-policy:870"
        ));
        return permissionClient;
    }

    private AgentAsyncToolPermissionAuthorizationResult permissionResult(boolean allowed,
                                                                        String reason,
                                                                        boolean approvalRequired,
                                                                        String policyVersion) {
        return new AgentAsyncToolPermissionAuthorizationResult(
                allowed,
                reason,
                allowed ? "ALLOW" : "DENY",
                "TENANT",
                List.of(),
                approvalRequired,
                policyVersion,
                true,
                "delegationType=SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR;serviceAccount=datasmart-task-management-agent-worker;representedActor=1001"
        );
    }

    private static class FakeExecutor implements AgentAsyncToolExecutor {

        @Override
        public boolean supports(String toolCode) {
            return "data-sync.execute".equals(toolCode);
        }

        @Override
        public AgentAsyncToolExecutionResult execute(AgentAsyncToolResolvedPayload payload) {
            return AgentAsyncToolExecutionResult.success("不会在 pre-check 测试中真正执行", Map.of());
        }
    }
}
