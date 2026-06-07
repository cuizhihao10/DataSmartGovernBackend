/**
 * @Author : Cui
 * @Date: 2026/06/07 15:34
 * @Description DataSmart Govern Backend - AgentToolActionPayloadReferenceVerificationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * 工具动作 payloadReference 复核结果。
 *
 * <p>该结果只表达“引用字符串是否像一个可被服务端继续读取的受控引用”，不会返回引用指向的真实参数。
 * 真实参数读取必须由后续 payload store/client 在服务端按 tenant、project、actor、run、用途和策略版本继续鉴权。</p>
 *
 * @param status 复核状态，例如 VERIFIED、REJECTED、MISSING。
 * @param verifiedForWriter 是否允许 writer 继续写入 outbox。
 * @param normalizedPayloadReference 规范化后的引用字符串。
 * @param referenceType 引用类型，例如 AGENT_PAYLOAD、AGENT_TOOL_AUDIT、ARTIFACT_REFERENCE。
 * @param acceptedEvidence 已接受的低敏证据。
 * @param issueCodes 阻断或风险代码。
 * @param summaryReasons 人读原因说明。
 * @param recommendedActions 下一步建议。
 */
public record AgentToolActionPayloadReferenceVerificationResult(
        String status,
        Boolean verifiedForWriter,
        String normalizedPayloadReference,
        String referenceType,
        List<String> acceptedEvidence,
        List<String> issueCodes,
        List<String> summaryReasons,
        List<String> recommendedActions
) {
}
