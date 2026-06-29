/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - TaskManagementExecutionReceiptRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.task.receipt;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * data-sync 调用 task-management execution receipt 的本地请求镜像。
 *
 * <p>该类故意不复用 task-management 模块中的 Java DTO，原因是两个模块是独立微服务，应该通过 JSON 契约解耦。
 * 字段集合与 task-management `/internal/data-sync-worker-execution-receipts/record` 当前契约保持一致。</p>
 *
 * <p>安全边界：本请求只允许低敏控制面事实，不允许携带字段映射正文、过滤条件、SQL、连接串、凭据、
 * checkpoint 原始值、失败行、样本数据、prompt、模型输出或内部 endpoint。</p>
 */
@Data
public class TaskManagementExecutionReceiptRequest {

    /** 稳定幂等 ID，同一 execution 的同一事件重复投递时必须保持一致。 */
    private String receiptId;

    /** 可选 commandId；data-sync 当前可能拿不到 task-management outbox commandId，可为空由对方按 sync 引用回查。 */
    private String commandId;

    /** data-sync 同步任务 ID。 */
    private Long syncTaskId;

    /** data-sync 执行记录 ID。 */
    private Long syncExecutionId;

    /** 事件类型：COMPLETE、FAILED，后续可扩展 PROGRESS、CHECKPOINT。 */
    private String eventType;

    /** 事件发生时间。 */
    private LocalDateTime eventTime;

    /** data-sync worker executorId。 */
    private String executorId;

    /** 来源服务，当前为 data-sync。 */
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
