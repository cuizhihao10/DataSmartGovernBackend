/**
 * @Author : Cui
 * @Date: 2026/05/28 21:05
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxOperationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * Agent 工具事件 outbox 人工补偿响应。
 *
 * <p>响应里同时返回 previousStatus/currentStatus，是为了让运维台清楚看到本次动作带来的状态变化。
 * record 是操作后的安全视图，不包含 payloadJson，避免补偿接口在默认权限下泄露服务间投递正文。</p>
 */
public record AgentToolExecutionEventOutboxOperationResponse(
        String action,
        String outboxId,
        String previousStatus,
        String currentStatus,
        String operatorId,
        String reason,
        Instant operatedAt,
        AgentToolExecutionEventOutboxRecordView record
) {
}
