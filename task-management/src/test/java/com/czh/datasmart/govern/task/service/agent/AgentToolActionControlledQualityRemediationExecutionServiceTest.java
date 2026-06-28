/**
 * @Author : Cui
 * @Date: 2026/06/28 21:30
 * @Description DataSmart Govern Backend - AgentToolActionControlledQualityRemediationExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandContractSupport;
import com.czh.datasmart.govern.task.support.TaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质量治理受控动作真实提交服务测试。
 *
 * <p>这里不测试 data-quality 的业务逻辑，也不展开 agent-runtime payload body。测试重点是 task-management
 * 在进入真实副作用前后是否遵守控制面顺序：先领取 command worker lease，再调用 Host submit，再写
 * command worker receipt，最后才把 task-management 任务标记成功。这样可以防止未来维护者为了“少一次 HTTP”
 * 而绕过 lease 或 receipt，破坏 Agent 执行闭环。</p>
 */
class AgentToolActionControlledQualityRemediationExecutionServiceTest {

    @Test
    void shouldSubmitQualityRemediationAndCompleteTaskAfterReceiptAccepted() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentRuntimeCommandWorkerLeaseClient leaseClient = mock(AgentRuntimeCommandWorkerLeaseClient.class);
        AgentRuntimeQualityRemediationSubmitClient submitClient = mock(AgentRuntimeQualityRemediationSubmitClient.class);
        AgentRuntimeToolActionCommandWorkerReceiptClient receiptClient =
                mock(AgentRuntimeToolActionCommandWorkerReceiptClient.class);
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentToolActionControlledQualityRemediationExecutionService service =
                new AgentToolActionControlledQualityRemediationExecutionService(
                        taskService,
                        properties,
                        leaseClient,
                        submitClient,
                        receiptClient,
                        metricsService
                );
        Task task = task();
        AgentToolActionControlledTaskPayload payload = payload();
        TaskActorContext actorContext = actorContext();
        AgentRuntimeCommandWorkerLeaseClaimResponse lease = lease();
        when(leaseClient.claim(eq(payload), anyString(), eq(actorContext))).thenReturn(lease);
        when(submitClient.submit(eq(payload), eq(9201L), anyString(), eq(actorContext)))
                .thenReturn(submitResponse(true));
        when(receiptClient.publish(eq(payload), any(AgentToolActionCommandWorkerReceiptRequest.class), eq(actorContext)))
                .thenReturn(receiptDelivery());

        AgentToolActionControlledDryRunResult result =
                service.execute(task, 9201L, payload, actorContext, Map.of("payloadBodyAvailable", true));

        assertEquals("EXECUTION_SUCCEEDED", result.outcome());
        assertTrue(result.preCheckPassed());
        assertTrue(result.sideEffectExecuted());
        assertEquals(7001L, result.diagnostics().get("downstreamTaskId"));
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TaskExecutionCallbackContext> callbackCaptor =
                ArgumentCaptor.forClass(TaskExecutionCallbackContext.class);
        verify(taskService).completeTask(eq(9101L), resultCaptor.capture(), callbackCaptor.capture());
        assertTrue(resultCaptor.getValue().contains("downstreamTaskId=7001"));
        assertFalse(resultCaptor.getValue().toLowerCase().contains("payloadpreview"));
        assertEquals("agent-worker-test-tool-action-controlled-dry-run", callbackCaptor.getValue().executorId());
        assertEquals("agent-tool-action-controlled:taoc-consume-001:EXECUTION_SUCCEEDED",
                callbackCaptor.getValue().idempotencyKey());
        verify(taskService, never()).deferTask(any(), any(), any(), any());
        verify(taskService, never()).failTask(any(), any(), any());
        verify(leaseClient).release(eq(payload), eq("agent-worker-test-tool-action-controlled-dry-run"),
                eq(lease), eq("COMPLETED"), eq(actorContext));
        verify(metricsService).recordDispatchOutcome("EXECUTION_SUCCEEDED");

