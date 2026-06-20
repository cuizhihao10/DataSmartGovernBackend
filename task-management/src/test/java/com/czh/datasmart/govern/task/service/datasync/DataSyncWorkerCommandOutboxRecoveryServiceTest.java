/**
 * @Author : Cui
 * @Date: 2026/06/20 23:35
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxRecoveryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSync worker outbox 超时恢复服务测试。
 *
 * <p>这组测试不连接真实 MySQL，而是通过 mapper mock 验证恢复服务的业务语义：</p>
 * <p>1. stale DISPATCHING 未达到最大尝试次数时恢复为 DEFERRED，后续 dispatcher 可以在 nextRetryAt 后重新领取；</p>
 * <p>2. stale DISPATCHING 已达到最大尝试次数时进入 DEAD_LETTER，避免无限重试和资源消耗；</p>
 * <p>3. 条件更新返回 0 时说明并发线程已经改变状态，恢复器必须跳过，不能覆盖成功 receipt 或新的 dispatchedAt；</p>
 * <p>4. 恢复结果只返回低敏视图，不泄露 payloadJson、SQL、连接串、工具实参或 lastError 正文。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataSyncWorkerCommandOutboxRecoveryServiceTest {

    @Mock
    private DataSyncWorkerCommandOutboxMapper mapper;

    private AgentAsyncToolWorkerProperties properties;
    private DataSyncWorkerCommandOutboxRecoveryService service;

    @BeforeEach
    void setUp() {
        properties = new AgentAsyncToolWorkerProperties();
        properties.setDataSyncOutboxMaxAttempts(3);
        properties.setDataSyncOutboxDispatchingTimeoutSeconds(60);
        properties.setDataSyncOutboxStaleRecoveryRetryAfterSeconds(45);
        service = new DataSyncWorkerCommandOutboxRecoveryService(mapper, properties);
    }

    @Test
    void recoverStaleDispatchingShouldMoveCommandBackToDeferredBeforeMaxAttempts() {
        DataSyncWorkerCommandOutbox stale = outbox("cmd-001", 1);
        stale.setPayloadJson("{\"sql\":\"select * from secret_table\",\"password\":\"secret\"}");
        stale.setLastError("jdbc:mysql://internal-host:3306/db password=secret");
        when(mapper.selectList(any())).thenReturn(List.of(stale));
        when(mapper.update(any(DataSyncWorkerCommandOutbox.class), any())).thenReturn(1);

        DataSyncWorkerOutboxRecoveryResult result = service.recoverStaleDispatching(new DataSyncWorkerOutboxRecoveryRequest(
                "recovery-worker-001",
                10L,
                20L,
                10
        ));

        assertEquals(1, result.scannedCount());
        assertEquals(1, result.recoveredCount());
        assertEquals(1, result.deferredCount());
        assertEquals(0, result.deadLetterCount());
        assertEquals(DataSyncWorkerCommandOutboxStatus.DEFERRED.name(), stale.getStatus());
        assertNotNull(stale.getNextRetryAt());
        assertTrue(stale.getSideEffectStarted() == Boolean.TRUE);
        assertFalse(stale.getSideEffectExecuted());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("DEFERRED")));

        String responseText = result.toString();
        assertFalse(responseText.contains("select * from secret_table"));
        assertFalse(responseText.contains("password=secret"));
        assertFalse(responseText.contains("jdbc:mysql"));

        ArgumentCaptor<DataSyncWorkerCommandOutbox> updateCaptor =
                ArgumentCaptor.forClass(DataSyncWorkerCommandOutbox.class);
        verify(mapper).update(updateCaptor.capture(), any());
        DataSyncWorkerCommandOutbox updateEntity = updateCaptor.getValue();
        assertEquals(DataSyncWorkerCommandOutboxStatus.DEFERRED.name(), updateEntity.getStatus());
        assertNotNull(updateEntity.getNextRetryAt());
        assertNull(updateEntity.getPayloadJson());
    }

    @Test
    void recoverStaleDispatchingShouldMoveCommandToDeadLetterWhenAttemptsReachLimit() {
        DataSyncWorkerCommandOutbox stale = outbox("cmd-002", 3);
        when(mapper.selectList(any())).thenReturn(List.of(stale));
        when(mapper.update(any(DataSyncWorkerCommandOutbox.class), any())).thenReturn(1);

        DataSyncWorkerOutboxRecoveryResult result = service.recoverStaleDispatching(new DataSyncWorkerOutboxRecoveryRequest(
                "recovery-worker-001",
                null,
                null,
                10
        ));

        assertEquals(1, result.recoveredCount());
        assertEquals(0, result.deferredCount());
        assertEquals(1, result.deadLetterCount());
        assertEquals(DataSyncWorkerCommandOutboxStatus.DEAD_LETTER.name(), stale.getStatus());
        assertNull(stale.getNextRetryAt());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("DEAD_LETTER")));
    }

    @Test
    void recoverStaleDispatchingShouldSkipWhenConditionalUpdateLosesRace() {
        DataSyncWorkerCommandOutbox stale = outbox("cmd-003", 1);
        when(mapper.selectList(any())).thenReturn(List.of(stale));
        when(mapper.update(any(DataSyncWorkerCommandOutbox.class), any())).thenReturn(0);

        DataSyncWorkerOutboxRecoveryResult result = service.recoverStaleDispatching(new DataSyncWorkerOutboxRecoveryRequest(
                "recovery-worker-001",
                null,
                null,
                10
        ));

        assertEquals(1, result.scannedCount());
        assertEquals(0, result.recoveredCount());
        assertEquals(1, result.skippedCount());
        assertEquals(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name(), stale.getStatus());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("状态或 dispatchedAt 已被其他线程更新")));
    }

    @Test
    void recoverStaleDispatchingShouldRejectMissingExecutorId() {
        DataSyncWorkerOutboxRecoveryRequest request = new DataSyncWorkerOutboxRecoveryRequest(
                " ",
                null,
                null,
                10
        );

        assertThrows(IllegalArgumentException.class, () -> service.recoverStaleDispatching(request));
    }

    private DataSyncWorkerCommandOutbox outbox(String commandId, int attemptCount) {
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
        outbox.setSyncTemplateId(6001L);
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name());
        outbox.setAttemptCount(attemptCount);
        outbox.setPayloadJson("{\"commandId\":\"" + commandId + "\"}");
        outbox.setPayloadSizeBytes(24);
        outbox.setPayloadTruncated(false);
        outbox.setDispatchedAt(LocalDateTime.now().minusMinutes(10));
        outbox.setSideEffectStarted(true);
        outbox.setSideEffectExecuted(false);
        outbox.setCreateTime(LocalDateTime.now().minusMinutes(15));
        outbox.setUpdateTime(LocalDateTime.now().minusMinutes(10));
        return outbox;
    }
}
