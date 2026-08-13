/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Run 确认执行结果。
 *
 * <p>该响应既用于首次确认的正常返回，也用于浏览器因网络中断而使用相同幂等键重试时的持久回放。
 * {@code autopilotSnapshot} 只包含用户需要看到的授权上限，不包含租户委派、策略摘要、工具参数或日志正文；
 * 因此前端可以据此展示“系统可自动恢复到什么边界”，但不能把该视图当作新的执行凭证。</p>
 */
public record AgentRunConfirmedExecutionResponse(
        String sessionId,
        String runId,
        String runState,
        int plannedCount,
        int succeededCount,
        int failedCount,
        List<AgentToolExecutionResultView> toolResults,
        List<AgentToolExecutionFailureView> failures,
        List<String> nextActions,
        String assistantReply,
        String answerMode,
        String modelProviderStatus,
        AgentPostConfirmContinuationView continuation,
        AgentAutopilotSnapshotView autopilotSnapshot) {
}
