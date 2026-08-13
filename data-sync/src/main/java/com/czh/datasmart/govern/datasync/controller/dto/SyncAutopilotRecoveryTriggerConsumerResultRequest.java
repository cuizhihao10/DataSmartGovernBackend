/**
 * @Author : Cui
 * @Date: 2026/08/11 21:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerConsumerResultRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * Narrow internal callback contract for a durable Autopilot recovery trigger consumer result.
 *
 * <p>The URL carries the immutable event ID. This body carries only a finite status, one short reason code, an
 * optional case reference, the original current execution ID, and a compact retrieval proof. The proof tells
 * operators whether the model selected SEARCH or SKIP and, for SEARCH, exposes only an evidence count and digest.
 * It deliberately has no payload, model answer, citation text, raw exception, prompt, SQL, credential, broker,
 * or endpoint field.</p>
 *
 * <p>The controller converts status into a server enum and restricts the reason code to an uppercase enum-like
 * token. The service then recomputes the result digest and checks idempotency against the original outbox rather
 * than trusting a caller-provided receipt or digest.</p>
 *
 * @param status finite consumer outcome, such as RECOVERY_STARTED or ATTENTION_REQUIRED
 * @param reasonCode compact low-sensitive classification code, never a model explanation
 * @param caseId optional data-sync recovery case created before this callback
 * @param currentExecutionId execution that must equal the original trigger outbox execution
 * @param retrievalDecision model-selected SEARCH or SKIP; null only when planning did not complete
 * @param retrievalStrategy bounded retrieval strategy code; null only when planning did not complete
 * @param retrievalEvidenceCount grounded RAG evidence count; zero for SKIP
 * @param retrievalEvidenceDigest SHA-256 digest of grounded evidence IDs; null for SKIP
 */
public record SyncAutopilotRecoveryTriggerConsumerResultRequest(
        String status,
        String reasonCode,
        Long caseId,
        Long currentExecutionId,
        String retrievalDecision,
        String retrievalStrategy,
        Integer retrievalEvidenceCount,
        String retrievalEvidenceDigest) {
}
