/**
 * @Author : Cui
 * @Date: 2026/06/27 01:24
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackDispatchServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Agent command 最终态回调投递服务测试。
 *
 * <p>这组测试保护 5.113 的核心闭环：最终态对账建议可以被显式映射为 task-management 执行器回调，
 * 但默认必须 dry-run，且缺少 taskId/taskRunId/executorId 时不能猜测投递。这样既能把 Agent Host
 * 与任务中心闭环连接起来，又不会把补偿接口变成“随意改任务状态”的后门。</p>
 */
class AgentCommandTaskFinalStateCallbackDispatchServiceTest {

    @Test
    void shouldDryRunByDefaultWithoutCallingTaskManagement() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentCommandTaskFinalStateCallbackDispatchService service = service(builder, storeWith(successReceipt()));

        AgentCommandTaskFinalStateCallbackDispatchResponse response = service.dispatch(
                request(null),
                platformAccess(),
                "trace-final-dispatch"
        );

        assertEquals("DRY_RUN_READY", response.deliveryStatus());
        assertEquals(false, response.dispatchAttempted());
        assertEquals(false, response.dispatched());
        assertEquals("TASK_COMPLETE", response.targetOperation());
        assertEquals("SUCCEEDED", response.callbackStatus());
        assertEquals(9001L, response.taskId());
        assertTrue(response.recommendedActions().stream().anyMatch(action -> action.contains("dry-run")));
        server.verify();
    }

    @Test
    void shouldDispatchSucceededReceiptToTaskCompleteCallback() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentCommandTaskFinalStateCallbackDispatchService service = service(builder, storeWith(successReceipt()));

        server.expect(once(), requestTo("http://task-management.test/tasks/9001/complete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime"))
                .andExpect(header(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT"))
                .andExpect(header(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT"))
                .andExpect(header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "20"))
                .andExpect(jsonPath("$.runId").value(9101))
                .andExpect(jsonPath("$.executorId").value("worker-final-001"))
                .andExpect(jsonPath("$.idempotencyKey").value(
                        "agent-command-final-state:CMD-FINAL-001:SUCCEEDED:12"))
                .andExpect(jsonPath("$.result").isString())
                .andRespond(withSuccess("""
                        {"code":0,"message":"任务完成成功","data":{"id":9001,"status":"SUCCESS"}}
                        """, MediaType.APPLICATION_JSON));

        AgentCommandTaskFinalStateCallbackDispatchResponse response = service.dispatch(
                request(false),
                platformAccess(),
                "trace-final-dispatch"
        );

        assertEquals("DISPATCHED", response.deliveryStatus());
        assertEquals(true, response.dispatchAttempted());
        assertEquals(true, response.dispatched());
        assertEquals(true, response.downstreamAccepted());
        assertEquals("任务完成成功", response.downstreamMessage());
        server.verify();
    }

    @Test
    void shouldDispatchFailedReceiptToTaskFailCallbackWithLowSensitiveMessage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentCommandTaskFinalStateCallbackDispatchService service = service(builder, storeWith(failedReceipt()));

        server.expect(once(), requestTo("http://task-management.test/tasks/9001/fail"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.runId").value(9101))
                .andExpect(jsonPath("$.executorId").value("worker-final-001"))
                .andExpect(jsonPath("$.idempotencyKey").value(
                        "agent-command-final-state:CMD-FINAL-001:FAILED:22"))
                .andExpect(jsonPath("$.errorMessage").value(
                        "受控命令 worker 已写回执行失败 receipt，建议把 Agent 异步任务推进到失败并进入补偿排障。"))
                .andRespond(withSuccess("""
                        {"code":0,"message":"任务失败状态已记录","data":{"id":9001,"status":"FAILED"}}
                        """, MediaType.APPLICATION_JSON));

        AgentCommandTaskFinalStateCallbackDispatchResponse response = service.dispatch(
                request(false),
                platformAccess(),
                "trace-final-dispatch"
        );

        assertEquals("DISPATCHED", response.deliveryStatus());
        assertEquals("TASK_FAIL", response.targetOperation());
        assertEquals("FAILED", response.callbackStatus());
        assertTrue(response.reconciliation().requiresManualCompensation());
        server.verify();
    }

    @Test
    void shouldSkipDispatchWhenReceiptDoesNotContainTaskLeaseFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentCommandTaskFinalStateCallbackDispatchService service = service(builder, storeWith(legacyReceiptWithoutLease()));

        AgentCommandTaskFinalStateCallbackDispatchRequest request = request(false);
        AgentCommandTaskFinalStateCallbackDispatchResponse response = service.dispatch(
                request,
                platformAccess(),
                "trace-final-dispatch"
        );

        assertEquals("SKIPPED_CALLBACK_LINK_INCOMPLETE", response.deliveryStatus());
        assertEquals(false, response.dispatchAttempted());
        assertEquals(false, response.dispatched());
        assertNull(response.taskId());
        assertTrue(response.issueCodes().contains("TASK_CALLBACK_LINK_INCOMPLETE"));
        server.verify();
    }

    @Test
    void shouldNotExposeDownstreamUrlWhenTaskManagementCallFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentCommandTaskFinalStateCallbackDispatchService service = service(builder, storeWith(successReceipt()));

        server.expect(once(), requestTo("http://task-management.test/tasks/9001/complete"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":500,"message":"POST http://internal-task-service/tasks/9001/complete failed","data":null}
                        """, MediaType.APPLICATION_JSON));

        AgentCommandTaskFinalStateCallbackDispatchResponse response = service.dispatch(
                request(false),
                platformAccess(),
                "trace-final-dispatch"
        );

        assertEquals("FAILED_DOWNSTREAM_REJECTED", response.deliveryStatus());
        assertFalse(response.dispatched());
        assertFalse(response.downstreamMessage().contains("http://internal-task-service"));
        assertTrue(response.issueCodes().contains("TASK_MANAGEMENT_REJECTED_FINAL_STATE_CALLBACK"));
        server.verify();
    }

    private AgentCommandTaskFinalStateCallbackDispatchService service(RestClient.Builder builder,
                                                                      InMemoryAgentToolActionWorkerReceiptIndexStore store) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("task-management", "http://task-management.test");
        return new AgentCommandTaskFinalStateCallbackDispatchService(
                properties,
                builder,
                new AgentCommandTaskFinalStateReconciliationService(
                        new AgentToolActionWorkerReceiptIndexService(store),
                        new AgentRuntimeEventProjectionAccessSupport()
                )
        );
    }

    private InMemoryAgentToolActionWorkerReceiptIndexStore storeWith(AgentToolActionWorkerReceiptIndexRecord receipt) {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt);
        return store;
    }

    private AgentCommandTaskFinalStateCallbackDispatchRequest request(Boolean dryRun) {
        AgentCommandTaskFinalStateCallbackDispatchRequest request = new AgentCommandTaskFinalStateCallbackDispatchRequest();
        request.setCommandId("cmd-final-001");
        request.setToolCode("command.run-program");
        request.setTenantId("10");
        request.setProjectId("20");
        request.setActorId("1001");
        request.setRunId("run-final");
        request.setSessionId("session-final");
        request.setLimit(20);
        request.setDryRun(dryRun);
        return request;
    }

    private AgentRuntimeEventQueryAccessContext platformAccess() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PLATFORM_ADMINISTRATOR",
                "trace-final-dispatch",
                "PLATFORM",
                List.of()
        );
    }

    private AgentToolActionWorkerReceiptIndexRecord successReceipt() {
        return receipt("receipt-success", "EXECUTION_SUCCEEDED", "SUCCEEDED", true, true,
                "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED", 12L);
    }

    private AgentToolActionWorkerReceiptIndexRecord failedReceipt() {
        return receipt("receipt-failed", "EXECUTION_FAILED", "FAILED", true, false,
                "AGENT_COMMAND_WORKER_EXECUTION_FAILED", 22L);
    }

    private AgentToolActionWorkerReceiptIndexRecord legacyReceiptWithoutLease() {
        return new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-legacy",
                "cmd-final-001",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                "command.run-program",
                "SUCCEEDED",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED",
                32L,
                Instant.parse("2026-06-27T01:00:32Z"),
                Instant.parse("2026-06-27T01:10:32Z")
        );
    }

    private AgentToolActionWorkerReceiptIndexRecord receipt(String identityKey,
                                                            String outcome,
                                                            String taskStatus,
                                                            boolean preCheckPassed,
                                                            boolean sideEffectExecuted,
                                                            String errorCode,
                                                            long replaySequence) {
        return new AgentToolActionWorkerReceiptIndexRecord(
                identityKey,
                "cmd-final-001",
                9001L,
                9101L,
                "worker-final-001",
                "audit-final-001",
                "10",
                "20",
                "1001",
                "run-final",
                "session-final",
                "command.run-program",
                taskStatus,
                outcome,
                preCheckPassed,
                sideEffectExecuted,
                errorCode,
                replaySequence,
                Instant.parse("2026-06-27T01:00:00Z").plusSeconds(replaySequence),
                Instant.parse("2026-06-27T01:10:00Z").plusSeconds(replaySequence)
        );
    }
}
