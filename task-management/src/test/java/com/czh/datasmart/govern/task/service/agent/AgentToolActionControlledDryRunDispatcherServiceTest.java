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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受控工具动作 dry-run 调度器测试。
 *
 * <p>这些测试刻意不复用历史 AgentAsyncTool worker 的 fake executor，因为新链路当前不能执行真实工具。
 * 我们只验证：专用任务类型被认领、payload store 证据存在时任务被 defer、证据缺失时任务 fail、
 * 开关关闭时不会认领任务。这样可以保护“控制面任务不能被旧 worker 误执行”的产品边界。</p>
 */
class AgentToolActionControlledDryRunDispatcherServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldClaimControlledTaskAndDeferWhenPayloadBodyIsNotMaterialized() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentToolActionControlledDryRunDispatcherService service = service(taskService, properties, metricsService);
        Task task = controlledTask(List.of(
                "REFERENCE_PREFIX:agent-payload",
                "RUN_ID_BOUND:run-proposal",
                "AGENT_PAYLOAD_RECORD_FOUND",
                "AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED",
                "PAYLOAD_BODY_NOT_MATERIALIZED"
        ));
        TaskExecutionRun run = run(9201L);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", task, run));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals(true, result.claimed());
        assertEquals(9101L, result.taskId());
        assertEquals(9201L, result.runId());
        assertEquals("DEFERRED_WAITING_PAYLOAD_BODY", result.outcome());
        assertEquals(true, result.preCheckPassed());
        assertEquals(false, result.sideEffectExecuted());
        assertEquals("taoc-consume-001", result.commandId());

        verify(taskService).deferTask(eq(9101L), any(String.class), eq(120), any(TaskExecutionCallbackContext.class));
        verify(taskService, never()).failTask(any(), any(), any());
        verify(metricsService).recordDispatchOutcome("DEFERRED_WAITING_PAYLOAD_BODY");
        verifyClaimedControlledTask(taskService);
    }

    @Test
    void shouldFailTaskWhenPayloadStoreEvidenceIsMissing() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentToolActionControlledDryRunDispatcherService service = service(taskService, properties, metricsService);
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
        verify(taskService).failTask(eq(9101L), any(String.class), any(TaskExecutionCallbackContext.class));
        verify(taskService, never()).deferTask(any(), any(), any(), any());
        verify(metricsService).recordDispatchOutcome("FAILED_PRECHECK");
    }

    @Test
    void shouldReturnNoTaskWhenQueueIsEmpty() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentToolActionControlledDryRunDispatcherService service = service(taskService, properties, metricsService);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(false, "当前没有可认领任务", null, null));

        AgentToolActionControlledDryRunResult result = service.dispatchDryRunOnce(actorContext());

        assertEquals(false, result.claimed());
        assertEquals("NO_TASK", result.outcome());
        verify(metricsService).recordDispatchOutcome("NO_TASK");
    }

    @Test
    void disabledDryRunShouldRejectBeforeClaimingTask() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        properties.setControlledActionDryRunEnabled(false);
        AgentToolActionControlledDryRunDispatcherService service =
                service(taskService, properties, mock(AgentAsyncToolWorkerMetricsService.class));

        assertThrows(IllegalStateException.class, () -> service.dispatchDryRunOnce(actorContext()));

        verify(taskService, never()).claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class));
    }

    private AgentToolActionControlledDryRunDispatcherService service(TaskService taskService,
                                                                    AgentAsyncToolWorkerProperties properties,
                                                                    AgentAsyncToolWorkerMetricsService metricsService) {
        return new AgentToolActionControlledDryRunDispatcherService(
                taskService,
                new AgentToolActionControlledPayloadResolver(objectMapper),
                properties,
                new AgentAsyncToolWorkerAdmissionGuardService(properties),
                metricsService
        );
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

    private Task controlledTask(List<String> delegationEvidence) {
        Task task = new Task();
        task.setId(9101L);
        task.setType(AgentAsyncTaskCommandContractSupport.TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED);
        task.setStatus(TaskStatus.RUNNING);
        task.setTenantId(10L);
        task.setProjectId(20L);
        task.setParams(toJson(params(delegationEvidence)));
        return task;
    }

    private Map<String, Object> params(List<String> delegationEvidence) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("schemaVersion", "datasmart.agent.async-task-command.v1");
        params.put("commandId", "taoc-consume-001");
        params.put("commandType", "AGENT_TOOL_ACTION_CONTROLLED_COMMAND");
        params.put("commandKind", "TOOL_ACTION_CONTROLLED");
        params.put("auditId", "tool-action:graph-contract-hash");
        params.put("sessionId", "session-proposal");
        params.put("runId", "run-proposal");
        params.put("toolCode", "datasource.metadata.read");
        params.put("targetService", "agent-runtime");
        params.put("targetEndpoint", null);
        params.put("workspaceId", null);
        params.put("payloadReference", "agent-payload:run-proposal/datasource-metadata-read");
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
