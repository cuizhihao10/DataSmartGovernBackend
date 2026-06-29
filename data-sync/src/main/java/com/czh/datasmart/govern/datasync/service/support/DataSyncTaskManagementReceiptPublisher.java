/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptClient;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * data-sync 到 task-management execution receipt 的发布器。
 *
 * <p>本类位于 service support 层，而不是 HTTP client 层，原因是它理解 data-sync 的业务对象：
 * {@link SyncTask}、{@link SyncExecution} 和 datasource-management run-once response。
 * 它负责把这些领域事实转换成 task-management 需要的低敏 receipt 请求。</p>
 *
 * <p>发布器不暴露敏感信息：</p>
 * <p>1. COMPLETE 只发送计数、完成标记、endOfSource 和 checkpoint 可见性策略；</p>
 * <p>2. FAILED 只发送错误码、低敏错误摘要和问题码 warning，不发送异常 message、SQL、URL、字段值或样本；</p>
 * <p>3. commandId 当前可为空，task-management 会尝试按 syncTaskId + syncExecutionId 回查 outbox。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncTaskManagementReceiptPublisher {

    private static final String SOURCE_SERVICE = "data-sync";
    private static final String CHECKPOINT_VISIBILITY = "NO_CHECKPOINT_VALUE_IN_RECEIPT";

    private final TaskManagementExecutionReceiptClient receiptClient;
    private final DataSyncTaskManagementReceiptProperties properties;

    /**
     * 发布 execution complete receipt。
     */
    public void publishComplete(SyncTask task,
                                SyncExecution execution,
                                SyncActorContext actorContext,
                                DatasourceRunOnceResponse response) {
        if (!properties.isEnabled()) {
            return;
        }
        TaskManagementExecutionReceiptRequest request = baseRequest(task, execution, "COMPLETE");
        request.setBatchRecordsRead(zeroIfNull(response == null ? null : response.getBatchRecordsRead()));
        request.setBatchRecordsWritten(zeroIfNull(response == null ? null : response.getBatchRecordsWritten()));
        request.setBatchFailedRecordCount(zeroIfNull(response == null ? null : response.getBatchFailedRecordCount()));
        request.setTotalRecordsRead(zeroIfNull(response == null ? execution.getRecordsRead() : response.getTotalRecordsRead()));
        request.setTotalRecordsWritten(zeroIfNull(response == null ? execution.getRecordsWritten() : response.getTotalRecordsWritten()));
        request.setTotalFailedRecordCount(zeroIfNull(response == null ? execution.getFailedRecordCount() : response.getTotalFailedRecordCount()));
        request.setProgressPercent(100);
        request.setEndOfSource(response == null ? Boolean.TRUE : response.getEndOfSource());
        request.setCompleted(true);
        request.setFailed(false);
        request.setProgressReported(false);
        request.setCheckpointPersisted(false);
        request.setCheckpointType(null);
        request.setCheckpointValueVisibility(CHECKPOINT_VISIBILITY);
        request.setWarnings(List.of("data-sync 已完成本次 execution，task-management 仅记录低敏执行投影"));
        deliver(request, actorContext);
    }

    /**
     * 发布 execution failed receipt。
     */
    public void publishFailed(SyncTask task,
                              SyncExecution execution,
                              SyncActorContext actorContext,
                              String errorCode,
                              List<String> issueCodes) {
        if (!properties.isEnabled()) {
            return;
        }
        TaskManagementExecutionReceiptRequest request = baseRequest(task, execution, "FAILED");
        request.setBatchRecordsRead(0L);
        request.setBatchRecordsWritten(0L);
        request.setBatchFailedRecordCount(1L);
        request.setTotalRecordsRead(zeroIfNull(execution.getRecordsRead()));
        request.setTotalRecordsWritten(zeroIfNull(execution.getRecordsWritten()));
        request.setTotalFailedRecordCount(Math.max(1L, zeroIfNull(execution.getFailedRecordCount())));
        request.setProgressPercent(null);
        request.setEndOfSource(false);
        request.setCompleted(false);
        request.setFailed(true);
        request.setProgressReported(false);
        request.setCheckpointPersisted(false);
        request.setCheckpointType(null);
        request.setCheckpointValueVisibility(CHECKPOINT_VISIBILITY);
        request.setErrorSummary("data-sync execution failed, errorCode=" + safeCode(errorCode));
        request.setWarnings(issueCodes == null || issueCodes.isEmpty()
                ? List.of("data-sync 回写失败回执，未提供额外低敏 issueCode")
                : issueCodes.stream().map(code -> "issueCode=" + safeCode(code)).toList());
        deliver(request, actorContext);
    }

    private TaskManagementExecutionReceiptRequest baseRequest(SyncTask task,
                                                              SyncExecution execution,
                                                              String eventType) {
        TaskManagementExecutionReceiptRequest request = new TaskManagementExecutionReceiptRequest();
        request.setReceiptId(receiptId(execution, eventType));
        request.setCommandId(null);
        request.setSyncTaskId(task.getId());
        request.setSyncExecutionId(execution.getId());
        request.setEventType(eventType);
        request.setEventTime(LocalDateTime.now());
        request.setExecutorId(execution.getExecutorId());
        request.setSourceService(SOURCE_SERVICE);
        return request;
    }

    /**
     * 统一投递并处理“receipt 投影失败是否阻断主流程”的策略。
     */
    private void deliver(TaskManagementExecutionReceiptRequest request, SyncActorContext actorContext) {
        try {
            receiptClient.record(request, actorContext);
        } catch (PlatformBusinessException exception) {
            log.warn("task-management execution receipt 投递失败: syncTaskId={}, syncExecutionId={}, receiptId={}, eventType={}, exceptionType={}, deliveryRequired={}",
                    request.getSyncTaskId(), request.getSyncExecutionId(), request.getReceiptId(), request.getEventType(),
                    exception.getClass().getSimpleName(), properties.isDeliveryRequired());
            if (properties.isDeliveryRequired()) {
                throw exception;
            }
        }
    }

    private String receiptId(SyncExecution execution, String eventType) {
        return "data-sync-execution-receipt:" + execution.getId() + ":" + eventType.toLowerCase();
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_\\-:.]", "_");
    }
}
