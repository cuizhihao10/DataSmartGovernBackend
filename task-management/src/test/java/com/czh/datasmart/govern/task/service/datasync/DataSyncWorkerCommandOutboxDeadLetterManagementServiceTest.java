/**
 * @Author : Cui
 * @Date: 2026/06/21 00:00
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxDeadLetterManagementServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSync worker outbox 死信人工处置服务测试。
 *
 * <p>这组测试覆盖的是“运维控制面状态流转”，不是 datasource-management 的真实同步执行。
 * 服务层只把 DEAD_LETTER 改回 DEFERRED 或关闭为 CLOSED，后续是否真正执行仍由统一 dispatcher
 * 和下游幂等入口负责。</p>
 *
 * <p>测试重点：</p>
 * <p>1. REPLAY 必须重置 attemptCount，并设置 nextRetryAt，避免刚解除故障时瞬时打爆下游；</p>
 * <p>2. CLOSE 必须进入 CLOSED 终态，普通 dispatcher 不能再领取；</p>
 * <p>3. operator reason 即使包含 password、endpoint 或 SQL，也不能在响应或 lastError 中裸露；</p>
 * <p>4. 非 DEAD_LETTER 状态必须拒绝处置，避免误开终态命令。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataSyncWorkerCommandOutboxDeadLetterManagementServiceTest {

    @Mock
    private DataSyncWorkerCommandOutboxMapper mapper;

    private DataSyncWorkerCommandOutboxDeadLetterManagementService service;

    @BeforeEach
    void setUp() {
        service = new DataSyncWorkerCommandOutboxDeadLetterManagementService(mapper);
    }

    @Test
    void replayShouldReturnDeadLetterToDeferredAndHideSensitiveReason() {
        DataSyncWorkerCommandOutbox outbox = deadLetter("cmd-001");
        outbox.setAttemptCount(5);
        outbox.setSideEffectStarted(true);
        outbox.setDispatchedAt(LocalDateTime.now().minusMinutes(10));
        when(mapper.selectOne(any())).thenReturn(outbox);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DataSyncWorkerOutboxDeadLetterManageResult result = service.manage(
                new DataSyncWorkerOutboxDeadLetterManageRequest(
                        "operator-001",
                        "cmd-001",
                        DataSyncWorkerOutboxDeadLetterAction.REPLAY,
                        "下游已恢复 password=secret123 http://10.0.0.8/internal select * from user_secret",
                        45
                )
        );

        assertEquals(DataSyncWorkerCommandOutboxStatus.DEAD_LETTER.name(), result.previousStatus());
        assertEquals(DataSyncWorkerCommandOutboxStatus.DEFERRED.name(), result.currentStatus());
        assertTrue(result.replayScheduled());
        assertEquals(45, result.effectiveRetryAfterSeconds());
        assertEquals(DataSyncWorkerCommandOutboxStatus.DEFERRED.name(), outbox.getStatus());
        assertEquals(0, outbox.getAttemptCount());
        assertNotNull(outbox.getNextRetryAt());
        assertNull(outbox.getDispatchedAt());
        assertTrue(outbox.getSideEffectStarted());
        assertFalse(outbox.getSideEffectExecuted());
        assertTrue(result.record().hasLastError());
        assertEquals("ERROR_SUMMARY_BODY_STORED_BUT_NOT_EXPOSED", result.record().errorVisibilityPolicy());

        String responseText = result.toString();
        String storedError = outbox.getLastError();
        assertFalse(responseText.contains("secret123"));
        assertFalse(responseText.contains("http://10.0.0.8"));
        assertFalse(responseText.contains("select * from user_secret"));
        assertFalse(storedError.contains("secret123"));
        assertFalse(storedError.contains("http://10.0.0.8"));
        assertFalse(storedError.contains("select * from user_secret"));
        assertTrue(storedError.contains("password=<已隐藏>"));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("脱敏")));
        verify(mapper).update(isNull(), any());
    }

    @Test
    void closeShouldMoveDeadLetterToClosedTerminal() {
        DataSyncWorkerCommandOutbox outbox = deadLetter("cmd-002");
        outbox.setAttemptCount(4);
        outbox.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        when(mapper.selectOne(any())).thenReturn(outbox);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DataSyncWorkerOutboxDeadLetterManageResult result = service.manage(
                new DataSyncWorkerOutboxDeadLetterManageRequest(
                        "operator-002",
                        "cmd-002",
                        DataSyncWorkerOutboxDeadLetterAction.CLOSE,
                        "业务方确认废弃该同步任务",
                        null
                )
        );

        assertEquals(DataSyncWorkerCommandOutboxStatus.CLOSED.name(), result.currentStatus());
        assertFalse(result.replayScheduled());
        assertNull(result.effectiveRetryAfterSeconds());
        assertEquals(DataSyncWorkerCommandOutboxStatus.CLOSED.name(), outbox.getStatus());
        assertEquals(4, outbox.getAttemptCount());
        assertNull(outbox.getNextRetryAt());
        assertTrue(DataSyncWorkerCommandOutboxStatus.CLOSED.terminal());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("CLOSED")));
    }

    @Test
    void manageShouldRejectNonDeadLetterStatus() {
        DataSyncWorkerCommandOutbox outbox = deadLetter("cmd-003");
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.FAILED.name());
        when(mapper.selectOne(any())).thenReturn(outbox);

        DataSyncWorkerOutboxDeadLetterManageRequest request = new DataSyncWorkerOutboxDeadLetterManageRequest(
                "operator-003",
                "cmd-003",
                DataSyncWorkerOutboxDeadLetterAction.REPLAY,
                "误操作验证",
                30
        );

        assertThrows(IllegalStateException.class, () -> service.manage(request));
    }

    @Test
    void manageShouldRejectConcurrentStateChange() {
        DataSyncWorkerCommandOutbox outbox = deadLetter("cmd-004");
        when(mapper.selectOne(any())).thenReturn(outbox);
        when(mapper.update(isNull(), any())).thenReturn(0);

        DataSyncWorkerOutboxDeadLetterManageRequest request = new DataSyncWorkerOutboxDeadLetterManageRequest(
                "operator-004",
                "cmd-004",
                DataSyncWorkerOutboxDeadLetterAction.CLOSE,
                "并发竞争验证",
                null
        );

        assertThrows(IllegalStateException.class, () -> service.manage(request));
    }

    @Test
    void manageShouldRejectMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> service.manage(null));
        assertThrows(IllegalArgumentException.class, () -> service.manage(
                new DataSyncWorkerOutboxDeadLetterManageRequest(" ", "cmd-005", DataSyncWorkerOutboxDeadLetterAction.CLOSE, null, null)
        ));
        assertThrows(IllegalArgumentException.class, () -> service.manage(
                new DataSyncWorkerOutboxDeadLetterManageRequest("operator-005", " ", DataSyncWorkerOutboxDeadLetterAction.CLOSE, null, null)
        ));
        assertThrows(IllegalArgumentException.class, () -> service.manage(
                new DataSyncWorkerOutboxDeadLetterManageRequest("operator-005", "cmd-005", null, null, null)
        ));
    }

    private DataSyncWorkerCommandOutbox deadLetter(String commandId) {
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
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.DEAD_LETTER.name());
        outbox.setAttemptCount(3);
        outbox.setPayloadJson("{\"commandId\":\"" + commandId + "\"}");
        outbox.setPayloadSizeBytes(24);
        outbox.setPayloadTruncated(false);
        outbox.setSideEffectStarted(false);
        outbox.setSideEffectExecuted(false);
        outbox.setLastError("ERROR_SUMMARY_BODY_STORED_BUT_NOT_EXPOSED");
        outbox.setCreateTime(LocalDateTime.now().minusMinutes(15));
        outbox.setUpdateTime(LocalDateTime.now().minusMinutes(5));
        return outbox;
    }
}
