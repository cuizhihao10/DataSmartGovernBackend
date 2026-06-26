/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateLatestReceiptView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * 命令任务最终态对账使用的最新 worker receipt 证据视图。
 *
 * <p>该视图只展示对账必须使用的低敏证据：状态、结果、租户/项目/run/session 边界和 Java replay 游标。
 * 它不是 worker receipt 原文，也不是命令执行日志。特别注意：这里不会返回命令行、工具参数、stdout/stderr、
 * artifact 正文、SQL、prompt、样本数据、模型输出、凭据、token 明文、内部 endpoint 或签名 URL。</p>
 *
 * @param receiptFingerprint receipt identityKey 的短指纹，用于排障关联来源，不暴露原始幂等键。
 * @param commandId Java command outbox 命令 ID，是本次对账的主定位符。
 * @param taskId task-management 任务 ID，可能为空；为空时只能给出状态建议，不能自动拼完整任务回调。
 * @param taskRunId task-management 任务运行 ID，可能为空；用于区分同一任务的多次尝试。
 * @param executorId worker 执行器低敏身份。
 * @param auditId Agent 工具审计 ID，可能为空；未来自动回调 Agent 审计时需要该字段。
 * @param tenantId 租户边界。
 * @param projectId 项目边界。
 * @param actorId 操作者或服务账号边界。
 * @param runId Agent Run ID。
 * @param sessionId Agent Session ID。
 * @param toolCode 工具编码，只代表工具目录身份，不代表参数或目标。
 * @param taskStatus worker receipt 中的 task-management 状态摘要。
 * @param outcome worker receipt 低敏结果，例如 WORKER_PRECHECK_PASSED、EXECUTION_SUCCEEDED、EXECUTION_FAILED。
 * @param preCheckPassed 执行前检查是否通过。
 * @param sideEffectExecuted 是否已确认真实副作用完成。
 * @param errorCode 低敏机器错误码。
 * @param replaySequence Java runtime event projection 回放游标，用于选择最新事实。
 * @param consumedAt receipt 被 Java 控制面消费或接收的时间。
 * @param indexedAt receipt 索引写入时间。
 * @param payloadPolicy 低敏载荷策略。
 */
public record AgentCommandTaskFinalStateLatestReceiptView(
        String receiptFingerprint,
        String commandId,
        Long taskId,
        Long taskRunId,
        String executorId,
        String auditId,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        String taskStatus,
        String outcome,
        Boolean preCheckPassed,
        Boolean sideEffectExecuted,
        String errorCode,
        Long replaySequence,
        Instant consumedAt,
        Instant indexedAt,
        String payloadPolicy
) {
}
