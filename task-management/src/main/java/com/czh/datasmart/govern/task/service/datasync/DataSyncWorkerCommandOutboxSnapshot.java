/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxSnapshot.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

/**
 * DataSync worker command outbox 快照。
 *
 * @param outboxId outbox 记录 ID。
 * @param commandId command ID。
 * @param idempotencyKey 幂等键。
 * @param status 当前 outbox 状态。
 * @param duplicate 是否复用了已有 outbox。
 * @param attemptCount 投递尝试次数。
 * @param receiptId 最近 receipt ID。
 * @param syncTaskId 下游同步任务 ID。
 * @param syncExecutionId 下游同步 execution ID。
 * @param message 低敏说明。
 */
public record DataSyncWorkerCommandOutboxSnapshot(
        String outboxId,
        String commandId,
        String idempotencyKey,
        String status,
        boolean duplicate,
        Integer attemptCount,
        String receiptId,
        Long syncTaskId,
        Long syncExecutionId,
        String message
) {
}
