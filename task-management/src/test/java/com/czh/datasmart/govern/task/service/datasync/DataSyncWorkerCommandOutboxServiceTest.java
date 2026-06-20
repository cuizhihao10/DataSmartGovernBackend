/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSync worker command outbox 服务测试。
 *
 * <p>这组测试不连接真实 MySQL，而是用 Mock 验证 Service 的业务语义：
 * - 首次命令写入 PENDING outbox；
 * - 重复命令复用已有记录，不重复创建；
 * - DISPATCHING 会递增 attemptCount；
 * - 成功 receipt 只写下游 ID 和低敏状态；
 * - 已成功的终态不会被重复 worker 回退成 DISPATCHING。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataSyncWorkerCommandOutboxServiceTest {

    @Mock
    private DataSyncWorkerCommandOutboxMapper mapper;

    private DataSyncWorkerCommandOutboxService service;

    @BeforeEach
    void setUp() {
        service = new DataSyncWorkerCommandOutboxService(mapper, new ObjectMapper());
    }

    @Test
    void stageCommandShouldInsertLowSensitivePendingOutbox() {
        when(mapper.insert(any(DataSyncWorkerCommandOutbox.class))).thenAnswer(invocation -> {
            DataSyncWorkerCommandOutbox outbox = invocation.getArgument(0);
            outbox.setId(1L);
            return 1;
        });

        DataSyncWorkerCommandOutboxSnapshot snapshot = service.stageCommand(stageRequest());

        assertEquals("task-datasync:cmd-001", snapshot.outboxId());
        assertEquals("cmd-001", snapshot.commandId());
        assertEquals(DataSyncWorkerCommandOutboxStatus.PENDING.name(), snapshot.status());
        assertFalse(snapshot.duplicate());

        ArgumentCaptor<DataSyncWorkerCommandOutbox> captor = ArgumentCaptor.forClass(DataSyncWorkerCommandOutbox.class);
        verify(mapper).insert(captor.capture());
        DataSyncWorkerCommandOutbox inserted = captor.getValue();
        assertEquals(0, inserted.getAttemptCount());
        assertEquals(false, inserted.getPayloadTruncated());
        assertEquals(false, inserted.getSideEffectStarted());
        assertTrue(inserted.getPayloadJson().contains("\"syncTemplateId\":6001"));
        assertTrue(inserted.getPayloadJson().contains("\"operation\":\"DATA_SYNC_EXECUTE\""));
        assertFalse(inserted.getPayloadJson().contains("password"));
        assertFalse(inserted.getPayloadJson().contains("select *"));
    }

    @Test
    void duplicateCommandShouldReuseExistingOutbox() {
        DataSyncWorkerCommandOutbox existing = existingOutbox(DataSyncWorkerCommandOutboxStatus.PENDING);
        when(mapper.insert(any(DataSyncWorkerCommandOutbox.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectOne(any())).thenReturn(existing);

        DataSyncWorkerCommandOutboxSnapshot snapshot = service.stageCommand(stageRequest());

        assertTrue(snapshot.duplicate());
        assertEquals("task-datasync:cmd-001", snapshot.outboxId());
        assertEquals(DataSyncWorkerCommandOutboxStatus.PENDING.name(), snapshot.status());
        verify(mapper, never()).updateById(any(DataSyncWorkerCommandOutbox.class));
    }

    @Test
    void markDispatchingShouldIncreaseAttemptCount() {
        DataSyncWorkerCommandOutbox existing = existingOutbox(DataSyncWorkerCommandOutboxStatus.PENDING);
        existing.setAttemptCount(2);
        when(mapper.selectOne(any())).thenReturn(existing);

        DataSyncWorkerCommandOutboxSnapshot snapshot = service.markDispatching("cmd-001");

        assertEquals(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name(), snapshot.status());
        assertEquals(3, snapshot.attemptCount());
        verify(mapper).updateById(existing);
    }

    @Test
    void markDispatchingShouldNotRollbackSucceededTerminalOutbox() {
        DataSyncWorkerCommandOutbox existing = existingOutbox(DataSyncWorkerCommandOutboxStatus.SUCCEEDED);
        existing.setAttemptCount(1);
        existing.setReceiptId("receipt-001");
        when(mapper.selectOne(any())).thenReturn(existing);

        DataSyncWorkerCommandOutboxSnapshot snapshot = service.markDispatching("cmd-001");

        assertTrue(snapshot.duplicate());
        assertEquals(DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name(), snapshot.status());
        assertEquals("receipt-001", snapshot.receiptId());
        verify(mapper, never()).updateById(any(DataSyncWorkerCommandOutbox.class));
    }

    @Test
    void recordSuccessShouldPersistReceiptAndDownstreamReferences() {
        DataSyncWorkerCommandOutbox existing = existingOutbox(DataSyncWorkerCommandOutboxStatus.DISPATCHING);
        when(mapper.selectOne(any())).thenReturn(existing);

        DataSyncWorkerCommandOutboxSnapshot snapshot = service.recordSuccess(new DataSyncWorkerReceiptRecordRequest(
                "cmd-001",
                "receipt-001",
                7001L,
                8001L,
                "QUEUED",
                true,
                true,
                false,
                "已入队"
        ));

        assertEquals(DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name(), snapshot.status());
        assertEquals("receipt-001", snapshot.receiptId());
        assertEquals(7001L, snapshot.syncTaskId());
        assertEquals(8001L, snapshot.syncExecutionId());
        assertEquals(true, existing.getSideEffectStarted());
        assertEquals(true, existing.getSideEffectExecuted());
        verify(mapper).updateById(existing);
    }

    @Test
    void missingTemplateShouldBeRejectedBeforeInsert() {
        DataSyncWorkerCommandStageRequest request = stageRequest();
        request.setTemplateId(null);
        request.setSyncTemplateId(null);

        assertThrows(IllegalArgumentException.class, () -> service.stageCommand(request));

        verify(mapper, never()).insert(any(DataSyncWorkerCommandOutbox.class));
    }

    private DataSyncWorkerCommandStageRequest stageRequest() {
        return new DataSyncWorkerCommandStageRequest(
                "cmd-001",
                "agent-async-tool:cmd-001",
                9001L,
                "session-001",
                "run-001",
                "audit-001",
                "data-sync.execute",
                "data-sync",
                DataSyncWorkerCommandOutboxService.DEFAULT_OPERATION,
                10L,
                20L,
                30L,
                "1001",
                "trace-001",
                null,
                6001L,
                "HIGH",
                "INCREMENTAL",
                1001L
        );
    }

    private DataSyncWorkerCommandOutbox existingOutbox(DataSyncWorkerCommandOutboxStatus status) {
        DataSyncWorkerCommandOutbox outbox = new DataSyncWorkerCommandOutbox();
        outbox.setId(1L);
        outbox.setOutboxId("task-datasync:cmd-001");
        outbox.setCommandId("cmd-001");
        outbox.setIdempotencyKey("agent-async-tool:cmd-001");
        outbox.setTaskId(9001L);
        outbox.setAgentRunId("run-001");
        outbox.setToolCode("data-sync.execute");
        outbox.setTargetService("data-sync");
        outbox.setStatus(status.name());
        outbox.setAttemptCount(0);
        outbox.setPayloadJson("{}");
        outbox.setPayloadSizeBytes(2);
        outbox.setPayloadTruncated(false);
        outbox.setSideEffectStarted(false);
        outbox.setSideEffectExecuted(false);
        return outbox;
    }
}
