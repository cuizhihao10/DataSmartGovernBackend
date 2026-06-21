/**
 * @Author : Cui
 * @Date: 2026/06/21 00:35
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxSchedulerTickResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSync worker outbox 后台调度单轮结果。
 *
 * <p>这个 record 不是对外用户 API，而是 scheduler、测试和后续低敏诊断指标之间的内部契约。
 * 它把一轮后台调度拆成两段：先恢复 stale DISPATCHING，再投递可领取 outbox。
 * 这样运维排查时能清楚地区分“本轮是在释放悬挂命令”还是“本轮是在真实调用 datasource-management”。</p>
 *
 * <p>低敏边界：</p>
 * <p>结果中引用的 recovery/dispatch 子结果本身已经是低敏摘要，不包含 payload_json、SQL、工具实参、
 * 样本数据、prompt、模型输出、连接串、凭据、内部 endpoint 或错误正文。scheduler 日志也只应打印计数，
 * 不应把单条结果列表原样写入日志。</p>
 *
 * @param schemaVersion 响应契约版本，便于未来扩展指标字段。
 * @param executorId 本轮后台调度使用的 worker 身份。
 * @param tickTime 本轮调度开始时间。
 * @param recoveryResult stale DISPATCHING 恢复结果，包含恢复为 DEFERRED/DEAD_LETTER 的低敏统计。
 * @param dispatchResult 可投递命令的批量投递结果，包含 succeeded/deferred/failed/skipped 低敏统计。
 * @param warnings 本轮调度聚合提示，例如恢复产生死信、投递队列为空、下游暂不可用等。
 */
public record DataSyncWorkerOutboxSchedulerTickResult(
        String schemaVersion,
        String executorId,
        LocalDateTime tickTime,
        DataSyncWorkerOutboxRecoveryResult recoveryResult,
        DataSyncWorkerOutboxDispatchBatchResult dispatchResult,
        List<String> warnings
) {

    /**
     * 判断本轮是否产生了值得记录 INFO 日志的业务动作。
     *
     * <p>后台 scheduler 默认每隔几秒触发一次。如果队列长期为空，每轮都打印日志会制造噪音。
     * 因此只有恢复、领取、投递或错误类结果出现时，调度器才打印聚合日志。</p>
     *
     * @return true 表示本轮有恢复、领取、投递、失败、跳过或死信等业务事件。
     */
    public boolean hasMeaningfulWork() {
        if (recoveryResult != null && (recoveryResult.scannedCount() > 0 || recoveryResult.recoveredCount() > 0)) {
            return true;
        }
        if (dispatchResult == null) {
            return false;
        }
        return dispatchResult.claimedCount() > 0
                || dispatchResult.deliveredCount() > 0
                || dispatchResult.deferredCount() > 0
                || dispatchResult.failedCount() > 0
                || dispatchResult.skippedCount() > 0;
    }
}
