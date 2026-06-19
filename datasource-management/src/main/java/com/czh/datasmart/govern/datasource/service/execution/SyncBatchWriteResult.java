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
}
