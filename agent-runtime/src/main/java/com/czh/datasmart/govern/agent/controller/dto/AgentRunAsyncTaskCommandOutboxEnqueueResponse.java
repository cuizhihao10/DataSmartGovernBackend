/**
 * @Author : Cui
 * @Date: 2026/05/31 17:17
 * @Description DataSmart Govern Backend - AgentRunAsyncTaskCommandOutboxEnqueueResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 某次 Agent Run 的异步命令入 outbox 结果。
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param plannedCount 当前 Run 中 ASYNC_TASK 草案数量。
 * @param dispatchableCount 可进入 outbox 的草案数量。
 * @param enqueuedCount 本次首次写入 outbox 的数量。
 * @param duplicateCount 已存在 outbox、被幂等复用的数量。
 * @param blockedCount 因策略不可下发或 payload 安全限制未写入的数量。
 * @param summaryReasons 汇总说明。
 * @param recommendedActions 后续建议。
 * @param items outbox 记录视图。
 */
public record AgentRunAsyncTaskCommandOutboxEnqueueResponse(
        String sessionId,
        String runId,
        Integer plannedCount,
        Integer dispatchableCount,
        Integer enqueuedCount,
        Integer duplicateCount,
        Integer blockedCount,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentAsyncTaskCommandOutboxRecordView> items
) {
}
