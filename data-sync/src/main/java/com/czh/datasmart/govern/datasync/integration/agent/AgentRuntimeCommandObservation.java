/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - AgentRuntimeCommandObservation.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.agent;

import java.time.LocalDateTime;

/**
 * Agent 异步命令 outbox 的低敏观察结果。
 *
 * <p>这里只保留 commandId 对应的 outbox 投递状态、尝试次数和时间。PUBLISHED 表示 dispatcher 已向所选
 * transport 投递，不等于 Kafka 消费成功；命令 payload、幂等键、内部 topic、目标地址和错误正文不会进入
 * data-sync，统一图也不会把这些内部字段返回给浏览器。</p>
 */
public record AgentRuntimeCommandObservation(
        boolean available,
        boolean found,
        String status,
        Integer attemptCount,
        LocalDateTime publishedAt,
        LocalDateTime updatedAt,
        String sourceStatus) {

    /** Agent Runtime 未能提供 outbox 快照时的保守结果。 */
    public static AgentRuntimeCommandObservation unavailable(String sourceStatus) {
        return new AgentRuntimeCommandObservation(false, false, null, null,
                null, null, sourceStatus);
    }

    /** 查询成功但没有找到目标 commandId。 */
    public static AgentRuntimeCommandObservation missing() {
        return new AgentRuntimeCommandObservation(true, false, null, null,
                null, null, "COMMAND_NOT_FOUND");
    }
}
