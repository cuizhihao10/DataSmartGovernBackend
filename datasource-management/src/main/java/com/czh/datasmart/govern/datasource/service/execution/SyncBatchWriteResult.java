/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchWriteResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批处理写入结果摘要。
 *
 * <p>该结果只返回数量、是否建议提交以及错误摘要。
 * 如果后续要记录失败行样本，应进入专门的 error sample 存储并做脱敏、权限和保留周期控制，
 * 不能把失败行原文塞进该摘要。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchWriteResult {

    /**
     * 本批成功写入记录数。
     */
    private Long recordsWritten;

    /**
     * 本批失败记录数。
     */
    private Long failedRecordCount;

    /**
     * 是否建议提交事务。
     */
    private Boolean commitRecommended;

    /**
     * 低敏错误摘要。
     */
    private String errorSummary;

    /**
     * 结构化脏数据样本。
     *
     * <p>该列表只保存低敏样本，不保存完整原始行。run-once 会把它们返回给 data-sync，由 data-sync
     * 写入错误样本表并应用权限、保留期和后续修复重放策略。</p>
     */
    private List<SyncDirtyRecordSample> dirtySamples;

    /**
     * 是否已经触发脏数据阈值。
     *
     * <p>Writer 负责识别行级失败和采样；是否超过阈值由 run-once 根据 RuntimeControlPlan 判断。
     * 该字段用于把裁决结果一起传回上层，便于测试和后续指标统计。</p>
     */
    private Boolean dirtyThresholdExceeded;

    public SyncBatchWriteResult(Long recordsWritten,
                                Long failedRecordCount,
                                Boolean commitRecommended,
                                String errorSummary) {
        this(recordsWritten, failedRecordCount, commitRecommended, errorSummary, List.of(), false);
    }
}
