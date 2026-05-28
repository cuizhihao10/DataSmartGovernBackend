/**
 * @Author : Cui
 * @Date: 2026/05/08 22:02
 * @Description DataSmart Govern Backend - SyncExpiredLeaseRecoveryResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 过期租约恢复结果。
 *
 * @param scanned 本次扫描到的过期执行记录数量
 * @param recovered 成功恢复回队列数量
 * @param recoveredExecutionIds 成功恢复回队列的 executionId 列表
 * @param attentionRequired 进入人工介入数量
 * @param attentionExecutionIds 进入人工介入的 executionId 列表
 * @param message 结果说明
 */
public record SyncExpiredLeaseRecoveryResult(
        int scanned,
        int recovered,
        List<Long> recoveredExecutionIds,
        int attentionRequired,
        List<Long> attentionExecutionIds,
        String message
) {
}
