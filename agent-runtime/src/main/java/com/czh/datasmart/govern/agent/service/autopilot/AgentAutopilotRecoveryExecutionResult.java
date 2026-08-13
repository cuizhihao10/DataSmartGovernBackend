/**
 * @Author : Cui
 * @Date: 2026/08/11 20:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryExecutionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

/**
 * 一条 Kafka 恢复触发完成 Java 治理后的低敏结果。
 *
 * <p>除了终态和定位 ID，该结果只携带模型是否检索、所选检索策略、证据数量和证据摘要。它不会
 * 携带 RAG answer、引用正文、原始日志、模型解释、工具参数或异常消息。这样 data-sync 可以把
 * SEARCH/SKIP 决策写入持久审计并公开给 E2E 和产品页面，同时不会把内部推理通道暴露成公共 API。</p>
 *
 * @param eventId durable trigger identity; malformed JSON can leave it absent
 * @param status finite governed outcome
 * @param reasonCode compact low-sensitive outcome code
 * @param caseId optional data-sync recovery case
 * @param currentExecutionId execution affected by the outcome
 * @param retrievalDecision model-selected SEARCH or SKIP decision, absent before planning
 * @param retrievalStrategy bounded strategy code selected by the planner
 * @param retrievalEvidenceCount grounded RAG evidence count; zero for SKIP
 * @param retrievalEvidenceDigest SHA-256 digest of RAG evidence IDs; absent for SKIP
 */
public record AgentAutopilotRecoveryExecutionResult(
        String eventId,
        String status,
        String reasonCode,
        Long caseId,
        Long currentExecutionId,
        String retrievalDecision,
        String retrievalStrategy,
        Integer retrievalEvidenceCount,
        String retrievalEvidenceDigest) {

    /**
     * Preserves the compact constructor for outcomes produced before a planner response exists.
     *
     * <p>Malformed JSON and authorization rejection cannot truthfully claim a model retrieval decision. This
     * constructor therefore leaves all retrieval fields absent instead of inventing {@code SKIP}. It has no
     * persistence or execution side effect; the data-sync callback performs the final validation.</p>
     */
    public AgentAutopilotRecoveryExecutionResult(
            String eventId,
            String status,
            String reasonCode,
            Long caseId,
            Long currentExecutionId) {
        this(eventId, status, reasonCode, caseId, currentExecutionId, null, null, null, null);
    }
}
