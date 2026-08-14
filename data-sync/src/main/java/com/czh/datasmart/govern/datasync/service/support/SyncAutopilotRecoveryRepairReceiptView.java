/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryRepairReceiptView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 一个受治理修复动作的低敏、可幂等重放回执。
 *
 * <p>{@code applied=true} 只证明控制面修复和重新排队已经提交，不代表数据搬运最终成功。后续仍需
 * worker 终态以及 PRECHECK_AGENT、MONITOR_AGENT 的事实完成恢复收敛。</p>
 *
 * <p>{@code applied=false} 时，data-sync 会在同一事务内收敛旧 case。若授权预算仍允许，
 * {@code replanQueued=true} 及其事件 ID 证明下一轮规划已经写入持久 outbox；达到循环或时间上限时
 * 只保留 {@code caseState=ATTENTION_REQUIRED}，不会伪造下一轮。</p>
 */
public record SyncAutopilotRecoveryRepairReceiptView(
        String receiptId,
        Long caseId,
        Long syncTaskId,
        Long sourceExecutionId,
        Long executionId,
        String action,
        boolean applied,
        int affectedCount,
        String executionState,
        String taskState,
        String reasonCode,
        List<String> issueCodes,
        String actionFingerprint,
        String caseState,
        boolean replanQueued,
        String replanEventId,
        Integer nextCycle) {
}
