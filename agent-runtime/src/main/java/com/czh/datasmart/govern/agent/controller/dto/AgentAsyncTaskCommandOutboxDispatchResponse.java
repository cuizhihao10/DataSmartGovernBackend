/**
 * @Author : Cui
 * @Date: 2026/05/31 17:17
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxDispatchResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxDispatcher;

/**
 * Agent 异步命令 outbox 手动投递响应。
 *
 * @param scanned 本轮扫描到的可投递记录数。
 * @param published 成功投递数量。
 * @param failed 失败并等待重试数量。
 * @param blocked 阻断数量。
 * @param skipped 跳过数量。
 * @param recovered 恢复 stale PUBLISHING 数量。
 * @param skippedReason 跳过原因。
 */
public record AgentAsyncTaskCommandOutboxDispatchResponse(
        Integer scanned,
        Integer published,
        Integer failed,
        Integer blocked,
        Integer skipped,
        Integer recovered,
        String skippedReason
) {

    public static AgentAsyncTaskCommandOutboxDispatchResponse from(
            AgentAsyncTaskCommandOutboxDispatcher.AgentAsyncTaskCommandOutboxDispatchSummary summary) {
        return new AgentAsyncTaskCommandOutboxDispatchResponse(
                summary.scanned(),
                summary.published(),
                summary.failed(),
                summary.blocked(),
                summary.skipped(),
                summary.recovered(),
                summary.skippedReason()
        );
    }
}
