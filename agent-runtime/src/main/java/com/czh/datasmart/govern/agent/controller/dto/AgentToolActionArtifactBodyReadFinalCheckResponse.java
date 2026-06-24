/**
 * @Author : Cui
 * @Date: 2026/06/24 20:42
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadFinalCheckResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 命令执行 artifact 正文读取最终回查响应。
 *
 * <p>本响应的核心语义是“服务端最终回查已经完成，并且最多只返回安全预览”。它不是文件下载响应，
 * 不签发对象存储 URL，不返回 bearer token，不返回 MinIO bucket/key，也不保证候选对象的完整正文可被读取。
 * 真实生产实现中，对象存储服务仍应把本响应视为 Host 控制面的一项证据，再结合对象 ACL、DLP、恶意内容扫描、
 * 下载审计、保留期和限速策略做最终动作。</p>
 *
 * <p>为什么允许 `safePreviewText`？因为很多商业场景需要在审批、任务结果页或审计页展示短预览，
 * 但不能把 stdout/stderr、原始报告、SQL、prompt 或内部地址完整塞回 Agent 上下文。这里的预览必须由下游服务
 * 先脱敏，再由 Java Host 二次裁剪和敏感标记拦截，因此它和“artifact 完整正文”是两个不同安全等级的概念。</p>
 *
 * @param allowed true 表示 final-check 允许本次安全预览或后续对象存储服务继续执行受控读取流程。
 * @param decision 机器可读决策码，用于前端、审计、测试和后续补偿流程稳定判断。
 * @param commandId 被回查的命令 ID，不包含命令行正文。
 * @param artifactReference 被回查的低敏 artifact 引用，不是 URL、bucket/key 或文件正文。
 * @param artifactReferenceType 从 receipt 归属链路确认后的引用类型。
 * @param readPurpose 服务端归一化后的读取目的。
 * @param requestedContentMode 服务端归一化后的读取形态。
 * @param grantMaxReadableBytes body-read grant 允许的最大读取字节数。
 * @param previewLimitBytes 本次安全预览实际采用的最大字节数上限。
 * @param previewBytes 本次返回预览文本的 UTF-8 字节数。
 * @param previewTruncated true 表示候选预览被服务端按字节上限裁剪。
 * @param safePreviewReturned true 表示响应包含安全预览文本；false 表示只返回决策与证据。
 * @param safePreviewText 经过下游脱敏和 Java Host 裁剪后的短预览；不是完整 artifact body。
 * @param bodyContentReturned 当前是否返回完整正文；本阶段固定为 false。
 * @param objectStoreReadVerified true 表示已执行 Host 控制面的最终回查；不代表对象存储 ACL 已被绕过。
 * @param signedUrlIssued 当前是否签发对象存储 URL；本阶段固定为 false。
 * @param bearerTokenIssued 当前是否签发 bearer token；本阶段固定为 false。
 * @param previousGrantDecisionReference 调用方提交的上一阶段 grant 审计引用，用于串联排障。
 * @param verifiedGrantDecisionReference 本次回查重新通过 grant 服务得到的当前决策引用。
 * @param grantExpiresAtEpochMs 当前 grant 决策的建议过期时间，后续 durable grant store 应以服务端记录为准。
 * @param candidateContentType 候选内容类型，只用于低敏展示和策略判断。
 * @param candidateContentLengthBytes 候选对象大小，用于说明预览是否只是大对象的一小段。
 * @param matchedReceiptFingerprint 匹配 receipt identityKey 的短摘要，便于排障但不泄露完整幂等键。
 * @param replaySequence runtime event replay 游标，用于定位 receipt 顺序。
 * @param receiptOutcome worker receipt 中的低敏执行结果。
 * @param tenantId 匹配 receipt 的租户 ID。
 * @param projectId 匹配 receipt 的项目 ID。
 * @param actorId 匹配 receipt 的触发 actor ID。
 * @param runId 匹配 receipt 的 runId。
 * @param sessionId 匹配 receipt 的 sessionId。
 * @param toolCode 匹配 receipt 的工具编码，不包含工具实参。
 * @param evidenceCodes 支持放行或裁剪的低敏证据码。
 * @param issueCodes 拒绝、降级或裁剪原因码。
 * @param recommendedActions 下一步建议，例如降低预览大小、补 DLP 扫描或等待 artifact 落盘。
 * @param payloadPolicy 当前响应承诺的低敏载荷策略。
 */
public record AgentToolActionArtifactBodyReadFinalCheckResponse(
        boolean allowed,
        String decision,
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String readPurpose,
        String requestedContentMode,
        Integer grantMaxReadableBytes,
        Integer previewLimitBytes,
        Integer previewBytes,
        boolean previewTruncated,
        boolean safePreviewReturned,
        String safePreviewText,
        boolean bodyContentReturned,
        boolean objectStoreReadVerified,
        boolean signedUrlIssued,
        boolean bearerTokenIssued,
        String previousGrantDecisionReference,
        String verifiedGrantDecisionReference,
        Long grantExpiresAtEpochMs,
        String candidateContentType,
        Long candidateContentLengthBytes,
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
