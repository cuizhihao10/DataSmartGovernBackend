/**
 * @Author : Cui
 * @Date: 2026/07/22
 * @Description DataSmart Govern Backend - SyncExecutionDiagnosisResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 可消费的低敏同步失败诊断包。
 *
 * <p>这里只公开错误分类、计数、对象定位和修复动作编码，不公开凭据、SQL、WHERE、
 * 字段映射正文、checkpoint 原值、源记录主键或样本载荷。</p>
 */
public record SyncExecutionDiagnosisResponse(
        Long taskId,
        Long executionId,
        String taskState,
        String executionState,
        String syncMode,
        String writeStrategy,
        String sourceConnectorType,
        String targetConnectorType,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        long recordsRead,
        long recordsWritten,
        long failedRecordCount,
        int failedObjectCount,
        int retryableDirtySampleCount,
        int quarantinedDirtySampleCount,
        RuntimeMetricSummary runtimeMetrics,
        ExecutionPolicyComparison executionPolicyComparison,
        List<ConnectorRuntimeSummary> connectorRuntimeSummaries,
        List<FailedObjectSummary> failedObjects,
        List<ErrorSummary> errors,
        List<String> rootCauseCodes,
        List<String> recommendedRepairActions,
        List<KnowledgeCaseSummary> similarCases,
        List<EvidenceRecord> evidenceRecords,
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

    /** 持久执行、对象账本和错误样本聚合出的结构化运行指标。 */
    public record RuntimeMetricSummary(
            long recordsRead,
            long recordsWritten,
            long failedRecordCount,
            int failedObjectCount,
            int retryableDirtySampleCount,
            int quarantinedDirtySampleCount
    ) {
    }

    /** 当前失败执行与上一次成功执行所使用的低敏策略对比。 */
    public record ExecutionPolicyComparison(
            String comparisonStatus,
            PolicySnapshotSummary current,
            PolicySnapshotSummary previousSuccessful,
            List<String> changedFields
    ) {
    }

    /** 一次 execution 真正使用的容量、批量、超时和重试参数。 */
    public record PolicySnapshotSummary(
            Long executionId,
            String policyCodeSummary,
            Integer resolvedChannel,
            Integer taskGroupSize,
            Integer readBatchSize,
            Integer writeBatchSize,
            Integer commitIntervalRecords,
            Integer timeoutSeconds,
            Integer maxRetryCount,
            Long maxDirtyRecordCount,
            BigDecimal maxDirtyRecordRatio,
            LocalDateTime capturedAt
    ) {
    }

    /** 连接器版本、健康、能力以及当前 execution 的限流/容量边界。 */
    public record ConnectorRuntimeSummary(
            String connectorRole,
            Long datasourceId,
            String lookupStatus,
            String snapshotVersion,
            String connectorRuntimeVersion,
            String connectorRuntimeVersionSource,
            String connectorType,
            String connectorFamily,
            String implementationStage,
            String healthStatus,
            Boolean canRead,
            Boolean canWrite,
            Boolean supportsSchemaDiscovery,
            Boolean supportsFieldMapping,
            Boolean supportsCheckpointResume,
            Boolean supportsPartitionParallelism,
            String runtimeLimitStatus,
            Integer effectiveChannel,
            Integer effectiveReadBatchSize,
            Integer effectiveWriteBatchSize,
            Integer effectiveTimeoutSeconds,
            String capacityStatus,
            List<String> consistencyNotes,
            List<String> performanceRecommendations,
            List<String> productionLimitations,
            List<String> issueCodes,
            LocalDateTime generatedAt
    ) {
    }

    /** 统一证据元数据：来源、取得时间、源事实时间、可信度和校准依据。 */
    public record EvidenceRecord(
            String evidenceId,
            String sourceType,
            String sourceRef,
            String retrievedAt,
            String sourceObservedAt,
            double confidence,
            String confidenceBasis
    ) {
    }

    public record KnowledgeCaseSummary(
            Long caseId,
            String incidentType,
            String title,
            String resolutionSummary,
            LocalDateTime closedAt
    ) {
    }
}
