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
        Map<String, Object> repairProposal,
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
                Map.of(),
                "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                message
        );
    }

    /**
     * 把“Python 已声明创建下一 Run，但 Java durable store 中没有该 Run”转换为可恢复的显式终态。
     *
     * <p>不能继续把原 {@code nextRunId} 返回给前端，否则页面会渲染一个必然 404 的确认按钮。这里保留模型给出的
     * 低敏诊断和修复建议（尤其是同名任务的建议名称），但清空悬空 Run 引用并要求前端重新生成审核计划。
     * 这样既不会自动执行未持久化动作，也不会让用户丢失已经形成的修复上下文。</p>
     *
     * @param continuation Python 返回的原始续跑结果
     * @return 去除悬空 nextRunId、保留公开修复建议的可重试结果
     */
    public static AgentPostConfirmContinuationView nextRunNotDurable(
            AgentPostConfirmContinuationView continuation) {
        String recoveryMessage = "Agent 已形成后续修复建议，但持久化校验没有找到对应 Run。"
                + "系统已阻止返回失效的确认入口，请使用保留的任务配置重新生成审核计划。";
        return new AgentPostConfirmContinuationView(
                "datasmart.post-confirm-continuation.v1",
                "FAILED_RETRYABLE",
                false,
                continuation.requestId(),
                continuation.sessionId(),
                continuation.sourceRunId(),
                null,
                false,
                "NEXT_RUN_NOT_DURABLE",
                recoveryMessage,
                continuation.modelSecondTurn(),
                continuation.durableLoop(),
                continuation.repairProposal(),
                continuation.payloadPolicy(),
                recoveryMessage
        );
    }

    /**
     * The reviewed business goal has reached its asynchronous hand-off point.
     * No extra model turn is needed after a task is scheduled, handed to CDC,
     * or submitted to the worker queue.
     */
    public static AgentPostConfirmContinuationView businessGoalReached() {
        return new AgentPostConfirmContinuationView(
                "datasmart.post-confirm-continuation.v1",
                "BUSINESS_GOAL_REACHED",
                false,
                null,
                null,
                null,
                null,
                false,
                "TASK_SUBMITTED_OR_SCHEDULED",
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                "同步任务已进入真实业务执行边界，无需等待任务终态或再次调用模型。"
        );
    }
}
