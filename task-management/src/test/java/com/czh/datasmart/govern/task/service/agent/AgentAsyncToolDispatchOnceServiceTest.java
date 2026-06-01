/**
 * @Author : Cui
 * @Date: 2026/05/31 23:55
 * @Description DataSmart Govern Backend - AgentAsyncToolDispatchOnceServiceTest.java
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 异步工具单次调度服务测试。
 *
 * <p>该测试使用假的白名单适配器，聚焦 worker 编排本身：必须先认领 AGENT_ASYNC_TOOL 任务，
 * 再解析 payload，最后根据适配器结果调用 complete/defer/fail。真实 data-sync HTTP 调用由适配器自己的测试或集成测试覆盖。</p>
 */
class AgentAsyncToolDispatchOnceServiceTest {

    @Test
    void shouldClaimResolveExecuteAndCompleteTask() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolPayloadResolver resolver = mock(AgentAsyncToolPayloadResolver.class);
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setEnabled(true);
        properties.setDryRunOnly(false);
        properties.setExecutorId("agent-worker-test");
        AgentAsyncToolExecutor executor = new FakeExecutor();
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class))).thenReturn(confirmationView());
        AgentRuntimeAsyncToolStatusClient statusClient = mock(AgentRuntimeAsyncToolStatusClient.class);
        AgentAsyncToolDispatchOnceService service = new AgentAsyncToolDispatchOnceService(
                taskService,
                resolver,
                new AgentAsyncToolExecutionPreCheckService(List.of(executor), properties, confirmationClient),
                List.of(executor),
                statusClient,
                properties,
                new ObjectMapper()
        );
        Task task = new Task();
        task.setId(9001L);
        TaskExecutionRun run = new TaskExecutionRun();
        run.setId(9101L);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", task, run));
        when(resolver.resolve(eq(task), eq("trace-worker"))).thenReturn(payload());
        TaskActorContext actorContext = new TaskActorContext(10L, null, "SERVICE_ACCOUNT", "trace-worker", null, List.of());

        AgentAsyncToolDispatchOnceResult result = service.dispatchOnce(actorContext);

        assertEquals("COMPLETED", result.outcome());
        assertEquals(9001L, result.taskId());
        assertEquals(9101L, result.runId());
        assertEquals("data-sync.execute", result.toolCode());
        verify(taskService).completeTask(eq(9001L), any(String.class), any(TaskExecutionCallbackContext.class));
        verify(statusClient).notifyStatus(any(AgentAsyncToolResolvedPayload.class), eq(9101L),
                eq("RUNNING"), any(String.class), eq(null), any(Map.class));
        verify(statusClient).notifyStatus(any(AgentAsyncToolResolvedPayload.class), eq(9101L),
                eq("SUCCEEDED"), any(String.class), eq(null), any(Map.class));
    }

    @Test
    void preCheckRejectionShouldFailTaskBeforeExecutorSideEffect() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolPayloadResolver resolver = mock(AgentAsyncToolPayloadResolver.class);
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setEnabled(true);
        properties.setDryRunOnly(false);
        properties.setExecutorId("agent-worker-test");
        AgentAsyncToolExecutor executor = mock(AgentAsyncToolExecutor.class);
        when(executor.supports("data-sync.execute")).thenReturn(true);
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        AgentRuntimeAsyncToolStatusClient statusClient = mock(AgentRuntimeAsyncToolStatusClient.class);
        AgentAsyncToolDispatchOnceService service = new AgentAsyncToolDispatchOnceService(
                taskService,
                resolver,
                new AgentAsyncToolExecutionPreCheckService(List.of(executor), properties, confirmationClient),
                List.of(executor),
                statusClient,
                properties,
                new ObjectMapper()
        );
        Task task = new Task();
        task.setId(9001L);
        TaskExecutionRun run = new TaskExecutionRun();
        run.setId(9101L);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", task, run));
        when(resolver.resolve(eq(task), eq("trace-worker"))).thenReturn(payloadWithAuditState("WAITING_APPROVAL"));
        TaskActorContext actorContext = new TaskActorContext(10L, null, "SERVICE_ACCOUNT", "trace-worker", null, List.of());

        AgentAsyncToolDispatchOnceResult result = service.dispatchOnce(actorContext);

        assertEquals("FAILED", result.outcome());
        verify(statusClient).notifyStatus(any(AgentAsyncToolResolvedPayload.class), eq(9101L),
                eq("FAILED"), any(String.class), eq("AGENT_ASYNC_TOOL_PRECHECK_REJECTED"), any(Map.class));
        verify(taskService).failTask(eq(9001L), any(String.class), any(TaskExecutionCallbackContext.class));
        verify(executor, never()).execute(any(AgentAsyncToolResolvedPayload.class));
    }

    @Test
    void preCheckDependencyUnavailableShouldDeferTaskWithoutExecutorSideEffect() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolPayloadResolver resolver = mock(AgentAsyncToolPayloadResolver.class);
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setEnabled(true);
        properties.setDryRunOnly(false);
        properties.setExecutorId("agent-worker-test");
        properties.setPreCheckUnavailableDeferSeconds(45);
        AgentAsyncToolExecutor executor = mock(AgentAsyncToolExecutor.class);
        when(executor.supports("data-sync.execute")).thenReturn(true);
        AgentRuntimeToolDagConfirmationClient confirmationClient = mock(AgentRuntimeToolDagConfirmationClient.class);
        when(confirmationClient.fetchConfirmation(any(AgentAsyncToolResolvedPayload.class)))
                .thenThrow(new IllegalStateException("agent-runtime unavailable"));
        AgentRuntimeAsyncToolStatusClient statusClient = mock(AgentRuntimeAsyncToolStatusClient.class);
        AgentAsyncToolDispatchOnceService service = new AgentAsyncToolDispatchOnceService(
                taskService,
                resolver,
                new AgentAsyncToolExecutionPreCheckService(List.of(executor), properties, confirmationClient),
                List.of(executor),
                statusClient,
                properties,
                new ObjectMapper()
        );
        Task task = new Task();
        task.setId(9001L);
        TaskExecutionRun run = new TaskExecutionRun();
        run.setId(9101L);
        when(taskService.claimNextTask(any(TaskExecutionClaimRequest.class), any(TaskActorContext.class)))
                .thenReturn(new TaskExecutionClaimResult(true, "claimed", task, run));
        when(resolver.resolve(eq(task), eq("trace-worker"))).thenReturn(payload());
        TaskActorContext actorContext = new TaskActorContext(10L, null, "SERVICE_ACCOUNT", "trace-worker", null, List.of());

        AgentAsyncToolDispatchOnceResult result = service.dispatchOnce(actorContext);

        assertEquals("DEFERRED", result.outcome());
        verify(taskService).deferTask(eq(9001L), any(String.class), eq(45), any(TaskExecutionCallbackContext.class));
        verify(statusClient, never()).notifyStatus(any(AgentAsyncToolResolvedPayload.class), any(), any(), any(), any(), any());
        verify(executor, never()).execute(any(AgentAsyncToolResolvedPayload.class));
    }

    private AgentAsyncToolResolvedPayload payload() {
        return payloadWithAuditState("PLANNED");
    }

    private AgentAsyncToolResolvedPayload payloadWithAuditState(String auditState) {
        return new AgentAsyncToolResolvedPayload(
                9001L,
                "RUNNING",
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
                "dag-confirmation:test-001",
                List.of("route-policy:860"),
                List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-agent"),
                Map.of("syncTemplateId", 6001L),
                Map.of(),
                Map.of(),
                List.of("预检通过"),
                List.of(),
                LocalDateTime.now()
        );
    }

    private AgentRuntimeToolDagConfirmationView confirmationView() {
        return new AgentRuntimeToolDagConfirmationView(
                "dag-confirmation:test-001",
                "session-001",
                "run-001",
                "fingerprint-001",
                List.of("node-001"),
                List.of("audit-001"),
                List.of("route-policy:860"),
                List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-agent"),
                List.of("outbox-001"),
                List.of("cmd-001"),
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

    private static class FakeExecutor implements AgentAsyncToolExecutor {

        @Override
        public boolean supports(String toolCode) {
            return "data-sync.execute".equals(toolCode);
        }

        @Override
        public AgentAsyncToolExecutionResult execute(AgentAsyncToolResolvedPayload payload) {
            return AgentAsyncToolExecutionResult.success(
                    "data-sync 已入队",
                    Map.of("syncTaskId", 7001L, "syncExecutionId", 8001L)
            );
        }
    }
}
