/**
 * @Author : Cui
 * @Date: 2026/05/28 21:05
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxOperationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 工具事件 outbox 人工补偿请求。
 *
 * <p>该请求用于运维台或平台管理员对 FAILED/BLOCKED 事件执行人工动作，例如重新入队、忽略归档或追加处理备注。
 * reason 是必填业务说明，不只是普通备注：它会进入 outbox 最近处理摘要，用来解释为什么某条事件被重新投递或被人工终止。
 * operatorId 可由请求体显式提供，也可由 gateway 通过 {@code X-DataSmart-Actor-Id} 透传。</p>
 */
public record AgentToolExecutionEventOutboxOperationRequest(
        String reason,
        String operatorId
) {
}
