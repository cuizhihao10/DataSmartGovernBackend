/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackHistoryRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;

/**
 * 最终态 callback job 的不可变低敏历史事件。
 *
 * <p>主 job 表保存当前状态，历史表保存为什么被创建、领取、续租、退避、送达或转人工补偿。事件只记录枚举状态、
 * 机器码和 worker 标识，完整异常细节仍留在受控日志平台，避免把下游响应或命令上下文复制进控制面事实库。</p>
 */
public record AgentCommandTaskFinalStateCallbackHistoryRecord(
        String historyId,
        String jobId,
        String sourceReceiptIdentityKey,
        String eventType,
        AgentCommandTaskFinalStateCallbackJobStatus status,
        String reasonCode,
        int attemptCount,
        String workerId,
        Instant occurredAt
) {
}
