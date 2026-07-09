/**
 * @Author : Cui
 * @Date: 2026/07/09 22:36
 * @Description DataSmart Govern Backend - SyncExecutionPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 执行策略前端视图。
 *
 * <p>该视图只返回低敏策略参数和作用域元数据，不返回任何数据源连接信息或任务配置正文。</p>
 */
public record SyncExecutionPolicyView(
        Long id,
        Long tenantId,
        Long projectId,
        String scopeType,
        String scopeKey,
        String scopeName,
        String policyCode,
        String policyName,
        Boolean enabled,
        Long datasourceId,
        String connectorType,
        String connectorRole,
        Long syncTaskId,
        Long targetRowsPerShard,
        Integer minShardCount,
        Integer maxShardCount,
        Integer maxChannel,
        Integer taskGroupSize,
        Integer readBatchSize,
        Integer writeBatchSize,
        Integer commitIntervalRecords,
        Integer timeoutSeconds,
        Integer maxRetryCount,
        Long maxDirtyRecordCount,
        BigDecimal maxDirtyRecordRatio,
        Integer priority,
        String description,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
