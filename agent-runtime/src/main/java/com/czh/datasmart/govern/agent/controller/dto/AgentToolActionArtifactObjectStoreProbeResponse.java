/**
 * @Author : Cui
 * @Date: 2026/06/26 23:11
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreProbeResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * artifact 对象存储探针响应。
 *
 * <p>响应只表达“对象是否可服务端读取、读取了多少 sample、是否截断、对象长度/类型/版本指纹是什么”。
 * 它故意不包含 sample 字节、不包含安全预览文本、不包含完整正文、不包含下载 URL、不包含 bucket/key。
 * 这样可以让 agent-runtime 先接入真实对象存储 adapter 的健康与存在性校验，同时不把对象存储能力误开放成
 * 任意文件读取 API。</p>
 *
 * @param probeAllowed true 表示 grant 复核通过、adapter 已执行且对象可用；false 表示缺授权、adapter 未启用或对象不可用。
 * @param decision 机器可读决策码，便于任务中心、Agent Host、审计系统和测试稳定判断原因。
 * @param commandId 被探针动作绑定的命令 ID，不包含命令行正文。
 * @param artifactReference 被探针动作绑定的低敏 artifact 引用。
 * @param artifactReferenceType 低敏引用类型，例如 MINIO_OBJECT。
 * @param readPurpose 服务端归一化后的读取目的。
 * @param requestedContentMode 服务端归一化后的读取形态。
 * @param maxReadableBytes grant 复核后允许下游读取的最大字节数。
 * @param probeLimitBytes 本次探针实际允许读取的 sample 字节上限。
 * @param objectStoreProbeExecuted true 表示 adapter 被调用；默认禁用实现会返回 false。
 * @param objectAvailable true 表示 adapter 确认对象存在且可读取 sample。
 * @param contentType adapter 识别出的低敏内容类型；不能包含 endpoint、路径或对象 key。
 * @param contentLengthBytes adapter 识别出的对象字节长度，允许为空。
 * @param sampledBytes 服务端实际处理的 sample 字节数，不代表响应返回了 sample 正文。
 * @param sampleTruncated true 表示 sample 只覆盖对象前缀或被 Host 硬上限裁剪。
 * @param sampleSha256Fingerprint sample 字节的短 SHA-256 指纹，只用于审计对账，不可还原正文。
 * @param objectVersionFingerprint adapter 返回的低敏版本指纹，不能是原始 versionId、etag 明文或对象路径。
 * @param bodyContentReturned 当前响应是否返回 artifact 正文；本接口固定为 false。
 * @param signedUrlIssued 当前响应是否签发签名 URL；本接口固定为 false。
 * @param bearerTokenIssued 当前响应是否签发 bearer token；本接口固定为 false。
 * @param previousGrantDecisionReference 调用方提交的上一阶段 grant 低敏引用。
 * @param verifiedGrantDecisionReference 本次服务端重新走 grant 校验后生成的低敏引用，用于证明当前时刻仍通过。
 * @param grantExpiresAtEpochMs 本次服务端 grant 复核的建议过期时间。
 * @param matchedReceiptFingerprint 匹配 receipt identityKey 的短摘要，便于排障但不泄露完整幂等键。
 * @param replaySequence runtime event replay 游标，用于定位 receipt 顺序。
 * @param receiptOutcome worker receipt 中的低敏执行结果。
 * @param tenantId 匹配 receipt 的租户 ID。
 * @param projectId 匹配 receipt 的项目 ID。
 * @param actorId 匹配 receipt 的触发 actor ID。
 * @param runId 匹配 receipt 的 runId。
 * @param sessionId 匹配 receipt 的 sessionId。
 * @param toolCode 匹配 receipt 的工具编码，不包含工具参数。
 * @param evidenceCodes 支持本次决策的低敏证据码。
 * @param issueCodes 拒绝、降级或不可用原因码。
 * @param recommendedActions 下一步建议，例如启用 MinIO adapter、补 durable grant store 或进入 final-check。
 * @param payloadPolicy 当前响应承诺的低敏载荷策略。
 */
public record AgentToolActionArtifactObjectStoreProbeResponse(
        boolean probeAllowed,
        String decision,
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String readPurpose,
        String requestedContentMode,
        Integer maxReadableBytes,
        Integer probeLimitBytes,
        boolean objectStoreProbeExecuted,
        boolean objectAvailable,
        String contentType,
        Long contentLengthBytes,
        Integer sampledBytes,
        boolean sampleTruncated,
        String sampleSha256Fingerprint,
        String objectVersionFingerprint,
        boolean bodyContentReturned,
        boolean signedUrlIssued,
        boolean bearerTokenIssued,
        String previousGrantDecisionReference,
        String verifiedGrantDecisionReference,
        Long grantExpiresAtEpochMs,
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
