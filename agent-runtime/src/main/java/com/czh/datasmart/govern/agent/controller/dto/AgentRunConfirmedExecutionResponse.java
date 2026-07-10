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
 */
public record AgentRunConfirmedExecutionResponse(
        String sessionId,
        String runId,
        String runState,
        int plannedCount,
        int succeededCount,
        int failedCount,
        List<AgentToolExecutionResultView> toolResults,
        List<String> nextActions) {
}
