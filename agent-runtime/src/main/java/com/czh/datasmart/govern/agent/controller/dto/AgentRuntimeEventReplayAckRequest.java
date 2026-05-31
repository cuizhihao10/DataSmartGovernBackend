/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentRuntimeEventReplayAckRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * Agent runtime event replay 客户端确认请求。
 *
 * <p>WebSocket 或 HTTP replay 客户端在成功处理某个 replaySequence 后，应把最新游标回写到控制面。
 * 这个动作的业务含义不是“查看事件”，而是“客户端确认自己已经消费到某个 Java 控制面事件位置”。</p>
 *
 * <p>为什么需要显式 ack：</p>
 * <p>1. 断线重连时，服务端可以从上次确认位置之后继续 replay，避免重复刷屏；</p>
 * <p>2. Python Runtime 或前端可以把 Java 控制面事件作为一个独立 source，与模型流式输出、工具流式输出分开管理；</p>
 * <p>3. 后续持久化 cursor 后，审计台可以知道某个客户端是否长期没有消费关键事件。</p>
 *
 * @param clientId 客户端稳定 ID。前端可以使用会话窗口 ID，Python Runtime 可以使用 source 名称加实例 ID。
 * @param runId Run 维度订阅 ID。runId 与 sessionId 至少传一个，真实页面通常优先传 runId。
 * @param sessionId 会话维度订阅 ID。适合会话级时间线或 Run 还没有创建完成的场景。
 * @param acknowledgedReplaySequence 客户端已成功处理的最大 Java replaySequence。
 * @param clientObservedAt 客户端处理该事件的时间。仅作诊断参考，不能作为服务端排序依据。
 */
public record AgentRuntimeEventReplayAckRequest(
        String clientId,
        String runId,
        String sessionId,
        Long acknowledgedReplaySequence,
        Instant clientObservedAt
) {
}
