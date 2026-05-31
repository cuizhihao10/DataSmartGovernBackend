/**
 * @Author : Cui
 * @Date: 2026/06/01 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventReplayCursorRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;

/**
 * Agent runtime event replay cursor 内部记录。
 *
 * <p>cursor 是“某个客户端在某个订阅范围内已经消费到哪里”的服务端状态。
 * 当前先以内存实现承载，后续生产化时应落到 Redis 或 MySQL，以支持多实例 WebSocket 网关和进程重启恢复。</p>
 *
 * @param clientId 客户端稳定 ID。
 * @param subscriptionKey 标准化订阅键。
 * @param tenantId 保存 ack 时的租户上下文，用于审计和后续持久化索引。
 * @param projectId 保存 ack 时的项目上下文；PROJECT 范围没有单一项目时可能为空。
 * @param actorId 保存 ack 的操作者。
 * @param runId Run 维度。
 * @param sessionId 会话维度。
 * @param acknowledgedReplaySequence 已确认的最大 replaySequence。
 * @param updatedAt 服务端更新时间。
 */
public record AgentRuntimeEventReplayCursorRecord(
        String clientId,
        String subscriptionKey,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        Long acknowledgedReplaySequence,
        Instant updatedAt
) {
}
