/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;

/**
 * Java 控制面保存的 command worker lease 低敏事实。
 *
 * <p>它回答的是“当前谁可以处理 command”，不是“command 做了什么”。因此字段只包含 session/run/command/executor、
 * leaseVersion、fencingToken 和过期时间。`fencingToken` 是内部写回资格凭证，只能保存在受控 store 中，
 * 不能进入 runtime event、timeline、日志、Prometheus label 或前端响应。</p>
 *
 * @param leaseIdentityKey sessionId/runId/commandId 拼出的稳定唯一键。
 * @param sessionId Agent 会话 ID。
 * @param runId Agent 运行 ID。
 * @param commandId command outbox 指令 ID。
 * @param executorId 持有 lease 的 worker 低敏身份。
 * @param tenantId 租户边界。
 * @param projectId 项目边界。
 * @param actorId 触发者边界。
 * @param fencingToken 内部 fencing token，禁止投影到可回放事件。
 * @param leaseVersion 单调版本号；过期后重新领取必须递增。
 * @param leaseExpiresAt lease 过期时间。
 * @param acquiredAt 首次或本次重新领取时间。
 * @param updatedAt 最近更新时间。
 */
public record AgentCommandWorkerLeaseRecord(
        String leaseIdentityKey,
        String sessionId,
        String runId,
        String commandId,
        String executorId,
        String tenantId,
        String projectId,
        String actorId,
        String fencingToken,
        long leaseVersion,
        Instant leaseExpiresAt,
        Instant acquiredAt,
        Instant updatedAt
) {

    /**
     * 判断 lease 在指定时刻是否仍然有效。
     */
    public boolean activeAt(Instant now) {
        return leaseExpiresAt != null && now != null && leaseExpiresAt.isAfter(now);
    }

    /**
     * 判断当前 worker 是否是 lease 持有者。
     */
    public boolean heldBy(String executorId) {
        return this.executorId != null && this.executorId.equals(executorId);
    }
}
