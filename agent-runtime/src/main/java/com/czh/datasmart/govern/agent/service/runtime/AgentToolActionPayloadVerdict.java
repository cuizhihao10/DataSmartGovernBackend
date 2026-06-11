/**
 * @Author : Cui
 * @Date: 2026/06/11 00:00
 * @Description DataSmart Govern Backend - AgentToolActionPayloadVerdict.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * 工具动作 payload store 低敏判定结果。
 *
 * <p>verdict 的设计目标是“可审计、可解释、不可泄露”。它可以进入 writer 前复核、运维诊断、未来 dry-run executor
 * 或低敏 runtime event，但不能包含 payloadBody、SQL、prompt、样本数据、模型输出、凭证和内部 endpoint。
 * 这和成熟 Agent 工具调用平台的原则一致：控制面可以保存和传播治理结论，真实参数只能在服务端执行边界按需读取。</p>
 *
 * @param status 判定状态，例如 VERIFIED、REJECTED、MISSING、EXPIRED。
 * @param readableForWriter 是否允许 writer 继续写入 outbox。
 * @param payloadReference 受控引用。
 * @param referenceType 引用类型，当前主要是 AGENT_PAYLOAD。
 * @param runId 绑定的 run ID。
 * @param payloadKey run 内 payload key。
 * @param tenantId 绑定租户。
 * @param projectId 绑定项目。
 * @param actorId 绑定主体。
 * @param toolName 绑定工具名。
 * @param payloadBodyAvailable 真实 payload body 是否已物化。
 * @param payloadSizeBytes 真实 payload body 字节数，未物化时为 0。
 * @param metadataDigest 元数据摘要，不是 payload 正文。
 * @param acceptedEvidence 可进入低敏命令信封的接受证据。
 * @param issueCodes 阻断原因代码。
 * @param summaryReasons 人读说明。
 * @param recommendedActions 下一步建议。
 */
public record AgentToolActionPayloadVerdict(
        String status,
        Boolean readableForWriter,
        String payloadReference,
        String referenceType,
        String runId,
        String payloadKey,
        String tenantId,
        String projectId,
        String actorId,
        String toolName,
        Boolean payloadBodyAvailable,
        Integer payloadSizeBytes,
        String metadataDigest,
        List<String> acceptedEvidence,
        List<String> issueCodes,
        List<String> summaryReasons,
        List<String> recommendedActions
) {

    public AgentToolActionPayloadVerdict {
        acceptedEvidence = acceptedEvidence == null ? List.of() : List.copyOf(acceptedEvidence);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        summaryReasons = summaryReasons == null ? List.of() : List.copyOf(summaryReasons);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        payloadBodyAvailable = Boolean.TRUE.equals(payloadBodyAvailable);
        payloadSizeBytes = Math.max(0, payloadSizeBytes == null ? 0 : payloadSizeBytes);
    }
}
