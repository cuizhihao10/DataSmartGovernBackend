/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxClaimResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSync worker outbox 领取结果。
 *
 * <p>该结果返回给内部 dispatcher 或运维控制面，表示本次有多少 outbox 命令已经被安全推进到 DISPATCHING。
 * 返回的 candidates 是低敏视图，调度器后续应根据 commandId/idempotencyKey/outboxId 继续执行真实投递，而不能从这里读取
 * payload_json、SQL、工具实参或样本数据。</p>
 *
 * @param schemaVersion 响应契约版本，便于后续兼容扩展。
 * @param executorId 本次领取者标识。
 * @param claimTime 本次领取动作发生时间。
 * @param requestedLimit 请求的原始 limit。
 * @param effectiveLimit 服务端裁剪后的有效 limit。
 * @param claimedCount 本次成功领取的命令数量。
 * @param candidates 已被标记为 DISPATCHING 的低敏命令视图。
 * @param warnings 本次领取的低敏提示，例如并发抢占失败或当前无可领取命令。
 */
public record DataSyncWorkerOutboxClaimResult(
        String schemaVersion,
        String executorId,
        LocalDateTime claimTime,
        Integer requestedLimit,
        int effectiveLimit,
        int claimedCount,
        List<DataSyncWorkerCommandOutboxView> candidates,
        List<String> warnings
) {
}
