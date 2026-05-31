/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentAsyncToolTaskStatusCallbackResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 异步工具任务状态回调响应。
 *
 * <p>响应体刻意保持很小：task-management 只需要知道 agent-runtime 已经接受回调，以及当前审计最终处于什么状态。
 * 完整审计详情仍应通过既有的工具审计查询接口读取，避免回调接口承担查询接口职责。</p>
 *
 * @param accepted true 表示回调已被 agent-runtime 接受并完成状态处理；false 预留给未来软拒绝场景。
 * @param auditId Agent 工具审计 ID。
 * @param state 当前 Agent 工具审计状态。
 * @param message 当前审计说明。
 */
public record AgentAsyncToolTaskStatusCallbackResponse(
        boolean accepted,
        String auditId,
        String state,
        String message) {
}
