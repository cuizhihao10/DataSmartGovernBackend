/**
 * @Author : Cui
 * @Date: 2026/07/08 00:01
 * @Description DataSmart Govern Backend - SyncDirtyRecordReplayResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 脏数据修复重放结果。
 *
 * <p>该结果只返回控制面低敏事实：
 * 新建了哪条 execution、绑定了哪条 recovery plan、选中了多少条错误样本、selector 使用什么模式。
 * 它不会返回错误样本 payload、源端记录原文、目标端记录原文、SQL、连接串或 worker 内部参数。</p>
 *
 * @param taskId 同步任务 ID。
 * @param sourceExecutionId 脏数据样本来源 executionId。
 * @param replayExecutionId 新建的 replay executionId，后续 worker 会认领该 execution。
 * @param recoveryPlanId 新建的恢复计划 ID。
 * @param selectedSampleCount 本次纳入修复重放的错误样本数量。
 * @param selectorMode selector 模式：SELECTED_IDS 或 ALL_RETRYABLE_IN_EXECUTION。
 * @param taskState 操作完成后的任务主状态，通常为 QUEUED。
 * @param warnings 低敏提示，例如部分候选样本不可重试或全选模式达到上限。
 * @param message 面向调用方的结果说明。
 */
public record SyncDirtyRecordReplayResult(
        Long taskId,
        Long sourceExecutionId,
        Long replayExecutionId,
        Long recoveryPlanId,
        int selectedSampleCount,
        String selectorMode,
        String taskState,
        List<String> warnings,
        String message
) {
}
