/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentRuntimeEventReplayResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Agent runtime event replay 响应。
 *
 * <p>普通查询接口回答“按条件看事件”；replay 接口额外回答“本次从哪个 cursor 之后回放、是否关联了客户端 cursor”。
 * 这为 WebSocket 断线重连、Python Runtime 事件桥接和前端时间线恢复提供了更明确的契约。</p>
 *
 * @param appliedLimit 本次应用的查询上限。
 * @param totalMatched 本次命中的事件数量。
 * @param requestedAfterSequence 调用方显式传入的 replay 起点。
 * @param effectiveAfterSequence 服务端实际使用的 replay 起点，可能来自请求，也可能来自已保存的客户端 ack cursor。
 * @param cursor 与 clientId 对应的服务端 cursor；未传 clientId 时为空。
 * @param replayedAt 服务端生成本次 replay 响应的时间。
 * @param events replay 事件列表，沿用 runtime event 投影视图，包含 display 展示解释。
 */
public record AgentRuntimeEventReplayResponse(
        int appliedLimit,
        int totalMatched,
        Long requestedAfterSequence,
        Long effectiveAfterSequence,
        AgentRuntimeEventReplayCursorView cursor,
        Instant replayedAt,
        List<AgentRuntimeEventProjectionView> events
) {
}
