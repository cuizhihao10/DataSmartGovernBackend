/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxDispatchServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSync worker command outbox 调度查询服务测试。
 *
 * <p>这组测试不连接真实 MySQL，而是用 Mapper mock 验证服务层的关键业务语义：</p>
 * <p>1. claim 会把可领取命令推进到 DISPATCHING，并递增 attemptCount；</p>
 * <p>2. 条件更新失败时，说明命令可能被其他 dispatcher 抢先领取，本服务不会把它返回给当前 dispatcher；</p>
 * <p>3. diagnostics 只返回低敏视图，不返回 payloadJson 或 lastError 正文；</p>
 * <p>4. 状态过滤会做枚举校验，避免任意字符串进入查询语义。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataSyncWorkerCommandOutboxDispatchServiceTest {

    @Mock
    private DataSyncWorkerCommandOutboxMapper mapper;

    private DataSyncWorkerCommandOutboxDispatchService service;

    @BeforeEach
    void setUp() {
        service = new DataSyncWorkerCommandOutboxDispatchService(mapper);
    }

    @Test
    void claimShouldMovePendingAndDeferredCommandsToDispatching() {
        DataSyncWorkerCommandOutbox pending = outbox(1L, "cmd-001", DataSyncWorkerCommandOutboxStatus.PENDING);
        DataSyncWorkerCommandOutbox deferred = outbox(2L, "cmd-002", DataSyncWorkerCommandOutboxStatus.DEFERRED);
        deferred.setNextRetryAt(LocalDateTime.now().minusSeconds(5));
        deferred.setAttemptCount(2);
        when(mapper.selectList(any())).thenReturn(List.of(pending, deferred));
        when(mapper.update(any(DataSyncWorkerCommandOutbox.class), any())).thenReturn(1);

        DataSyncWorkerOutboxClaimResult result = service.claimDispatchCandidates(new DataSyncWorkerOutboxClaimRequest(
                "dispatcher-001",
                10L,
                20L,
                50,
                true
        ));

        assertEquals(2, result.claimedCount());
        assertEquals(50, result.effectiveLimit());
        assertEquals(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name(), result.candidates().get(0).status());
        assertEquals(1, result.candidates().get(0).attemptCount());
        assertEquals(3, result.candidates().get(1).attemptCount());
        verify(mapper, times(2)).update(any(DataSyncWorkerCommandOutbox.class), any());
    }

    @Test
    void claimShouldSkipCommandWhenConditionalUpdateLosesConcurrencyRace() {
        DataSyncWorkerCommandOutbox pending = outbox(1L, "cmd-001", DataSyncWorkerCommandOutboxStatus.PENDING);
        when(mapper.selectList(any())).thenReturn(List.of(pending));
        when(mapper.update(any(DataSyncWorkerCommandOutbox.class), any())).thenReturn(0);

        DataSyncWorkerOutboxClaimResult result = service.claimDispatchCandidates(new DataSyncWorkerOutboxClaimRequest(
                "dispatcher-001",
                null,
                null,
                20,
                true
        ));

        assertEquals(0, result.claimedCount());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("并发领取竞争失败")));
    }

    @Test
    void claimShouldRejectMissingExecutorId() {
        DataSyncWorkerOutboxClaimRequest request = new DataSyncWorkerOutboxClaimRequest(
                " ",
                null,
                null,
                10,
                true
        );

        assertThrows(IllegalArgumentException.class, () -> service.claimDispatchCandidates(request));
    }

    @Test
    void diagnosticsShouldReturnCountsAndHidePayloadAndErrorBody() {
        DataSyncWorkerCommandOutbox failed = outbox(3L, "cmd-003", DataSyncWorkerCommandOutboxStatus.FAILED);
        failed.setPayloadJson("{\"sql\":\"select * from user_secret\",\"password\":\"secret\"}");
        failed.setLastError("password=secret; select * from user_secret");
        when(mapper.selectCount(any())).thenReturn(3L, 1L, 1L, 0L, 0L, 1L, 0L, 0L);
        when(mapper.selectList(any())).thenReturn(List.of(failed));

        DataSyncWorkerOutboxDiagnosticsResult result = service.diagnose(new DataSyncWorkerOutboxDiagnosticsRequest(
                10L,
                20L,
                null,
                null,
                null,
                100
        ));

        assertEquals(3L, result.totalCount());
        assertEquals(1L, result.statusCounts().get(DataSyncWorkerCommandOutboxStatus.FAILED.name()));
        assertEquals(1, result.recentRecords().size());
        assertTrue(result.recentRecords().get(0).hasLastError());
        assertEquals("ERROR_SUMMARY_BODY_STORED_BUT_NOT_EXPOSED", result.recentRecords().get(0).errorVisibilityPolicy());
        String responseText = result.toString();
        assertFalse(responseText.contains("select * from user_secret"));
        assertFalse(responseText.contains("password=secret"));
        assertFalse(responseText.contains("payloadJson"));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("FAILED")));
    }

    @Test
    void diagnosticsShouldNormalizeStatusAndLimitRecentRecords() {
        DataSyncWorkerCommandOutbox succeeded = outbox(4L, "cmd-004", DataSyncWorkerCommandOutboxStatus.SUCCEEDED);
        when(mapper.selectCount(any())).thenReturn(1L, 0L, 0L, 0L, 1L, 0L, 0L, 0L);
        when(mapper.selectList(any())).thenReturn(List.of(succeeded));

        DataSyncWorkerOutboxDiagnosticsResult result = service.diagnose(new DataSyncWorkerOutboxDiagnosticsRequest(
                null,
                null,
                9001L,
                "cmd-004",
                "succeeded",
                5000
        ));

        assertEquals(DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name(), result.requestedStatus());
        assertEquals(1, result.recentRecords().size());
        verify(mapper, times(8)).selectCount(any());
    }

    @Test
    void claimShouldWriteOnlyClaimFieldsDuringConditionalUpdate() {
        DataSyncWorkerCommandOutbox pending = outbox(1L, "cmd-001", DataSyncWorkerCommandOutboxStatus.PENDING);
        when(mapper.selectList(any())).thenReturn(List.of(pending));
        when(mapper.update(any(DataSyncWorkerCommandOutbox.class), any())).thenReturn(1);

        service.claimDispatchCandidates(new DataSyncWorkerOutboxClaimRequest(
                "dispatcher-001",
                null,
                null,
                1,
                false
        ));

        ArgumentCaptor<DataSyncWorkerCommandOutbox> updateCaptor =
                ArgumentCaptor.forClass(DataSyncWorkerCommandOutbox.class);
        verify(mapper).update(updateCaptor.capture(), any());
        DataSyncWorkerCommandOutbox updateEntity = updateCaptor.getValue();
        assertEquals(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name(), updateEntity.getStatus());
        assertEquals(1, updateEntity.getAttemptCount());
        assertTrue(updateEntity.getDispatchedAt() != null);
        assertTrue(updateEntity.getPayloadJson() == null);
    }

    private DataSyncWorkerCommandOutbox outbox(Long id,
                                               String commandId,
                                               DataSyncWorkerCommandOutboxStatus status) {
        DataSyncWorkerCommandOutbox outbox = new DataSyncWorkerCommandOutbox();
        outbox.setId(id);
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
        outbox.setStatus(status.name());
        outbox.setAttemptCount(0);
        outbox.setPayloadJson("{\"commandId\":\"" + commandId + "\"}");
        outbox.setPayloadSizeBytes(24);
        outbox.setPayloadTruncated(false);
        outbox.setSideEffectStarted(false);
        outbox.setSideEffectExecuted(false);
        outbox.setCreateTime(LocalDateTime.now().minusMinutes(5));
        outbox.setUpdateTime(LocalDateTime.now().minusMinutes(1));
        return outbox;
    }
}
