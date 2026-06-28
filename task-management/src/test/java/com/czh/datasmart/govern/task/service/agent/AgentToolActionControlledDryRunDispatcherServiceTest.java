/**
 * @Author : Cui
 * @Date: 2026/06/11 22:00
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunDispatcherServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandContractSupport;
import com.czh.datasmart.govern.task.support.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受控工具动作 dry-run 调度器测试。
 *
 * <p>这些测试刻意不复用历史 AgentAsyncTool worker 的 fake executor，因为新链路当前不能执行真实工具。
 * 我们验证的不只是 payload store 证据，还包括 permission-admin 审批事实回查：审批通过才能继续等待
 * payload/executor，审批 pending 要 defer，审批拒绝必须 fail-closed。</p>
 */
class AgentToolActionControlledDryRunDispatcherServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldClaimControlledTaskAndDeferWhenPayloadBodyIsNotMaterialized() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentRuntimeToolActionControlledReceiptClient receiptClient = receiptClient();
        PermissionAdminAgentToolActionApprovalClient approvalClient = approvedApprovalClient();
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, metricsService, receiptClient, approvalClient);
        Task task = controlledTask(List.of(
                "REFERENCE_PREFIX:agent-payload",
                "RUN_ID_BOUND:run-proposal",
                "AGENT_PAYLOAD_RECORD_FOUND",
                "AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED",
                "PAYLOAD_BODY_NOT_MATERIALIZED"
        ));
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", task, run(9201L)));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals(true, result.claimed());
        assertEquals(9101L, result.taskId());
        assertEquals(9201L, result.runId());
        assertEquals("DEFERRED_WAITING_PAYLOAD_BODY", result.outcome());
        assertEquals(true, result.preCheckPassed());
        assertEquals(false, result.sideEffectExecuted());
        assertEquals("taoc-consume-001", result.commandId());
        assertTrue(result.receiptDelivery().attempted());
        assertTrue(result.receiptDelivery().accepted());

        verify(taskService).deferTask(eq(9101L), any(String.class), eq(120), any(TaskExecutionCallbackContext.class));
        verify(taskService, never()).failTask(any(), any(), any());
        verify(metricsService).recordDispatchOutcome("DEFERRED_WAITING_PAYLOAD_BODY");
        verify(approvalClient).evaluate(any(AgentToolActionControlledApprovalEvaluationRequest.class));
        verify(receiptClient).publishDryRunReceipt(
                any(AgentToolActionControlledTaskPayload.class),
                eq(9201L),
                eq("DEFERRED_WAITING_PAYLOAD_BODY"),
                eq(true),
                any(String.class),
                isNull(),
                any(),
                any(TaskActorContext.class)
        );
        verifyClaimedControlledTask(taskService);
    }

    @Test
    void shouldDelegateToQualityRemediationExecutorWhenBodyApprovalAndSubmitGateAreReady() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        properties.setDryRunOnly(false);
        properties.setControlledActionSubmitEnabled(true);
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentRuntimeToolActionControlledReceiptClient receiptClient = receiptClient();
        PermissionAdminAgentToolActionApprovalClient approvalClient = approvedApprovalClient();
        AgentToolActionControlledQualityRemediationExecutionService executionService =
                mock(AgentToolActionControlledQualityRemediationExecutionService.class);
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, metricsService, receiptClient, approvalClient, executionService);
        Task task = controlledQualityTaskWithBodyAvailable();
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", task, run(9205L)));
        when(executionService.execute(eq(task), eq(9205L), any(AgentToolActionControlledTaskPayload.class),
                any(TaskActorContext.class), any()))
                .thenReturn(new AgentToolActionControlledDryRunResult(
                        true,
                        9101L,
                        9205L,
                        "taoc-consume-001",
                        "quality.remediation.task.draft",
                        "EXECUTION_SUCCEEDED",
                        true,
                        true,
                        "质量治理任务已受控提交",
                        Map.of("qualityRemediationSubmitCandidate", true),
                        List.of("已写入 command worker receipt"),
                        AgentToolActionControlledReceiptDelivery.skipped("由执行服务负责 receipt")
                ));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals("EXECUTION_SUCCEEDED", result.outcome());
        assertTrue(result.sideEffectExecuted());
        verify(executionService).execute(eq(task), eq(9205L), any(AgentToolActionControlledTaskPayload.class),
                any(TaskActorContext.class), any());
        verify(taskService, never()).deferTask(any(), any(), any(), any());
        verify(taskService, never()).failTask(any(), any(), any());
        verify(receiptClient, never()).publishDryRunReceipt(
                any(), any(), any(), eq(false), any(), any(), any(), any()
        );
    }

    @Test
    void shouldFailTaskWhenPayloadStoreEvidenceIsMissingBeforeApprovalCheck() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentRuntimeToolActionControlledReceiptClient receiptClient = receiptClient();
        PermissionAdminAgentToolActionApprovalClient approvalClient = approvedApprovalClient();
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, metricsService, receiptClient, approvalClient);
        Task task = controlledTask(List.of(
                "REFERENCE_PREFIX:agent-payload",
                "RUN_ID_BOUND:run-proposal"
        ));
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", task, run(9202L)));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals("FAILED_PRECHECK", result.outcome());
        assertEquals(false, result.preCheckPassed());
        assertFalse(result.sideEffectExecuted());
        assertTrue(result.receiptDelivery().accepted());
        verify(taskService).failTask(eq(9101L), any(String.class), any(TaskExecutionCallbackContext.class));
        verify(taskService, never()).deferTask(any(), any(), any(), any());
        verify(metricsService).recordDispatchOutcome("FAILED_PRECHECK");
        verify(approvalClient, never()).evaluate(any(AgentToolActionControlledApprovalEvaluationRequest.class));
        verify(receiptClient).publishDryRunReceipt(
                any(AgentToolActionControlledTaskPayload.class),
                eq(9202L),
                eq("FAILED_PRECHECK"),
                eq(false),
                any(String.class),
                eq("AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED"),
                any(),
                any(TaskActorContext.class)
        );
    }

    @Test
    void shouldDeferWhenApprovalFactIsPending() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentRuntimeToolActionControlledReceiptClient receiptClient = receiptClient();
        PermissionAdminAgentToolActionApprovalClient approvalClient = approvalClient(new AgentToolActionControlledApprovalEvaluationResult(
                "approval:human-001",
                false,
                true,
                "PENDING",
                "审批事实仍在等待人工确认",
                "PENDING",
                "tool-readiness-policy.v1",
                List.of("APPROVAL_FACT_FOUND"),
                List.of("APPROVAL_FACT_PENDING")
        ));
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, metricsService, receiptClient, approvalClient);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", controlledTaskWithValidEvidence(), run(9203L)));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals("DEFERRED_WAITING_APPROVAL_FACT", result.outcome());
        assertFalse(result.preCheckPassed());
        assertTrue(result.message().contains("等待 permission-admin 审批事实"));
        verify(taskService).deferTask(eq(9101L), any(String.class), eq(120), any(TaskExecutionCallbackContext.class));
        verify(taskService, never()).failTask(any(), any(), any());
        verify(metricsService).recordDispatchOutcome("DEFERRED_WAITING_APPROVAL_FACT");
        verify(receiptClient).publishDryRunReceipt(
                any(AgentToolActionControlledTaskPayload.class),
                eq(9203L),
                eq("DEFERRED_WAITING_APPROVAL_FACT"),
                eq(false),
                any(String.class),
                isNull(),
                any(),
                any(TaskActorContext.class)
        );
    }

    @Test
    void shouldFailWhenApprovalFactIsRejected() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentRuntimeToolActionControlledReceiptClient receiptClient = receiptClient();
        PermissionAdminAgentToolActionApprovalClient approvalClient = approvalClient(new AgentToolActionControlledApprovalEvaluationResult(
                "approval:human-001",
                false,
                false,
                "REJECTED",
                "审批人拒绝该工具动作",
                "REJECTED",
                "tool-readiness-policy.v1",
                List.of("APPROVAL_FACT_FOUND"),
                List.of("APPROVAL_FACT_REJECTED")
        ));
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, metricsService, receiptClient, approvalClient);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", controlledTaskWithValidEvidence(), run(9204L)));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals("FAILED_PRECHECK", result.outcome());
        assertFalse(result.preCheckPassed());
        assertTrue(result.message().contains("审批事实拒绝"));
        verify(taskService).failTask(eq(9101L), any(String.class), any(TaskExecutionCallbackContext.class));
        verify(taskService, never()).deferTask(any(), any(), any(), any());
        verify(metricsService).recordDispatchOutcome("FAILED_PRECHECK");
        verify(receiptClient).publishDryRunReceipt(
                any(AgentToolActionControlledTaskPayload.class),
                eq(9204L),
                eq("FAILED_PRECHECK"),
                eq(false),
                any(String.class),
                eq("AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED"),
                any(),
                any(TaskActorContext.class)
        );
    }

    @Test
    void shouldReturnNoTaskWhenQueueIsEmpty() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentRuntimeToolActionControlledReceiptClient receiptClient = receiptClient();
        PermissionAdminAgentToolActionApprovalClient approvalClient = approvedApprovalClient();
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, metricsService, receiptClient, approvalClient);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(false, "当前没有可认领任务", null, null));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals(false, result.claimed());
        assertEquals("NO_TASK", result.outcome());
        assertFalse(result.receiptDelivery().attempted());
        verify(metricsService).recordDispatchOutcome("NO_TASK");
        verify(approvalClient, never()).evaluate(any(AgentToolActionControlledApprovalEvaluationRequest.class));
        verify(receiptClient, never()).publishDryRunReceipt(
                any(), any(), any(), eq(false), any(), any(), any(), any()
        );
    }

    @Test
    void disabledDryRunShouldRejectBeforeClaimingTask() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        properties.setControlledActionDryRunEnabled(false);
        AgentRuntimeToolActionControlledReceiptClient receiptClient = receiptClient();
        PermissionAdminAgentToolActionApprovalClient approvalClient = approvedApprovalClient();
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, mock(AgentAsyncToolWorkerMetricsService.class), receiptClient, approvalClient);

        assertThrows(IllegalStateException.class, () -> service.dispatchDryRunOnce(actorContext()));

        verify(taskService, never()).claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class));
        verify(approvalClient, never()).evaluate(any(AgentToolActionControlledApprovalEvaluationRequest.class));
        verify(receiptClient, never()).publishDryRunReceipt(any(), any(), any(), eq(false), any(), any(), any(), any());
    }

    private AgentToolActionControlledDryRunDispatcherService service(TaskService taskService,
                                                                    AgentAsyncToolWorkerProperties properties,
                                                                    AgentAsyncToolWorkerMetricsService metricsService,
                                                                    AgentRuntimeToolActionControlledReceiptClient receiptClient,
                                                                    PermissionAdminAgentToolActionApprovalClient approvalClient) {
        return service(taskService, properties, metricsService, receiptClient, approvalClient,
                mock(AgentToolActionControlledQualityRemediationExecutionService.class));
    }

    private AgentToolActionControlledDryRunDispatcherService service(TaskService taskService,
                                                                    AgentAsyncToolWorkerProperties properties,
                                                                    AgentAsyncToolWorkerMetricsService metricsService,
                                                                    AgentRuntimeToolActionControlledReceiptClient receiptClient,
                                                                    PermissionAdminAgentToolActionApprovalClient approvalClient,
                                                                    AgentToolActionControlledQualityRemediationExecutionService executionService) {
        return new AgentToolActionControlledDryRunDispatcherService(
                taskService,
                new AgentToolActionControlledPayloadResolver(objectMapper),
                properties,
                new AgentAsyncToolWorkerAdmissionGuardService(properties),
                metricsService,
                receiptClient,
                approvalClient,
                executionService
        );
    }

    private AgentRuntimeToolActionControlledReceiptClient receiptClient() {
        AgentRuntimeToolActionControlledReceiptClient receiptClient =
                mock(AgentRuntimeToolActionControlledReceiptClient.class);
        when(receiptClient.publishDryRunReceipt(
                any(AgentToolActionControlledTaskPayload.class),
                any(),
                any(String.class),
                any(Boolean.class),
                any(String.class),
                any(),
                any(),
                any(TaskActorContext.class)
        )).thenReturn(new AgentToolActionControlledReceiptDelivery(
                true,
                true,
                false,
                "controlled-tool-action-receipt:test",
                "agent.tool_execution.controlled_dry_run_receipt_recorded",
                "receipt accepted"
        ));
        return receiptClient;
    }

    private PermissionAdminAgentToolActionApprovalClient approvedApprovalClient() {
        return approvalClient(new AgentToolActionControlledApprovalEvaluationResult(
                "approval:human-001",
                true,
                false,
                "APPROVED",
                "审批事实已批准",
                "APPROVED",
                "tool-readiness-policy.v1",
                List.of("APPROVAL_FACT_FOUND", "APPROVAL_FACT_SCOPE_VERIFIED", "APPROVAL_FACT_STATUS_APPROVED"),
                List.of()
        ));
    }

    private PermissionAdminAgentToolActionApprovalClient approvalClient(
            AgentToolActionControlledApprovalEvaluationResult result) {
        PermissionAdminAgentToolActionApprovalClient approvalClient =
                mock(PermissionAdminAgentToolActionApprovalClient.class);
        when(approvalClient.evaluate(any(AgentToolActionControlledApprovalEvaluationRequest.class))).thenReturn(result);
        return approvalClient;
    }

    private AgentAsyncToolWorkerProperties properties() {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setControlledActionDryRunEnabled(true);
        properties.setControlledActionDryRunDeferSeconds(120);
        properties.setExecutorId("agent-worker-test");
        properties.setClaimLeaseSeconds(60);
        properties.setMaxLocalConcurrentExecutions(1);
        return properties;
    }

    private Task controlledTaskWithValidEvidence() {
        return controlledTask(List.of(
                "REFERENCE_PREFIX:agent-payload",
                "RUN_ID_BOUND:run-proposal",
                "AGENT_PAYLOAD_RECORD_FOUND",
                "AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED",
                "PAYLOAD_BODY_NOT_MATERIALIZED"
        ));
    }

    private Task controlledQualityTaskWithBodyAvailable() {
        return controlledTask(List.of(
                "REFERENCE_PREFIX:agent-payload",
                "RUN_ID_BOUND:run-proposal",
                "AGENT_PAYLOAD_RECORD_FOUND",
                "AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED",
                "PAYLOAD_BODY_AVAILABLE"
        ), "quality.remediation.task.draft", "quality-remediation-task-draft:audit-001");
    }

    private Task controlledTask(List<String> delegationEvidence) {
        return controlledTask(delegationEvidence, "datasource.metadata.read", "datasource-metadata-read");
    }

    private Task controlledTask(List<String> delegationEvidence, String toolCode, String payloadKey) {
        Task task = new Task();
        task.setId(9101L);
        task.setType(AgentAsyncTaskCommandContractSupport.TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED);
        task.setStatus(TaskStatus.RUNNING);
        task.setTenantId(10L);
        task.setProjectId(20L);
        task.setParams(toJson(params(delegationEvidence, toolCode, payloadKey)));
        return task;
    }

    private Map<String, Object> params(List<String> delegationEvidence, String toolCode, String payloadKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("schemaVersion", "datasmart.agent.async-task-command.v1");
        params.put("commandId", "taoc-consume-001");
        params.put("commandType", "AGENT_TOOL_ACTION_CONTROLLED_COMMAND");
        params.put("commandKind", "TOOL_ACTION_CONTROLLED");
        params.put("auditId", "tool-action:graph-contract-hash");
        params.put("sessionId", "session-proposal");
        params.put("runId", "run-proposal");
        params.put("toolCode", toolCode);
        params.put("targetService", "agent-runtime");
        params.put("targetEndpoint", null);
        params.put("workspaceId", null);
        params.put("actorId", "30");
        params.put("payloadReference", "agent-payload:run-proposal/" + payloadKey);
        params.put("payloadReferenceType", "AGENT_PAYLOAD");
        params.put("workerDispatchEnabled", false);
        params.put("argumentNames", List.of());
        params.put("sensitiveArgumentNames", List.of());
        params.put("confirmationId", "approval:human-001");
        params.put("policyVersions", List.of("tool-readiness-policy.v1"));
        params.put("delegationEvidence", delegationEvidence);
        return params;
    }

    private TaskExecutionRun run(Long id) {
        TaskExecutionRun run = new TaskExecutionRun();
        run.setId(id);
        run.setTaskId(9101L);
        return run;
    }

    private TaskActorContext actorContext() {
        return new TaskActorContext(10L, null, "SERVICE_ACCOUNT", "trace-controlled-dry-run-test", null, List.of());
    }

    private void verifyClaimedControlledTask(TaskService taskService) {
        /*
         * 这里不只验证 claimNextTask 被调用，还要验证认领请求的 taskType。
         * 原因是 task-management 现在同时存在两条工具链路：
         * 1. AGENT_ASYNC_TOOL：历史 worker，会读取 agent-tool-audit:// 参数并可能真实执行白名单工具；
         * 2. AGENT_TOOL_ACTION_CONTROLLED：新控制面任务，只允许 dry-run/pre-check，暂时不能真实执行副作用。
         * 如果未来维护者误把 taskType 改回历史类型，测试会立即失败，从而保护“新受控工具动作不能被旧 worker 误执行”的边界。
         */
        ArgumentCaptor<TaskExecutionClaimRequest> requestCaptor =
                ArgumentCaptor.forClass(TaskExecutionClaimRequest.class);
        verify(taskService).claimNextTask(requestCaptor.capture(), any(TaskActorContext.class));
        TaskExecutionClaimRequest request = requestCaptor.getValue();
        assertEquals(AgentAsyncTaskCommandContractSupport.TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED, request.getTaskType());
        assertTrue(request.getExecutorId().endsWith("-tool-action-controlled-dry-run"));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
