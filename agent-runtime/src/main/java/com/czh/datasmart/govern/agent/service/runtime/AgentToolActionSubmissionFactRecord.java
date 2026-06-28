/**
 * @Author : Cui
 * @Date: 2026/06/28 21:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Agent 受控工具真实提交事实记录。
 *
 * <p>这条记录是 commandId 到真实下游副作用之间的“低敏对账事实”。
 * 它不是工具参数缓存，不是 payload body，不是 data-quality 响应全文，也不是模型输出。
 * 它只保存后续防重、恢复、审计和运营排障必须使用的机器字段。</p>
 *
 * <p>字段设计原则：
 * 1. commandId/idempotencyKey：用于防重复提交；
 * 2. tenant/project/actor/run/session：用于租户、项目、运行上下文边界校验；
 * 3. payloadReference/confirmationId/policyVersion：用于证明真实提交来自已物化 payload 与审批事实；
 * 4. status/outcome/downstreamTaskId：用于恢复时回答“提交到哪一步了”；
 * 5. issueCodes/recommendedActions/lowSensitiveMessage：只保存低敏机器诊断，不保存正文。</p>
 */
public record AgentToolActionSubmissionFactRecord(
        /** 稳定主键，当前默认使用 `tool-action-submission:{commandId}`，便于未来支持多工具类型。 */
        String submissionIdentityKey,

        /** command outbox 的 commandId，是本提交事实的核心幂等定位键。 */
        String commandId,

        /** worker/command 侧幂等键；为空时可以回退 commandId，但生产环境建议始终显式传入。 */
        String idempotencyKey,

        /** Agent 会话 ID，只用于低敏控制面定位，不保存对话内容。 */
        String sessionId,

        /** Agent run ID，用于把提交事实限定在一次运行链路内。 */
        String runId,

        /** 工具审计 ID，用于连接工具调用审计、outbox 与提交事实。 */
        String auditId,

        /** 工具编码，例如 quality.remediation.task.draft。 */
        String toolCode,

        /** 租户边界，跨租户查询或复用提交事实时必须匹配。 */
        String tenantId,

        /** 项目边界，避免一个项目的治理提交事实被另一个项目误用。 */
        String projectId,

        /** 触发者边界，当前作为低敏审计字段保存。 */
        String actorId,

        /** 服务端 payload 引用，只保存引用，不保存 payload body。 */
        String payloadReference,

        /** 人审/确认事实 ID，只保存低敏引用，不保存审批意见正文。 */
        String confirmationId,

        /** 工具执行策略版本，用于确认提交时采用了哪套准入规则。 */
        String policyVersion,

        /** 下游服务名，例如 data-quality；只保存服务逻辑名，不保存内部 URL。 */
        String targetService,

        /** 下游端点模板，例如 /quality-rules/remediation-tasks；不保存完整内部地址。 */
        String targetEndpoint,

        /** 当前提交事实状态。 */
        AgentToolActionSubmissionStatus status,

        /** 是否已经开始真实副作用调用。UNKNOWN 场景下该字段通常为 true。 */
        Boolean sideEffectStarted,

        /** 是否已经确认真实副作用成功落地。 */
        Boolean sideEffectExecuted,

        /** 低敏结果编码，例如 EXECUTION_SUCCEEDED、EXECUTION_SKIPPED。 */
        String outcome,

        /** 下游真实任务 ID；只保存数字关联键，不保存任务正文。 */
        Long downstreamTaskId,

        /** 下游任务状态摘要，例如 PENDING。 */
        String downstreamTaskStatus,

        /** 低敏机器错误码，用于聚合和恢复建议。 */
        String errorCode,

        /** 低敏问题码集合，不保存异常正文、SQL、prompt 或样本。 */
        List<String> issueCodes,

        /** 低敏推荐动作集合，用于运营排障提示。 */
        List<String> recommendedActions,

        /** 低敏消息摘要，长度受控且经过敏感模式过滤。 */
        String lowSensitiveMessage,

        /** 第一次登记提交意图的时间。 */
        Instant firstSubmittedAt,

        /** 最近一次状态更新时间。 */
        Instant lastUpdatedAt
) {

    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_SUBMISSION_FACT_NO_PAYLOAD_NO_PROMPT_NO_SQL_NO_ENDPOINT";

    public AgentToolActionSubmissionFactRecord {
        submissionIdentityKey = safeText(submissionIdentityKey, 220);
        commandId = requireText(commandId, 180, "commandId");
        idempotencyKey = safeText(idempotencyKey, 180);
        sessionId = safeText(sessionId, 180);
        runId = safeText(runId, 180);
        auditId = safeText(auditId, 200);
        toolCode = safeText(toolCode, 180);
        tenantId = safeText(tenantId, 80);
        projectId = safeText(projectId, 80);
        actorId = safeText(actorId, 120);
        payloadReference = safeText(payloadReference, 260);
        confirmationId = safeText(confirmationId, 220);
        policyVersion = safeText(policyVersion, 120);
        targetService = safeText(targetService, 120);
        targetEndpoint = safeEndpointTemplate(targetEndpoint, 180);
        status = status == null ? AgentToolActionSubmissionStatus.SUBMITTING : status;
        sideEffectStarted = Boolean.TRUE.equals(sideEffectStarted);
        sideEffectExecuted = Boolean.TRUE.equals(sideEffectExecuted);
        outcome = safeText(outcome, 120);
        downstreamTaskId = downstreamTaskId == null || downstreamTaskId < 0 ? null : downstreamTaskId;
        downstreamTaskStatus = safeText(downstreamTaskStatus, 80);
        errorCode = safeText(errorCode, 160);
        issueCodes = safeList(issueCodes, 20, 160);
        recommendedActions = safeList(recommendedActions, 10, 220);
        lowSensitiveMessage = safeText(lowSensitiveMessage, 300);
        firstSubmittedAt = firstSubmittedAt == null ? Instant.now() : firstSubmittedAt;
        lastUpdatedAt = lastUpdatedAt == null ? firstSubmittedAt : lastUpdatedAt;
        if (submissionIdentityKey == null) {
            submissionIdentityKey = identityKey(commandId);
        }
    }

    /**
     * 构造提交事实主键。
     *
     * <p>主键不包含租户、项目或 actor，是因为 commandId 本身来自 agent-runtime outbox，
     * 应当全局唯一。租户和项目仍作为独立字段保存，用于查询与恢复校验。</p>
     */
    public static String identityKey(String commandId) {
        return "tool-action-submission:" + requireText(commandId, 180, "commandId");
    }

    /**
     * 生成状态更新后的新记录。
     *
     * @param newStatus 新提交状态。
     * @param started 是否已经开始调用下游。
     * @param executed 是否已经确认副作用成功落地。
     * @param newOutcome 低敏结果编码。
     * @param taskId 下游任务 ID。
     * @param taskStatus 下游任务状态摘要。
     * @param newErrorCode 低敏错误码。
     * @param newIssueCodes 低敏问题码。
     * @param newRecommendedActions 低敏推荐动作。
     * @param message 低敏消息摘要。
     * @param updatedAt 更新时间。
     * @return 更新后的不可变记录。
     */
    public AgentToolActionSubmissionFactRecord transitionTo(
            AgentToolActionSubmissionStatus newStatus,
            boolean started,
            boolean executed,
            String newOutcome,
            Long taskId,
            String taskStatus,
            String newErrorCode,
            List<String> newIssueCodes,
            List<String> newRecommendedActions,
            String message,
            Instant updatedAt) {
        return new AgentToolActionSubmissionFactRecord(
                submissionIdentityKey,
                commandId,
                idempotencyKey,
                sessionId,
                runId,
                auditId,
                toolCode,
                tenantId,
                projectId,
                actorId,
                payloadReference,
                confirmationId,
                policyVersion,
                targetService,
                targetEndpoint,
                newStatus,
                started,
                executed,
                newOutcome,
                taskId,
                taskStatus,
                newErrorCode,
                newIssueCodes,
                newRecommendedActions,
                message,
                firstSubmittedAt,
                updatedAt == null ? Instant.now() : updatedAt
        );
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        String text = safeText(value, maxLength);
        if (text == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static String safeEndpointTemplate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.contains("://")) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static List<String> safeList(List<String> values, int maxSize, int maxLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> safeText(value, maxLength))
                .filter(value -> value != null && !value.isBlank())
                .limit(maxSize)
                .toList();
    }

    private static String safeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (looksSensitive(text)) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static boolean looksSensitive(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("prompt:")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("jdbc:")
                || lower.contains("http://")
                || lower.contains("https://");
    }
}
