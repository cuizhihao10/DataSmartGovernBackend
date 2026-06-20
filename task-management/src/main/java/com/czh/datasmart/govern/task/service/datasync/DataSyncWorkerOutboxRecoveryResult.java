/**
 * @Author : Cui
 * @Date: 2026/06/20 23:35
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxRecoveryResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSync worker outbox 超时恢复结果。
 *
 * <p>该结果只用于内部控制面和运维排障，表达“本轮扫描了多少 stale DISPATCHING、恢复了多少、哪些进入 DEFERRED、
 * 哪些因为达到最大尝试次数进入 DEAD_LETTER”。它不返回 outbox.payload_json、不返回错误正文、不返回 datasource-management
 * 内部地址，也不返回工具实参、SQL、样本数据、prompt 或模型输出。</p>
 *
 * @param schemaVersion 响应契约版本，便于后续兼容扩展。
 * @param executorId 本次恢复动作执行者。
 * @param recoveryTime 本次恢复动作发生时间。
 * @param timeoutSeconds DISPATCHING 被认定为 stale 的配置阈值。
 * @param retryAfterSeconds 恢复为 DEFERRED 后，距离下一次允许 dispatcher 重新领取的秒数。
 * @param staleBeforeTime 小于等于该时间的 DISPATCHING dispatchedAt 会被视为可恢复候选。
 * @param requestedLimit 调用方请求的原始 limit。
 * @param effectiveLimit 服务端裁剪后的实际 limit。
 * @param scannedCount 本轮查出的 stale 候选数量。
 * @param recoveredCount 条件更新成功的恢复数量。
 * @param deferredCount 恢复为 DEFERRED 的数量。
 * @param deadLetterCount 因达到最大尝试次数而转入 DEAD_LETTER 的数量。
 * @param skippedCount 因并发竞争或状态已变化而跳过的数量。
 * @param recoveryPolicy 本轮恢复策略说明，供管理台和学习排障时理解状态流转。
 * @param recoveredRecords 已成功恢复的低敏 outbox 视图。
 * @param warnings 本轮恢复的低敏提示信息，例如无候选、并发跳过、死信产生等。
 */
public record DataSyncWorkerOutboxRecoveryResult(
        String schemaVersion,
        String executorId,
        LocalDateTime recoveryTime,
        int timeoutSeconds,
        int retryAfterSeconds,
        LocalDateTime staleBeforeTime,
        Integer requestedLimit,
        int effectiveLimit,
        int scannedCount,
        int recoveredCount,
        int deferredCount,
        int deadLetterCount,
        int skippedCount,
        String recoveryPolicy,
        List<DataSyncWorkerCommandOutboxView> recoveredRecords,
        List<String> warnings
) {
}
