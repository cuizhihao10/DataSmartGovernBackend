/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactReceiptSummaryView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * 恢复事实包中的 worker/dry-run receipt 低敏摘要。
 *
 * <p>receipt 来自 task-management 或后续 worker 写回的 runtime event projection。
 * 它是“下游已经看过这条 command 并返回了某种执行前结果”的控制面事实。
 * 该视图只展示 outcome、preCheckPassed、errorCode 等机器可读状态，不展示 receipt message、
 * payload body、工具参数、SQL 或模型输出。</p>
 */
public record AgentToolActionResumeFactReceiptSummaryView(
        Integer receiptCount,
        Boolean commandIdMatched,
        Long latestReplaySequence,
        String latestOutcome,
        String latestTaskStatus,
        Boolean latestPreCheckPassed,
        Boolean latestSideEffectExecuted,
        String latestErrorCode,
        Instant latestConsumedAt,
        String payloadPolicy
) {
}
