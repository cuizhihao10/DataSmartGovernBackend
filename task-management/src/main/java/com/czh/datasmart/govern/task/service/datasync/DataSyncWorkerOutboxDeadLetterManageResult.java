/**
 * @Author : Cui
 * @Date: 2026/06/21 00:00
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxDeadLetterManageResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSync worker outbox 死信人工处置结果。
 *
 * <p>该结果用于内部运维控制面展示处置是否成功、命令从哪个状态流转到哪个状态、
 * 是否已经重新进入 dispatcher 可领取队列，以及处置后的低敏 outbox 视图。</p>
 *
 * <p>低敏边界：</p>
 * <p>结果不返回 payloadJson、lastError 正文、operator reason 正文、SQL、工具实参、样本数据、prompt、
 * 模型输出、连接串、凭据或内部 endpoint。调用方只看到“原因已按策略隐藏/脱敏”的策略说明。</p>
 *
 * @param schemaVersion 响应契约版本，便于后续管理台兼容扩展字段。
 * @param executorId 执行处置的操作者或内部服务账号。
 * @param commandId 被处置的命令 ID。
 * @param action 本次处置动作。
 * @param managementTime 处置发生时间。
 * @param previousStatus 处置前状态，当前必须是 DEAD_LETTER。
 * @param currentStatus 处置后状态，REPLAY 对应 DEFERRED，CLOSE 对应 CLOSED。
 * @param replayScheduled 是否已重新进入受控重放调度。
 * @param requestedRetryAfterSeconds 调用方请求的重放延迟。
 * @param effectiveRetryAfterSeconds 服务端裁剪后的重放延迟；非 REPLAY 时为 null。
 * @param operatorReasonVisibilityPolicy 人工原因的可见性策略说明。
 * @param managementPolicy 本接口的处置策略说明。
 * @param record 处置后的低敏 outbox 视图。
 * @param warnings 面向运维台的低敏提示信息。
 */
public record DataSyncWorkerOutboxDeadLetterManageResult(
        String schemaVersion,
        String executorId,
        String commandId,
        String action,
        LocalDateTime managementTime,
        String previousStatus,
        String currentStatus,
        boolean replayScheduled,
        Integer requestedRetryAfterSeconds,
        Integer effectiveRetryAfterSeconds,
        String operatorReasonVisibilityPolicy,
        String managementPolicy,
        DataSyncWorkerCommandOutboxView record,
        List<String> warnings
) {
}
