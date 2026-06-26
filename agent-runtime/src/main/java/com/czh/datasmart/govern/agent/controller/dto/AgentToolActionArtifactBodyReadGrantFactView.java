/**
 * @Author : Cui
 * @Date: 2026/06/26 21:06
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantFactView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * artifact 正文读取 grant fact 的低敏管理视图。
 *
 * <p>该 DTO 面向管理台、审计台和排障接口，展示“某个 grant 是否存在、属于哪个 command/artifact、
 * 当前是否 ACTIVE/EXPIRED/REVOKED”。它不是正文读取结果，也不是下载凭据。</p>
 *
 * <p>安全边界：本视图不包含 artifact 正文、sample bytes、stdout/stderr、bucket/key、签名 URL、
 * bearer token、prompt、SQL、工具参数、模型输出、凭据或内部 endpoint。</p>
 */
public record AgentToolActionArtifactBodyReadGrantFactView(
        /** 低敏 grant 引用。它可用于管理员撤销，但不能被当作 bearer token。 */
        String grantDecisionReference,

        /** grant 所绑定的 commandId。 */
        String commandId,

        /** 低敏 artifact 引用，不是对象存储 bucket/key。 */
        String artifactReference,

        /** artifact 引用类型，例如 MINIO_OBJECT。 */
        String artifactReferenceType,

        /** 读取目的，例如 TASK_RESULT_VIEW 或 AUDIT_REVIEW。 */
        String readPurpose,

        /** 请求的正文读取形态，例如 SAFE_RENDERED_PREVIEW。 */
        String requestedContentMode,

        /** 本 grant 允许的最大读取字节数。 */
        Integer maxReadableBytes,

        /** 租户边界。 */
        String tenantId,

        /** 项目边界。 */
        String projectId,

        /** actor 或服务账号边界。 */
        String actorId,

        /** Agent run 编号。 */
        String runId,

        /** Agent session 编号。 */
        String sessionId,

        /** 触发该 grant 的低敏工具编码。 */
        String toolCode,

        /** worker receipt 摘要指纹，不包含 receipt 原文。 */
        String matchedReceiptFingerprint,

        /** runtime event 或 receipt 回放序号。 */
        Long replaySequence,

        /** receipt 低敏结果状态。 */
        String receiptOutcome,

        /** grant 签发时间，毫秒时间戳。 */
        Long issuedAtEpochMs,

        /** grant 过期时间，毫秒时间戳。 */
        Long expiresAtEpochMs,

        /** 当前服务端事实状态。 */
        String status,

        /** 撤销时间，未撤销时为空。 */
        Long revokedAtEpochMs,

        /** 撤销操作者低敏标识。 */
        String revokedBy,

        /** 撤销原因码。 */
        String revokeReasonCode,

        /** payload 策略说明，提醒调用方这里不是 artifact 正文。 */
        String payloadPolicy
) {
}
