/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentRuntimeEventReplayCursorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * Agent runtime event replay cursor 展示视图。
 *
 * <p>该视图描述某个客户端在某个订阅范围内已经确认到哪里。
 * 它不是事件本身，也不代表服务端已经删除旧事件；它只是断线续传、客户端恢复和运维排障使用的轻量状态。</p>
 *
 * @param clientId 客户端稳定 ID。
 * @param subscriptionKey 服务端标准化后的订阅键，例如 `run:xxx`、`session:yyy` 或 `run:xxx|session:yyy`。
 * @param runId Run 维度。
 * @param sessionId 会话维度。
 * @param acknowledgedReplaySequence 客户端已确认处理的最大 replaySequence。
 * @param previousAcknowledgedReplaySequence 本次更新前的确认位置；查询场景为空。
 * @param advanced 本次 ack 是否推进了 cursor。旧 ack、重复 ack 不会让 cursor 回退。
 * @param reason 本次 cursor 结果原因，例如 `ACK_ADVANCED`、`STALE_ACK_IGNORED`、`CURSOR_FOUND`。
 * @param updatedAt 服务端更新时间。
 */
public record AgentRuntimeEventReplayCursorView(
        String clientId,
        String subscriptionKey,
        String runId,
        String sessionId,
        Long acknowledgedReplaySequence,
        Long previousAcknowledgedReplaySequence,
        boolean advanced,
        String reason,
        Instant updatedAt
) {
}
