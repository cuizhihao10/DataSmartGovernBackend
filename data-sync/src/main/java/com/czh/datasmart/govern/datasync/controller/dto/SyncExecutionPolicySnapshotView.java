/**
 * @Author : Cui
 * @Date: 2026/07/09 22:37
 * @Description DataSmart Govern Backend - SyncExecutionPolicySnapshotView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 单次 execution 的执行策略快照视图。
 *
 * <p>用于任务运行详情展示“本次实际用了什么策略”。这不是当前策略配置，而是历史 execution
 * 当时固化下来的运行治理事实。</p>
 */
public record SyncExecutionPolicySnapshotView(
        Long id,
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        Long executionId,
        String policyCodeSummary,
        List<String> matchedPolicyCodes,
        String resolutionOrder,
        Long targetRowsPerShard,
        Integer resolvedShardCount,
        Integer resolvedChannel,
        Integer taskGroupSize,
        Integer readBatchSize,
        Integer writeBatchSize,
        Integer commitIntervalRecords,
        Integer timeoutSeconds,
        Integer maxRetryCount,
        Long maxDirtyRecordCount,
        BigDecimal maxDirtyRecordRatio,
        String payloadPolicy,
        String snapshotJson,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
