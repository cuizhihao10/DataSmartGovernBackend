/**
 * @Author : Cui
 * @Date: 2026/06/07 15:34
 * @Description DataSmart Govern Backend - AgentToolActionFactEvidenceVerificationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * 工具动作人工事实证据复核结果。
 *
 * <p>人工事实包括审批确认、用户澄清、未来可能扩展的风险豁免或运营复核。
 * 当前结果只校验 ID 形态和低敏边界，不代表已经从 permission-admin 或 confirmation store 成功回查事实正文。</p>
 *
 * @param status 复核状态，例如 VERIFIED_OR_NOT_REQUIRED、REJECTED。
 * @param verifiedForWriter 是否允许 writer 继续写入 outbox。
 * @param acceptedEvidence 已接受的低敏证据。
 * @param issueCodes 阻断或风险代码。
 * @param summaryReasons 人读原因说明。
 * @param recommendedActions 下一步建议。
 */
public record AgentToolActionFactEvidenceVerificationResult(
        String status,
        Boolean verifiedForWriter,
        List<String> acceptedEvidence,
        List<String> issueCodes,
        List<String> summaryReasons,
        List<String> recommendedActions
) {
}
