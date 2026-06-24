/**
 * @Author : Cui
 * @Date: 2026/06/24 23:45
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxOperationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * Agent 异步命令 outbox 人工补偿响应。
 *
 * <p>响应同时返回 previousStatus/currentStatus，是为了让管理台和审计台清楚看到本次动作改变了什么。
 * record 使用 {@link AgentAsyncTaskCommandOutboxRecordView}，不会返回 payloadJson，避免补偿接口暴露服务间命令正文。</p>
 *
 * @param action 本次动作，例如 REQUEUE、DEAD_LETTER、IGNORE、NOTE。
 * @param outboxId 被操作的 outbox ID。
 * @param previousStatus 操作前状态。
 * @param currentStatus 操作后状态。
 * @param operatorId 操作人。
 * @param reason 已规范化后的低敏操作说明。
 * @param operatedAt 操作时间。
 * @param nextRetryAt 下次允许 dispatcher 领取时间；仅 requeue 延迟时有值。
 * @param record 操作后的低敏 outbox 记录视图。
 */
public record AgentAsyncTaskCommandOutboxOperationResponse(
        String action,
        String outboxId,
        String previousStatus,
        String currentStatus,
        String operatorId,
        String reason,
        Instant operatedAt,
        Instant nextRetryAt,
        AgentAsyncTaskCommandOutboxRecordView record
) {
}
