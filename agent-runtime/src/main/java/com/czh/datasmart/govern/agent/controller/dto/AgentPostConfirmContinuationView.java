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
        Map<String, Object> specialistVerificationExecution,
        Map<String, Object> postBridgeVerification,
        String payloadPolicy,
        String message) {

    /**
     * 兼容尚未关心六 Agent 后置复核字段的既有测试和调用代码。
     *
     * <p>生产 HTTP 反序列化使用上面的完整 canonical constructor；这个重载只为源码兼容补入两个空 Map，
     * 不会伪造 PRECHECK/MONITOR 已经运行。新代码需要展示或断言后置复核时必须读取完整字段。</p>
     */
    public AgentPostConfirmContinuationView(
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
        this(schemaVersion, status, continued, requestId, sessionId, sourceRunId, nextRunId,
                requiresConfirmation, stoppedReason, assistantReply, modelSecondTurn, durableLoop,
                repairProposal, Map.of(), Map.of(), payloadPolicy, message);
    }

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
                continuation.specialistVerificationExecution(),
                continuation.postBridgeVerification(),
                continuation.payloadPolicy(),
                recoveryMessage
        );
    }

    /**
     * 将不可信或不完整的 Python 后确认续跑响应降级为不可执行的可重试结果。
     *
     * <p>Java 控制面只信任自己已持久化的会话、Run、工具审计和后置复核契约。远端 HTTP 返回 2xx 并不代表
     * 它携带的 session/run 定位符、下一 Run 或“业务目标已完成”结论可以直接交给前端。因此该工厂刻意清空
     * {@code nextRunId}、确认标记、模型摘要和修复提案，仅保留 Java 已知的会话定位和可安全展示的固定说明。
     * 用户仍可基于已保存的本轮工具结果重新生成计划，但不能点击一个可能越权或悬空的操作入口。</p>
     *
     * @param sessionId 当前 Java 已知的会话 ID
     * @param sourceRunId 当前 Java 已知的源 Run ID
     * @param violationCode 由 Java 本地规则生成的低敏违约码，不能传入远端原始错误正文
     * @return 无可执行后续动作的 fail-closed 续跑视图
     */
    public static AgentPostConfirmContinuationView contractInvalid(
            String sessionId,
            String sourceRunId,
            String violationCode) {
        String code = violationCode != null && violationCode.matches("[A-Z0-9_]{1,80}")
                ? violationCode
                : "UNSPECIFIED";
        String message = "Agent 后确认续跑响应未通过控制面契约校验（" + code + "）。"
                + "系统已保留本轮 Java 工具执行结果；请重新生成审核计划或查看任务详情后重试。";
        return new AgentPostConfirmContinuationView(
                "datasmart.post-confirm-continuation.v1",
                "FAILED_RETRYABLE",
                false,
                null,
                sessionId,
                sourceRunId,
                null,
                false,
                "CONTINUATION_CONTRACT_INVALID",
                message,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                message
        );
    }
}
