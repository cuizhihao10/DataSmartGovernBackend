/**
 * @Author : Cui
 * @Date: 2026/06/20 21:43
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxDispatchBatchResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSync worker outbox 批量投递结果。
 *
 * <p>该结果是内部运维控制面可以安全展示的低敏摘要。
 * 它展示“领到了多少、成功多少、延迟重试多少、失败多少”，但不展示 payload_json、SQL、工具实参、
 * 连接串、凭据、样本数据、prompt、模型输出或 datasource 内部地址。</p>
 *
 * @param schemaVersion 响应契约版本，便于后续兼容扩展。
 * @param executorId 本轮投递执行者。
 * @param dispatchTime 本轮投递时间。
 * @param claimedCount 本轮成功领取的 outbox 数量。
 * @param deliveredCount 本轮实际尝试投递的数量。
 * @param succeededCount 下游确认成功的数量。
 * @param deferredCount 可重试失败并进入 DEFERRED 的数量。
 * @param failedCount 永久失败或终态拒绝的数量。
 * @param skippedCount 因状态不适合投递而跳过的数量。
 * @param results 单条命令的低敏投递结果。
 * @param warnings claim 或投递过程中的低敏提示。
 */
public record DataSyncWorkerOutboxDispatchBatchResult(
        String schemaVersion,
        String executorId,
        LocalDateTime dispatchTime,
        int claimedCount,
        int deliveredCount,
        int succeededCount,
        int deferredCount,
        int failedCount,
        int skippedCount,
        List<DataSyncWorkerCommandDeliveryResult> results,
        List<String> warnings
) {
}
