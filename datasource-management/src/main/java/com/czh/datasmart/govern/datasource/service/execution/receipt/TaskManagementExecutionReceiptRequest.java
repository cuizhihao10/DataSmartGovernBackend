/**
 * @Author : Cui
 * @Date: 2026/06/22 10:43
 * @Description DataSmart Govern Backend - TaskManagementExecutionReceiptRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.receipt;

import java.time.LocalDateTime;
import java.util.List;

/**
 * datasource-management 发送给 task-management 的执行回执本地请求模型。
 *
 * <p>这里没有 import task-management 模块的请求类，是为了保持微服务边界清晰。
 * 两个服务通过 JSON 字段契约对接，后续如果 task-management 内部重构包名或 DTO 实现，datasource-management
 * 只要字段契约不变就不需要重新编译依赖。</p>
 *
 * <p>安全要求：该对象只承载 ID、计数、布尔状态、checkpoint 可见性策略和低敏摘要，不承载 SQL、连接串、
 * 工具实参、样本数据、失败行、checkpoint 原始值、prompt 或模型输出。</p>
 */
public record TaskManagementExecutionReceiptRequest(
        String receiptId,
        String commandId,
        Long syncTaskId,
        Long syncExecutionId,
        String eventType,
        LocalDateTime eventTime,
        String executorId,
        String sourceService,
        Long batchRecordsRead,
        Long batchRecordsWritten,
        Long batchFailedRecordCount,
        Long totalRecordsRead,
        Long totalRecordsWritten,
        Long totalFailedRecordCount,
        Integer progressPercent,
        Boolean endOfSource,
        Boolean completed,
        Boolean failed,
        Boolean progressReported,
        Boolean checkpointPersisted,
        String checkpointType,
        String checkpointValueVisibility,
        String errorSummary,
        List<String> warnings
) {
}
