/**
 * @Author : Cui
 * @Date: 2026/06/22 10:37
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptViewAssembler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.entity.DataSyncWorkerExecutionReceipt;

/**
 * DataSync worker 执行回执低敏视图组装器。
 *
 * <p>把视图白名单集中在一个类里，可以避免不同 Controller 或 Service 各自挑字段导致“某个新接口不小心返回
 * errorSummary、warningSummary 或未来新增敏感字段”的问题。这个类属于 task-management/data-sync 子域，
 * 不是全局通用 Mapper。</p>
 */
final class DataSyncWorkerExecutionReceiptViewAssembler {

    /**
     * 执行详情正文隐藏策略。
     */
    static final String DETAIL_VISIBILITY_POLICY =
            "只返回执行 ID、状态、计数、检查点可见性策略和错误/警告存在性；不返回 SQL、工具参数、样本数据、checkpoint 原始值、prompt、模型输出、连接串、凭据、内部 endpoint、错误正文或 warning 正文。";

    private DataSyncWorkerExecutionReceiptViewAssembler() {
        throw new UnsupportedOperationException("DataSyncWorkerExecutionReceiptViewAssembler 只提供静态组装方法");
    }

    /**
     * 将内部实体转换为可出站的低敏视图。
     *
     * @param receipt 数据库中的执行回执实体。
     * @return 低敏视图。
     */
    static DataSyncWorkerExecutionReceiptView toView(DataSyncWorkerExecutionReceipt receipt) {
        boolean hasErrorSummary = receipt.getErrorSummary() != null && !receipt.getErrorSummary().isBlank();
        return new DataSyncWorkerExecutionReceiptView(
                receipt.getId(),
                receipt.getReceiptId(),
                receipt.getCommandId(),
                receipt.getOutboxId(),
                receipt.getTaskId(),
                receipt.getAgentRunId(),
                receipt.getAgentSessionId(),
                receipt.getAuditId(),
                receipt.getTenantId(),
                receipt.getProjectId(),
                receipt.getWorkspaceId(),
                receipt.getSyncTaskId(),
                receipt.getSyncExecutionId(),
                receipt.getEventType(),
                receipt.getEventTime(),
                receipt.getExecutorId(),
                receipt.getSourceService(),
                receipt.getBatchRecordsRead(),
                receipt.getBatchRecordsWritten(),
                receipt.getBatchFailedRecordCount(),
                receipt.getTotalRecordsRead(),
                receipt.getTotalRecordsWritten(),
                receipt.getTotalFailedRecordCount(),
                receipt.getProgressPercent(),
                receipt.getEndOfSource(),
                receipt.getCompleted(),
                receipt.getFailed(),
                receipt.getProgressReported(),
                receipt.getCheckpointPersisted(),
                receipt.getCheckpointType(),
                receipt.getCheckpointValueVisibility(),
                hasErrorSummary,
                safeInt(receipt.getWarningCount()),
                DETAIL_VISIBILITY_POLICY,
                receipt.getCreateTime(),
                receipt.getUpdateTime()
        );
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
