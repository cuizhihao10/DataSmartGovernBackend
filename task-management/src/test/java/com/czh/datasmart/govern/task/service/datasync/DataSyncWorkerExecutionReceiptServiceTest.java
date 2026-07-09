/**
 * @Author : Cui
 * @Date: 2026/06/22 10:40
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerExecutionReceipt;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerExecutionReceiptMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import com.czh.datasmart.govern.task.support.DataSyncWorkerExecutionReceiptEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSync worker 执行回执服务测试。
 *
 * <p>本测试聚焦 task-management 侧的“低敏执行投影”语义，不启动真实 MySQL，也不调用 datasource-management。
 * 这样可以把要验证的业务规则压得很清楚：</p>
 * <p>1. 执行回执必须关联已存在的 outbox，不能写孤立事实；</p>
 * <p>2. receiptId 必须具备幂等语义；</p>
 * <p>3. commandId 缺失时可以通过 syncTaskId/syncExecutionId 回查；</p>
 * <p>4. errorSummary/warnings 即使包含 endpoint、password、SQL，也不能在出站视图中裸露；</p>
 * <p>5. 查询接口只返回低敏执行历史，不返回错误正文和 warning 正文。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataSyncWorkerExecutionReceiptServiceTest {

    @Mock
    private DataSyncWorkerExecutionReceiptMapper receiptMapper;

    @Mock
    private DataSyncWorkerCommandOutboxMapper outboxMapper;

    private DataSyncWorkerExecutionReceiptService service;

    @BeforeEach
    void setUp() {
        service = new DataSyncWorkerExecutionReceiptService(receiptMapper, outboxMapper);
    }

    @Test
    void recordCheckpointReceiptShouldPersistLowSensitiveProjection() {
        DataSyncWorkerCommandOutbox outbox = outbox("cmd-001");
        when(outboxMapper.selectOne(any())).thenReturn(outbox);
        when(receiptMapper.insert(any(DataSyncWorkerExecutionReceipt.class))).thenAnswer(invocation -> {
            DataSyncWorkerExecutionReceipt receipt = invocation.getArgument(0);
            receipt.setId(1L);
            return 1;
        });

        DataSyncWorkerExecutionReceiptRecordResult result = service.recordReceipt(checkpointRequest("cmd-001"));

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        assertEquals(DataSyncWorkerExecutionReceiptEventType.CHECKPOINT.name(), result.record().eventType());
        assertEquals("cmd-001", result.record().commandId());
        assertEquals("task-datasync:cmd-001", result.record().outboxId());
        assertEquals(120L, result.record().totalRecordsRead());
        assertEquals(118L, result.record().totalRecordsWritten());
        assertTrue(result.record().checkpointPersisted());
        assertEquals("UPDATED_AT", result.record().checkpointType());
        assertEquals("HIDDEN_RAW_VALUE", result.record().checkpointValueVisibility());
        assertTrue(result.record().hasErrorSummary());
        assertEquals(2, result.record().warningCount());

        ArgumentCaptor<DataSyncWorkerExecutionReceipt> captor =
                ArgumentCaptor.forClass(DataSyncWorkerExecutionReceipt.class);
        verify(receiptMapper).insert(captor.capture());
        DataSyncWorkerExecutionReceipt inserted = captor.getValue();
        assertEquals("datasource-management", inserted.getSourceService());
        assertFalse(inserted.getErrorSummary().contains("secret123"));
        assertFalse(inserted.getErrorSummary().contains("jdbc:mysql://127.0.0.1:3306/order"));
        assertFalse(inserted.getWarningSummary().contains("select * from user_secret"));
        assertFalse(result.record().toString().contains("secret123"));
        assertFalse(result.record().toString().contains("jdbc:mysql://127.0.0.1:3306/order"));
        assertFalse(result.record().toString().contains("select * from user_secret"));
        assertTrue(result.record().detailVisibilityPolicy().contains("不返回 SQL"));
    }

    @Test
    void duplicateReceiptShouldReuseExistingProjection() {
        DataSyncWorkerCommandOutbox outbox = outbox("cmd-002");
        DataSyncWorkerExecutionReceipt existing = existingReceipt("receipt-duplicate", outbox);
        when(outboxMapper.selectOne(any())).thenReturn(outbox);
        when(receiptMapper.insert(any(DataSyncWorkerExecutionReceipt.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(receiptMapper.selectOne(any())).thenReturn(existing);

        DataSyncWorkerExecutionReceiptRecordResult result = service.recordReceipt(checkpointRequest("cmd-002"));

        assertTrue(result.accepted());
        assertTrue(result.duplicate());
        assertEquals("receipt-duplicate", result.record().receiptId());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("幂等复用")));
    }

    @Test
    void recordShouldResolveOutboxBySyncReferencesWhenCommandIdMissing() {
        DataSyncWorkerCommandOutbox outbox = outbox("cmd-003");
        when(outboxMapper.selectOne(any())).thenReturn(outbox);
        when(receiptMapper.insert(any(DataSyncWorkerExecutionReceipt.class))).thenAnswer(invocation -> {
            DataSyncWorkerExecutionReceipt receipt = invocation.getArgument(0);
            receipt.setId(3L);
            return 1;
        });
        DataSyncWorkerExecutionReceiptRecordRequest request = checkpointRequest(null);
        request.setReceiptId("receipt-without-command");

        DataSyncWorkerExecutionReceiptRecordResult result = service.recordReceipt(request);

        assertEquals("cmd-003", result.record().commandId());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("未携带 commandId")));
    }

    @Test
    void recordShouldAcceptStandaloneDataSyncReceiptWhenCommandAndOutboxMissing() {
        /*
         * 普通用户在数据同步页面直接创建、执行任务时，并不会先在 task-management 生成 Agent command outbox。
         * data-sync 仍然会在执行完成后投递低敏 execution receipt。这个用例验证 task-management 不再把
         * “没有 commandId/outbox”误判为非法回执，而是使用 standalone 关联键保存投影。
         */
        when(outboxMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.insert(any(DataSyncWorkerExecutionReceipt.class))).thenAnswer(invocation -> {
            DataSyncWorkerExecutionReceipt receipt = invocation.getArgument(0);
            receipt.setId(4L);
            return 1;
        });
        DataSyncWorkerExecutionReceiptRecordRequest request = checkpointRequest(null);
        request.setReceiptId("receipt-standalone-data-sync");
        request.setEventType("COMPLETE");
        request.setCompleted(true);
        request.setEndOfSource(true);
        request.setCheckpointPersisted(false);

        DataSyncWorkerExecutionReceiptRecordResult result = service.recordReceipt(request);

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        assertEquals("standalone-data-sync-task:7001:execution:8001", result.record().commandId());
        assertEquals("standalone-data-sync-execution:8001", result.record().outboxId());
        assertEquals(7001L, result.record().taskId());
        assertEquals(7001L, result.record().syncTaskId());
        assertEquals(8001L, result.record().syncExecutionId());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("standalone")));
    }

    @Test
    void recordShouldRejectUnknownOutbox() {
        when(outboxMapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.recordReceipt(checkpointRequest("missing-command")));

        verify(receiptMapper, never()).insert(any(DataSyncWorkerExecutionReceipt.class));
    }

    @Test
    void queryReceiptsShouldReturnLowSensitiveHistoryAndCounts() {
        DataSyncWorkerCommandOutbox outbox = outbox("cmd-004");
        DataSyncWorkerExecutionReceipt progress = existingReceipt("receipt-progress", outbox);
        progress.setEventType(DataSyncWorkerExecutionReceiptEventType.PROGRESS.name());
        progress.setErrorSummary("password=<已隐藏>");
        progress.setWarningSummary("warning hidden");
        when(receiptMapper.selectCount(any())).thenReturn(1L, 1L, 0L, 0L, 0L);
        when(receiptMapper.selectList(any())).thenReturn(List.of(progress));

        DataSyncWorkerExecutionReceiptQueryResult result = service.queryReceipts(
                "cmd-004",
                7001L,
                8001L,
                9001L,
                10L,
                20L,
                null,
                20
        );

        assertEquals(1L, result.totalCount());
        assertEquals(1L, result.eventTypeCounts().get(DataSyncWorkerExecutionReceiptEventType.PROGRESS.name()));
        assertEquals(1, result.records().size());
        assertTrue(result.records().get(0).hasErrorSummary());
        assertFalse(result.records().get(0).toString().contains("warning hidden"));
        assertTrue(result.detailVisibilityPolicy().contains("不返回 SQL"));
    }

    private DataSyncWorkerExecutionReceiptRecordRequest checkpointRequest(String commandId) {
        DataSyncWorkerExecutionReceiptRecordRequest request = new DataSyncWorkerExecutionReceiptRecordRequest();
        request.setReceiptId(commandId == null ? "receipt-no-command" : "receipt-" + commandId);
        request.setCommandId(commandId);
        request.setSyncTaskId(7001L);
        request.setSyncExecutionId(8001L);
        request.setEventType("CHECKPOINT");
        request.setEventTime(LocalDateTime.now().minusSeconds(2));
        request.setExecutorId("datasource-runner-001");
        request.setBatchRecordsRead(20L);
        request.setBatchRecordsWritten(18L);
        request.setBatchFailedRecordCount(2L);
        request.setTotalRecordsRead(120L);
        request.setTotalRecordsWritten(118L);
        request.setTotalFailedRecordCount(2L);
        request.setProgressPercent(60);
        request.setEndOfSource(false);
        request.setProgressReported(true);
        request.setCheckpointPersisted(true);
        request.setCheckpointType("UPDATED_AT");
        request.setCheckpointValueVisibility("HIDDEN_RAW_VALUE");
        request.setErrorSummary("写入失败 password=secret123 jdbc:mysql://127.0.0.1:3306/order");
        request.setWarnings(List.of(
                "跳过字段清洗 select * from user_secret",
                "下游批次存在 2 条失败记录"
        ));
        return request;
    }

    private DataSyncWorkerCommandOutbox outbox(String commandId) {
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
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name());
        outbox.setAttemptCount(1);
        outbox.setReceiptId("data-sync-receipt:" + commandId + ":7001:8001");
        outbox.setSyncTaskId(7001L);
        outbox.setSyncExecutionId(8001L);
        outbox.setPayloadJson("{\"commandId\":\"" + commandId + "\"}");
        outbox.setPayloadSizeBytes(24);
        outbox.setPayloadTruncated(false);
        outbox.setSideEffectStarted(true);
        outbox.setSideEffectExecuted(true);
        outbox.setCreateTime(LocalDateTime.now().minusMinutes(10));
        outbox.setUpdateTime(LocalDateTime.now().minusMinutes(5));
        return outbox;
    }

    private DataSyncWorkerExecutionReceipt existingReceipt(String receiptId, DataSyncWorkerCommandOutbox outbox) {
        DataSyncWorkerExecutionReceipt receipt = new DataSyncWorkerExecutionReceipt();
        receipt.setId(9L);
        receipt.setReceiptId(receiptId);
        receipt.setCommandId(outbox.getCommandId());
        receipt.setOutboxId(outbox.getOutboxId());
        receipt.setTaskId(outbox.getTaskId());
        receipt.setAgentRunId(outbox.getAgentRunId());
        receipt.setAgentSessionId(outbox.getAgentSessionId());
        receipt.setAuditId(outbox.getAuditId());
        receipt.setTenantId(outbox.getTenantId());
        receipt.setProjectId(outbox.getProjectId());
        receipt.setWorkspaceId(outbox.getWorkspaceId());
        receipt.setSyncTaskId(outbox.getSyncTaskId());
        receipt.setSyncExecutionId(outbox.getSyncExecutionId());
        receipt.setEventType(DataSyncWorkerExecutionReceiptEventType.CHECKPOINT.name());
        receipt.setEventTime(LocalDateTime.now());
        receipt.setExecutorId("datasource-runner-001");
        receipt.setSourceService("datasource-management");
        receipt.setBatchRecordsRead(20L);
        receipt.setBatchRecordsWritten(18L);
        receipt.setBatchFailedRecordCount(2L);
        receipt.setTotalRecordsRead(120L);
        receipt.setTotalRecordsWritten(118L);
        receipt.setTotalFailedRecordCount(2L);
        receipt.setProgressPercent(60);
        receipt.setEndOfSource(false);
        receipt.setCompleted(false);
        receipt.setFailed(false);
        receipt.setProgressReported(true);
        receipt.setCheckpointPersisted(true);
        receipt.setCheckpointType("UPDATED_AT");
        receipt.setCheckpointValueVisibility("HIDDEN_RAW_VALUE");
        receipt.setWarningCount(0);
        receipt.setCreateTime(LocalDateTime.now().minusMinutes(1));
        receipt.setUpdateTime(LocalDateTime.now().minusMinutes(1));
        return receipt;
    }
}
