/**
 * @Author : Cui
 * @Date: 2026/07/22
 * @Description DataSmart Govern Backend - SyncExecutionDiagnosisResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * Agent 可消费的低敏同步失败诊断包。
 *
 * <p>这里只公开错误分类、计数、对象定位和修复动作编码，不公开凭据、SQL、WHERE、
 * 字段映射正文、checkpoint 原值、源记录主键或样本载荷。</p>
 */
public record SyncExecutionDiagnosisResponse(
        Long taskId,
        Long templateId,
        Long executionId,
        String taskState,
        String executionState,
        String syncMode,
        String writeStrategy,
        String sourceConnectorType,
        String targetConnectorType,
        Long targetDatasourceId,
        long recordsRead,
        long recordsWritten,
        long failedRecordCount,
        int failedObjectCount,
        int retryableDirtySampleCount,
        int quarantinedDirtySampleCount,
        List<FailedObjectSummary> failedObjects,
        List<ErrorSummary> errors,
        List<String> rootCauseCodes,
        List<String> recommendedRepairActions,
        List<KnowledgeCaseSummary> similarCases,
        String ragQuery,
        String diagnosisDigest,
        String payloadPolicy
) {
    public record FailedObjectSummary(
            Long objectExecutionId,
            Integer objectOrdinal,
            String workUnitType,
            String shardOrPartition,
            String targetSchemaName,
            String targetObjectName,
            String errorType,
            String errorCode,
            String errorMessage
    ) {
    }

    public record ErrorSummary(String errorType, String errorCode, String message, long count, boolean retryable) {
    }

    public record KnowledgeCaseSummary(Long caseId, String incidentType, String title, String resolutionSummary) {
    }
}
