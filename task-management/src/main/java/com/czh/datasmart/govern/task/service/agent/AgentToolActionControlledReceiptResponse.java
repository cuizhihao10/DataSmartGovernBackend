/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledReceiptResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * agent-runtime 对受控工具动作 dry-run receipt 的接收响应。
 *
 * <p>task-management 只需要知道 agent-runtime 是否接收、是否重复以及对应的事件类型。完整事件内容
 * 继续由 agent-runtime 的 runtime event 查询接口负责，避免内部回调响应承担展示和审计查询职责。</p>
 */
public record AgentToolActionControlledReceiptResponse(
        boolean accepted,
        boolean duplicate,
        String identityKey,
        String eventType,
        String message
) {
}
