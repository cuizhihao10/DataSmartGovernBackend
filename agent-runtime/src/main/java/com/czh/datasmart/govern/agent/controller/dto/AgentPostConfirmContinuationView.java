/**
 * @Author : Cui
 * @Date: 2026/07/27 00:00
 * @Description DataSmart Govern Backend - AgentPostConfirmContinuationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.Map;

/**
 * 确认后续跑的低敏结果视图。
 *
 * <p>这里只返回模型公开摘要、下一 Run 引用和 durable loop 计数，不返回 prompt、隐藏思维链、工具参数、
 * 数据源目录正文或 Provider 原始响应。</p>
 */
public record AgentPostConfirmContinuationView(
        String schemaVersion,
        String status,
        Boolean continued,
        String requestId,
        String sessionId,
        String sourceRunId,
        String nextRunId,
        Boolean requiresConfirmation,
        String stoppedReason,
        String assistantReply,
        Map<String, Object> modelSecondTurn,
        Map<String, Object> durableLoop,
        String payloadPolicy,
        String message) {

    public static AgentPostConfirmContinuationView disabled() {
        return new AgentPostConfirmContinuationView(
                "datasmart.post-confirm-continuation.v1",
                "DISABLED",
                false,
                null,
                null,
                null,
                null,
                false,
                "CLIENT_DISABLED",
                null,
                Map.of(),
                Map.of(),
                "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                "确认后续跑客户端未启用，本次只保留已成功的 Java 工具结果。"
        );
    }

    public static AgentPostConfirmContinuationView failed(String message) {
        return new AgentPostConfirmContinuationView(
                "datasmart.post-confirm-continuation.v1",
                "FAILED_RETRYABLE",
                false,
                null,
                null,
                null,
                null,
                false,
                "PYTHON_CONTINUATION_UNAVAILABLE",
                null,
                Map.of(),
                Map.of(),
                "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                message
        );
    }
}
