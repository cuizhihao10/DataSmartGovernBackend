/**
 * @Author : Cui
 * @Date: 2026/07/06 21:49
 * @Description DataSmart Govern Backend - SyncObjectExecutionSummary.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * OBJECT_LIST 对象级执行汇总。
 *
 * <p>fan-out 调度器最终不直接根据“最后一个对象是否成功”判断父 execution，而是根据本汇总判断：
 * 全部对象成功、部分对象成功、全部对象失败分别进入不同父状态。这样才能接近 DataX 的 Job/Task 关系：
 * 子任务可以单独成功或失败，父任务只负责汇总整体结果。</p>
 *
 * @param totalCount 总对象数，来自对象级执行账本记录数量。
 * @param succeededCount 已成功对象数。
 * @param failedCount 已进入最终失败状态的对象数。
 * @param retryingCount 仍处于 RUNNING/RETRYING 的对象数，正常串行流程结束时应为 0。
 * @param recordsRead 成功对象累计读取行数。
 * @param recordsWritten 成功对象累计写入行数。
 * @param failedRecordCount 失败对象或失败记录累计数，用于父 execution 和运营台低敏展示。
 * @param issueCodes 低敏问题码集合，不包含对象名、SQL、字段名、连接串、凭据或行样本。
 */
public record SyncObjectExecutionSummary(
        int totalCount,
        int succeededCount,
        int failedCount,
        int retryingCount,
        long recordsRead,
        long recordsWritten,
        long failedRecordCount,
        List<String> issueCodes
) {

    public SyncObjectExecutionSummary {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }

    /**
     * 是否所有对象都已经成功完成。
     */
    public boolean allSucceeded() {
        return totalCount > 0 && succeededCount == totalCount;
    }

    /**
     * 是否存在“部分对象成功、部分对象失败”的真实生产状态。
     */
    public boolean partiallySucceeded() {
        return succeededCount > 0 && failedCount > 0;
    }

    /**
     * 是否所有已处理对象都失败，父 execution 应进入 FAILED。
     */
    public boolean allFailed() {
        return totalCount > 0 && succeededCount == 0 && failedCount > 0;
    }
}
