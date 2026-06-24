/**
 * @Author : Cui
 * @Date: 2026/06/24 18:13
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 命令执行产物正文读取授权决策响应。
 *
 * <p>这里的 grant 不是文件下载令牌，也不是对象存储签名 URL。它只是 Java
 * Agent Runtime 控制面对“某个低敏 artifact 引用是否可以进入正文读取流程”的
 * 可审计决策。后续真正读取正文时，对象存储服务仍必须校验服务端身份、对象 ACL、
 * DLP/恶意内容扫描、下载审计、保留期和限速策略，不能把本响应当作绕过存储层
 * 授权的 bearer token。</p>
 *
 * @param granted true 表示允许进入对象存储正文读取前的下一段服务端流程。
 * @param decision 机器可读决策码，便于前端、外部 Agent、审计系统和自动化测试稳定判断原因。
 * @param commandId 被授权决策绑定的命令 ID，不包含命令行正文。
 * @param artifactReference 被授权决策绑定的低敏 artifact 引用，不是 URL、bucket/key 或正文。
 * @param artifactReferenceType 从匹配 receipt 中读取到的低敏引用类型。
 * @param readPurpose 服务端归一化后的读取目的，例如 TASK_RESULT_VIEW。
 * @param requestedContentMode 服务端归一化后的读取形态，例如 OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY。
 * @param maxReadableBytes 服务端允许下游对象存储读取的最大字节数上限。
 * @param grantDecisionReference 低敏决策引用，用于审计串联；它不是 bearer token，不能单独换取正文。
 * @param grantExpiresAtEpochMs 决策引用的建议过期时间，后续持久化实现应以服务端记录为准。
 * @param artifactMetadataAuthorized true 表示第一道 metadata 归属校验已经通过。
 * @param bodyContentReturned 当前响应是否返回了正文；本阶段固定为 false。
 * @param signedUrlIssued 当前响应是否签发对象存储 URL；本阶段固定为 false。
 * @param bearerTokenIssued 当前响应是否签发 bearer token；本阶段固定为 false。
 * @param objectStoreReadStillRequired true 表示后续仍需对象存储服务执行最终读取授权。
 * @param matchedReceiptFingerprint 匹配 receipt identityKey 的短摘要，便于排障但不泄露完整幂等键。
 * @param replaySequence runtime event replay 游标，用于定位 receipt 顺序。
 * @param receiptOutcome worker receipt 中的低敏执行结果。
 * @param tenantId 匹配 receipt 的租户 ID。
 * @param projectId 匹配 receipt 的项目 ID。
 * @param actorId 匹配 receipt 的触发 actor ID。
 * @param runId 匹配 receipt 的 runId。
 * @param sessionId 匹配 receipt 的 sessionId。
 * @param toolCode 匹配 receipt 的工具编码，不包含工具参数。
 * @param evidenceCodes 支持授权的低敏证据码。
 * @param issueCodes 拒绝或降级原因码。
 * @param recommendedActions 下一步建议，例如补审批、降低读取模式或等待 artifact 落盘。
 * @param payloadPolicy 当前响应承诺的低敏载荷策略。
 */
public record AgentToolActionArtifactBodyReadGrantResponse(
        boolean granted,
        String decision,
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String readPurpose,
        String requestedContentMode,
        Integer maxReadableBytes,
        String grantDecisionReference,
        Long grantExpiresAtEpochMs,
        boolean artifactMetadataAuthorized,
        boolean bodyContentReturned,
        boolean signedUrlIssued,
        boolean bearerTokenIssued,
        boolean objectStoreReadStillRequired,
        String matchedReceiptFingerprint,
        Long replaySequence,
        String receiptOutcome,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions,
        String payloadPolicy
) {
}
