/**
 * @Author : Cui
 * @Date: 2026/06/22 10:33
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptRecordRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSync worker 执行回执记录请求。
 *
 * <p>该请求由 datasource-management Runner 或后续 Kafka consumer 调用，用来把真实执行阶段的低敏进度回传给
 * task-management。它不是普通用户接口请求，也不应携带任何原始数据内容。</p>
 *
 * <p>字段安全要求：</p>
 * <p>1. 允许携带 ID、数量、布尔状态、事件类型、执行器 ID 和低敏检查点可见性策略；</p>
 * <p>2. 不允许携带 SQL、连接串、工具参数正文、失败行、样本数据、原始 checkpointValue、prompt、模型输出或内部 endpoint；</p>
 * <p>3. errorSummary/warnings 会被服务端再次脱敏和截断，API 视图也不会直接返回正文。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerExecutionReceiptRecordRequest {

    /**
     * 稳定幂等 ID。
     */
    private String receiptId;

    /**
     * 可选 commandId。
     *
     * <p>下游能拿到 commandId 时应优先传入；如果为空，服务端会用 syncTaskId + syncExecutionId 回查 outbox。
     * 对生产系统而言，commandId 是最清晰的端到端关联键，建议后续 datasource runner 也持续传递。</p>
     */
    private String commandId;

    /**
     * 下游 data-sync 同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * 下游 data-sync 执行 ID。
     */
    private Long syncExecutionId;

    /**
     * 事件类型：PROGRESS、CHECKPOINT、COMPLETE、FAILED。
     */
    private String eventType;

    /**
     * 下游事件发生时间，可为空。
     */
    private LocalDateTime eventTime;

    /**
     * 下游执行器 ID。
     */
    private String executorId;

    /**
     * 来源服务，默认 datasource-management。
     */
    private String sourceService;

    private Long batchRecordsRead;
    private Long batchRecordsWritten;
    private Long batchFailedRecordCount;
    private Long totalRecordsRead;
    private Long totalRecordsWritten;
    private Long totalFailedRecordCount;
    private Integer progressPercent;
    private Boolean endOfSource;
    private Boolean completed;
    private Boolean failed;
    private Boolean progressReported;
    private Boolean checkpointPersisted;
    private String checkpointType;
    private String checkpointValueVisibility;
    private String errorSummary;
    private List<String> warnings;
}
