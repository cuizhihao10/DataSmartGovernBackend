/**
 * @Author : Cui
 * @Date: 2026/06/20 21:43
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandDeliveryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteRequest;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteResponse;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSync worker command delivery 服务测试。
 *
 * <p>这组测试不访问真实 datasource-management，而是用 fake client 验证 task-management 本地状态账本。
 * 这样可以把“跨服务调用是否成功”与“本地 outbox 如何推进状态”分开测试，避免单元测试依赖网络和端口。</p>
 *
 * <p>覆盖重点：</p>
 * <p>1. 单条命令会先进入 DISPATCHING，再在下游成功后记录 SUCCEEDED receipt；</p>
 * <p>2. batch dispatch 已经经过 claim，不应该再重复递增 attemptCount；</p>
 * <p>3. 临时网络异常会进入 DEFERRED，并且错误摘要不能泄露内部 URL；</p>
 * <p>4. outbox payload_json 只用于内部白名单字段还原，不会进入对外结果。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataSyncWorkerCommandDeliveryServiceTest {

    @Mock
    private DataSyncWorkerCommandOutboxMapper mapper;

    private FakeDataSyncAgentExecuteClient executeClient;
    private DataSyncWorkerCommandDeliveryService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        DataSyncWorkerCommandOutboxService outboxService =
                new DataSyncWorkerCommandOutboxService(mapper, objectMapper);
        DataSyncWorkerCommandOutboxDispatchService claimService =
                new DataSyncWorkerCommandOutboxDispatchService(mapper);
        executeClient = new FakeDataSyncAgentExecuteClient();
        service = new DataSyncWorkerCommandDeliveryService(
                mapper,
                outboxService,
                claimService,
                executeClient,
                objectMapper
        );
    }

    @Test
    void deliverCommandShouldMarkDispatchingAndRecordSuccessReceipt() {
        DataSyncWorkerCommandOutbox outbox = outbox("cmd-001", DataSyncWorkerCommandOutboxStatus.PENDING);
        when(mapper.selectOne(any())).thenReturn(outbox, outbox, outbox);
        when(mapper.updateById(any(DataSyncWorkerCommandOutbox.class))).thenReturn(1);
        executeClient.response = new DataSyncAgentExecuteResponse(
                "cmd-001",
                7001L,
                null,
                "QUEUED",
                true,
                true,
                false,
                "DataSync 同步任务已入队"
        );

        DataSyncWorkerCommandDeliveryResult result = service.deliverCommand("cmd-001");

        assertTrue(result.success());
        assertEquals(DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name(), result.status());
        assertEquals("SUCCEEDED", result.outcome());
        assertEquals(7001L, result.syncTaskId());
        assertEquals("HIGH", executeClient.capturedRequest.getPriority());
        assertEquals("MANUAL", executeClient.capturedRequest.getRunMode());
        assertEquals(1000L, executeClient.capturedRequest.getOwnerId());
        assertFalse(result.toAgentOutput().toString().contains("select * from secret_table"));
        verify(mapper, times(2)).updateById(any(DataSyncWorkerCommandOutbox.class));
    }

    @Test
    void dispatchBatchShouldClaimThenDeliverWithoutDoubleAttemptIncrement() {
        DataSyncWorkerCommandOutbox outbox = outbox("cmd-002", DataSyncWorkerCommandOutboxStatus.PENDING);
        when(mapper.selectList(any())).thenReturn(List.of(outbox));
        when(mapper.update(any(DataSyncWorkerCommandOutbox.class), any())).thenReturn(1);
        when(mapper.selectOne(any())).thenReturn(outbox, outbox);
        when(mapper.updateById(any(DataSyncWorkerCommandOutbox.class))).thenReturn(1);
        executeClient.response = new DataSyncAgentExecuteResponse(
                "cmd-002",
                7002L,
                8002L,
                "QUEUED",
                true,
                true,
                false,
                "DataSync 同步任务已入队"
        );

        DataSyncWorkerOutboxDispatchBatchResult result = service.dispatchBatch(
                new DataSyncWorkerOutboxDispatchBatchRequest(
                        "dispatcher-001",
                        10L,
                        20L,
                        10,
                        true
                )
        );

        assertEquals(1, result.claimedCount());
        assertEquals(1, result.succeededCount());
        assertEquals(0, result.deferredCount());
        assertEquals(0, result.failedCount());

        ArgumentCaptor<DataSyncWorkerCommandOutbox> claimCaptor =
                ArgumentCaptor.forClass(DataSyncWorkerCommandOutbox.class);
        verify(mapper).update(claimCaptor.capture(), any());
        assertEquals(1, claimCaptor.getValue().getAttemptCount());
        verify(mapper, times(1)).updateById(any(DataSyncWorkerCommandOutbox.class));
    }

    @Test
    void deliverCommandShouldDeferWhenDataSyncIsTemporarilyUnavailableAndHideEndpoint() {
        DataSyncWorkerCommandOutbox outbox = outbox("cmd-003", DataSyncWorkerCommandOutboxStatus.PENDING);
        when(mapper.selectOne(any())).thenReturn(outbox, outbox, outbox);
        when(mapper.updateById(any(DataSyncWorkerCommandOutbox.class))).thenReturn(1);
        executeClient.exception = new RestClientException(
                "POST http://localhost:8086/internal/data-sync/agent/tasks/execute failed"
        );

        DataSyncWorkerCommandDeliveryResult result = service.deliverCommand("cmd-003");

        assertFalse(result.success());
        assertTrue(result.retryable());
        assertEquals(DataSyncWorkerCommandOutboxStatus.DEFERRED.name(), result.status());
        assertFalse(result.message().contains("localhost"));
        assertFalse(result.toAgentOutput().toString().contains("/internal/data-sync/agent/tasks/execute"));
        assertNotNull(outbox.getNextRetryAt());
        assertFalse(outbox.getLastError().contains("localhost"));
        verify(mapper, times(2)).updateById(any(DataSyncWorkerCommandOutbox.class));
    }

    private DataSyncWorkerCommandOutbox outbox(String commandId, DataSyncWorkerCommandOutboxStatus status) {
        DataSyncWorkerCommandOutbox outbox = new DataSyncWorkerCommandOutbox();
        outbox.setId(1L);
        outbox.setOutboxId("task-datasync:" + commandId);
        outbox.setCommandId(commandId);
        outbox.setIdempotencyKey("agent-async-tool:" + commandId);
        outbox.setTaskId(9001L);
        outbox.setAgentRunId("run-001");
        outbox.setAgentSessionId("session-001");
        outbox.setAuditId("audit-001");
        outbox.setToolCode("data-sync.execute");
        outbox.setTargetService("data-sync");
        outbox.setOperation("DATA_SYNC_EXECUTE");
        outbox.setTenantId(10L);
        outbox.setProjectId(20L);
        outbox.setWorkspaceId(30L);
        outbox.setActorId("1000");
        outbox.setTraceId("trace-001");
        outbox.setSyncTemplateId(6001L);
        outbox.setStatus(status.name());
        outbox.setAttemptCount(0);
        outbox.setPayloadJson("""
                {
                  "schemaVersion": "datasmart.task.data-sync-worker-command.v1",
                  "priority": "HIGH",
                  "runMode": "MANUAL",
                  "ownerId": 1000,
                  "sql": "select * from secret_table"
                }
                """);
        outbox.setPayloadSizeBytes(outbox.getPayloadJson().length());
        outbox.setPayloadTruncated(false);
        outbox.setSideEffectStarted(false);
        outbox.setSideEffectExecuted(false);
        outbox.setCreateTime(LocalDateTime.now().minusMinutes(5));
        outbox.setUpdateTime(LocalDateTime.now().minusMinutes(1));
        return outbox;
    }

    private static class FakeDataSyncAgentExecuteClient implements DataSyncAgentExecuteClient {

        private DataSyncAgentExecuteRequest capturedRequest;
        private DataSyncAgentExecuteResponse response;
        private RestClientException exception;

        @Override
        public DataSyncAgentExecuteResponse execute(DataSyncAgentExecuteRequest request) {
            this.capturedRequest = request;
            if (exception != null) {
                throw exception;
            }
            return response;
        }
    }
}
