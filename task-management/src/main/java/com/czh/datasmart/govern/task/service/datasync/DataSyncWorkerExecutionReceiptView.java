/**
 * @Author : Cui
 * @Date: 2026/06/22 10:34
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;

/**
 * DataSync worker 执行回执低敏视图。
 *
 * <p>这个视图用于内部 API、管理台执行历史、任务时间线和后续告警聚合。它只展示定位和运营需要的低敏字段：
 * ID、事件类型、计数、状态标记、检查点可见性策略和错误/警告是否存在。</p>
 *
 * <p>它刻意不返回 errorSummary/warningSummary 正文，因为真实商业环境中错误摘要很容易被 JDBC 异常、
 * 连接池异常、SQL 引擎异常或上游系统异常污染。即使服务端写入时做了脱敏，出站视图仍应默认隐藏正文，
 * 需要正文时应走受控审计后台和更高权限。</p>
 */
public record DataSyncWorkerExecutionReceiptView(
        Long databaseId,
        String receiptId,
        String commandId,
        String outboxId,
        Long taskId,
        String agentRunId,
        String agentSessionId,
        String auditId,
        Long tenantId,
        Long projectId,
        Long workspaceId,
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
        boolean hasErrorSummary,
        int warningCount,
        String detailVisibilityPolicy,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
