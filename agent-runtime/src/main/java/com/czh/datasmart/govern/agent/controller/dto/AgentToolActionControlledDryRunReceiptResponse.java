/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunReceiptResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 受控工具动作 dry-run receipt 接收结果。
 *
 * <p>响应保持很小，只告诉 task-management：agent-runtime 是否接收了 receipt、是否是重复 receipt、
 * 对应的 runtime event 类型和幂等键。完整 timeline 仍通过 runtime event 查询接口读取，避免内部回调接口
 * 变成查询接口或暴露过多审计细节。</p>
 *
 * @param accepted true 表示请求契约通过并已尝试写入 runtime event projection。
 * @param duplicate true 表示 identityKey 已经存在，本次属于幂等重复回写。
 * @param identityKey agent-runtime 用于 runtime event projection 去重的稳定键。
 * @param eventType 写入的 runtime event 类型，便于 task-management 日志排查。
 * @param message 面向运维的低敏处理说明。
 */
public record AgentToolActionControlledDryRunReceiptResponse(
        boolean accepted,
        boolean duplicate,
        String identityKey,
        String eventType,
        String message
) {
}