        ArgumentCaptor<AgentToolActionCommandWorkerReceiptRequest> receiptCaptor =
                ArgumentCaptor.forClass(AgentToolActionCommandWorkerReceiptRequest.class);
        verify(receiptClient).publish(eq(payload), receiptCaptor.capture(), eq(actorContext));
        AgentToolActionCommandWorkerReceiptRequest receipt = receiptCaptor.getValue();
        assertEquals("EXECUTION_SUCCEEDED", receipt.outcome());
        assertEquals("ALLOW_CONTROLLED_EXECUTION", receipt.commandSafetyDecision());
        assertEquals("fencing-token-001", receipt.fencingToken());
        assertEquals(1L, receipt.workerLeaseVersion());
        assertTrue(receipt.sideEffectStarted());
        assertTrue(receipt.sideEffectExecuted());
        assertEquals("TASK_MANAGEMENT_TASK", receipt.artifactReferenceType());
        assertEquals("task-artifact:quality-remediation-task:7001", receipt.artifactReference());
    }

    @Test
    void shouldDeferWithoutSubmitWhenCommandLeaseIsNotAcquired() {
        TaskService taskService = mock(TaskService.class);
        AgentAsyncToolWorkerProperties properties = properties();
        AgentRuntimeCommandWorkerLeaseClient leaseClient = mock(AgentRuntimeCommandWorkerLeaseClient.class);
        AgentRuntimeQualityRemediationSubmitClient submitClient = mock(AgentRuntimeQualityRemediationSubmitClient.class);
        AgentRuntimeToolActionCommandWorkerReceiptClient receiptClient =
                mock(AgentRuntimeToolActionCommandWorkerReceiptClient.class);
        AgentAsyncToolWorkerMetricsService metricsService = mock(AgentAsyncToolWorkerMetricsService.class);
        AgentToolActionControlledQualityRemediationExecutionService service =
                new AgentToolActionControlledQualityRemediationExecutionService(
                        taskService,
                        properties,
                        leaseClient,
                        submitClient,
                        receiptClient,
                        metricsService
                );
        AgentToolActionControlledTaskPayload payload = payload();
        TaskActorContext actorContext = actorContext();
        when(leaseClient.claim(eq(payload), anyString(), eq(actorContext)))
                .thenReturn(new AgentRuntimeCommandWorkerLeaseClaimResponse(
                        false,
                        "HELD_BY_OTHER_WORKER",
                        "taoc-consume-001",
                        "other-worker",
                        null,
                        null,
                        null,
                        "其它 worker 正在处理该 command"
                ));

        AgentToolActionControlledDryRunResult result =
                service.execute(task(), 9202L, payload, actorContext, Map.of());

        assertEquals("DEFERRED_WAITING_COMMAND_LEASE", result.outcome());
        assertTrue(result.preCheckPassed());
        assertFalse(result.sideEffectExecuted());
        verify(submitClient, never()).submit(any(), any(), any(), any());
        verify(receiptClient, never()).publish(any(), any(), any());
        verify(taskService).deferTask(eq(9101L), any(String.class), eq(120),
                any(TaskExecutionCallbackContext.class));
        verify(metricsService).recordDispatchOutcome("DEFERRED_WAITING_COMMAND_LEASE");
    }

    private AgentAsyncToolWorkerProperties properties() {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setExecutorId("agent-worker-test");
        properties.setControlledActionDryRunDeferSeconds(120);
        properties.setPreCheckUnavailableDeferSeconds(30);
        properties.setControlledActionSubmitTimeoutMs(3000L);
        return properties;
    }

    private Task task() {
        Task task = new Task();
        task.setId(9101L);
        task.setType(AgentAsyncTaskCommandContractSupport.TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED);
        task.setStatus(TaskStatus.RUNNING);
        task.setTenantId(10L);
        task.setProjectId(20L);
        return task;
    }

    private AgentToolActionControlledTaskPayload payload() {
        return new AgentToolActionControlledTaskPayload(
                9101L,
                TaskStatus.RUNNING,
                AgentAsyncTaskCommandContractSupport.TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED,
                10L,
                20L,
                "30",
                "taoc-consume-001",
                "AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
                "TOOL_ACTION_CONTROLLED",
                "tool-action:quality-remediation",
                "session-proposal",
                "run-proposal",
                "quality.remediation.task.draft",
                "agent-runtime",
                null,
                null,
                "agent-payload:run-proposal/quality-remediation-task-draft:audit-001",
                "AGENT_PAYLOAD",
                "quality-remediation-task-draft:audit-001",
                false,
                List.of(),
                List.of(),
                "approval:human-001",
                List.of("tool-readiness-policy.v1"),
                List.of(
                        "REFERENCE_PREFIX:agent-payload",
                        "RUN_ID_BOUND:run-proposal",
                        "AGENT_PAYLOAD_RECORD_FOUND",
                        "AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED",
                        "PAYLOAD_BODY_AVAILABLE"
                ),
                LocalDateTime.now()
        );
    }

    private TaskActorContext actorContext() {
        return new TaskActorContext(10L, 30L, "SERVICE_ACCOUNT", "trace-quality-submit-test", null, List.of());
    }

    private AgentRuntimeCommandWorkerLeaseClaimResponse lease() {
        return new AgentRuntimeCommandWorkerLeaseClaimResponse(
                true,
                "ACQUIRED",
                "taoc-consume-001",
                "agent-worker-test-tool-action-controlled-dry-run",
                "fencing-token-001",
                1L,
                1_786_000_000_000L,
                "lease acquired"
        );
    }

    private AgentToolActionQualityRemediationSubmitResponse submitResponse(boolean executed) {
        return new AgentToolActionQualityRemediationSubmitResponse(
                true,
                false,
                true,
                executed,
                executed ? "EXECUTION_SUCCEEDED" : "EXECUTION_SKIPPED",
                "taoc-consume-001",
                "agent-payload:run-proposal/quality-remediation-task-draft:audit-001",
                "approval:human-001",
                executed ? 7001L : null,
                executed ? "PENDING" : null,
                executed ? "质量治理任务已受控提交" : "质量治理任务未提交",
                executed ? List.of() : List.of("DATA_QUALITY_REMEDIATION_NOT_SUBMITTED"),
                executed ? List.of("等待下游 task-management 执行质量治理任务。") : List.of("检查 data-quality 配置。")
        );
    }

    private AgentToolActionControlledReceiptDelivery receiptDelivery() {
        return new AgentToolActionControlledReceiptDelivery(
                true,
                true,
                false,
                "command-worker-receipt:taoc-consume-001:EXECUTION_SUCCEEDED:9201",
                "agent.tool_execution.command_worker_receipt_recorded",
                "receipt accepted"
        );
    }
}
