/**
 * @Author : Cui
 * @Date: 2026/07/27 00:00
 * @Description DataSmart Govern Backend - AgentPostConfirmContinuationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Java 向 Python 续跑入口提交的结构化事实。
 *
 * <p>{@code toolResults} 必须来自 Java 工具执行事实源，而不是前端请求体；这保证模型只会看到已经由
 * 权限、审计和适配器状态机确认过的结果。</p>
 */
public record AgentPostConfirmContinuationRequest(
        String tenantId,
        String projectId,
        String actorId,
        String sessionId,
        String runId,
        String objective,
        String workspaceKey,
        String traceId,
        List<AgentToolExecutionResultView> toolResults) {
}
