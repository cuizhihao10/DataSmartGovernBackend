/**
 * @Author : Cui
 * @Date: 2026/06/22 10:36
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptQueryResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.util.List;
import java.util.Map;

/**
 * DataSync worker 执行回执查询结果。
 *
 * <p>该结果面向内部诊断和后续管理台执行历史查询。它不返回错误正文、warning 正文、checkpoint 原始值、
 * 同步数据样本或任何下游内部 endpoint，只返回低敏投影和按事件类型聚合的计数。</p>
 */
public record DataSyncWorkerExecutionReceiptQueryResult(
        String schemaVersion,
        long totalCount,
        Map<String, Long> eventTypeCounts,
        List<DataSyncWorkerExecutionReceiptView> records,
        String detailVisibilityPolicy,
        List<String> warnings
) {
}
