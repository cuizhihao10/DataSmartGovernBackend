/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackSuggestionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 命令任务最终态对账后的回调建议视图。
 *
 * <p>该 DTO 不是直接执行回调的请求体，而是“Agent Host 对 task-management / Agent 审计状态的建议”。
 * 之所以先输出建议而不是在查询接口里直接写回状态，是为了保持三个边界清晰：</p>
 * <p>1. task-management 仍然是业务任务生命周期的系统事实源；</p>
 * <p>2. agent-runtime 只根据 worker receipt 做低敏解释和建议，不跨库修改任务表；</p>
 * <p>3. 未来真正自动回调时，可以把本 DTO 映射为 {@code AgentAsyncToolTaskStatusCallbackRequest}，
 *    再由幂等键、审计权限和服务账号签名保护写入动作。</p>
 *
 * @param recommended true 表示当前 receipt 已经足以建议回调；false 表示还应等待更多事实或人工介入。
 * @param callbackStatus 建议回调状态，当前只使用 RUNNING、DEFERRED、SUCCEEDED、FAILED 这类既有状态，避免扩展旧审计状态机。
 * @param terminalCallback true 表示该回调会把任务推进到终态；false 表示只是刷新执行中/退避可见性。
 * @param callbackMessage 低敏人类说明，不包含命令、stdout/stderr、payload、SQL、prompt、模型输出或内部 endpoint。
 * @param callbackErrorCode 建议失败码；只有 FAILED/补偿场景通常需要。
 * @param outputSummary 建议成功摘要；只描述 receipt 已确认，不承载真实工具输出正文。
 * @param idempotencyKeyHint 建议幂等键，用 commandId + replaySequence 低敏组成，防止重复对账重复推进。
 * @param targetContract 建议映射到的目标合同，帮助学习者理解下一跳接口职责。
 * @param fieldMappingNotes 字段映射说明，强调哪些字段可用、哪些字段仍需由正式回调调用方补齐。
 */
public record AgentCommandTaskFinalStateCallbackSuggestionView(
        Boolean recommended,
        String callbackStatus,
        Boolean terminalCallback,
        String callbackMessage,
        String callbackErrorCode,
        String outputSummary,
        String idempotencyKeyHint,
        String targetContract,
        List<String> fieldMappingNotes
) {
}
