/**
 * @Author : Cui
 * @Date: 2026/06/28 22:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Agent 工具真实提交事实的低敏视图。
 *
 * <p>该视图用于运维台、智能网关和后续自动补偿 worker 查看“某个 command 的真实副作用提交走到哪一步”。
 * 它不是 payload 查询接口，也不是下游任务详情接口，因此不能返回 payload body、治理草案正文、SQL、prompt、
 * 样本数据、模型输出、凭据、stdout/stderr、完整内部 URL 或完整工具参数。</p>
 *
 * <p>几个字段的设计意图：</p>
 * <p>1. `submissionFingerprint/payloadReferenceFingerprint/confirmationFingerprint` 使用短指纹，便于排障串联，
 * 但避免把完整内部引用直接暴露到控制台；</p>
 * <p>2. `commandId/idempotencyKey` 是低敏机器键，用于跨服务对账时定位 task-management 是否已经创建任务；</p>
 * <p>3. `targetEndpoint` 只允许是端点模板，例如 `/quality-rules/remediation-tasks`，不能是完整内部地址；</p>
 * <p>4. `requiresManualReconciliation` 表示当前是否还需要人工对账，主要服务于 `UNKNOWN` 状态；</p>
 * <p>5. `reusableResponse` 表示重复提交命中该事实时，agent-runtime 是否可以直接复用低敏结果而不再调用下游。</p>
 */
public record AgentToolActionSubmissionFactView(
        String submissionFingerprint,
        String commandId,
        String idempotencyKey,
        String sessionId,
        String runId,
        String auditId,
        String toolCode,
        String tenantId,
        String projectId,
        String actorId,
        String payloadReferenceFingerprint,
        String confirmationFingerprint,
        String policyVersion,
        String targetService,
        String targetEndpoint,
        String status,
        Boolean sideEffectStarted,
        Boolean sideEffectExecuted,
        String outcome,
        Long downstreamTaskId,
        String downstreamTaskStatus,
        String errorCode,
        List<String> issueCodes,
        List<String> recommendedActions,
        String lowSensitiveMessage,
        Instant firstSubmittedAt,
        Instant lastUpdatedAt,
        String payloadPolicy,
        Boolean requiresManualReconciliation,
        Boolean reusableResponse
) {
}
