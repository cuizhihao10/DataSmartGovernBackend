/**
 * @Author : Cui
 * @Date: 2026/08/11 21:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerConsumerResultView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.time.LocalDateTime;

/**
 * Restricted durable result returned after an Autopilot consumer callback is accepted or idempotently replayed.
 *
 * <p>The view exposes only event identity, execution/case references, compact status and reason codes, the
 * server-computed integrity digest, and the first consumed time. It deliberately excludes the Kafka payload,
 * authorization snapshot, policy, evidence, Python answer, model output, raw exception, SQL, and credentials.</p>
 *
 * @param eventId immutable low-sensitive trigger identifier
 * @param currentExecutionId original execution verified against the outbox row
 * @param status persisted finite consumer outcome
 * @param reasonCode persisted short reason code
 * @param caseId optional recovery case identifier
 * @param resultDigest server-computed digest binding the durable result facts
 * @param retrievalDecision persisted SEARCH/SKIP decision, absent before planning
 * @param retrievalStrategy persisted bounded strategy code, absent before planning
 * @param retrievalEvidenceCount grounded RAG evidence count; zero for SKIP
 * @param retrievalEvidenceDigest grounded evidence-ID digest; absent for SKIP
 * @param consumedAt first durable acceptance time, unchanged by idempotent replays
 */
public record SyncAutopilotRecoveryTriggerConsumerResultView(
        String eventId,
        Long currentExecutionId,
        String status,
        String reasonCode,
        Long caseId,
        String resultDigest,
        String retrievalDecision,
        String retrievalStrategy,
        Integer retrievalEvidenceCount,
        String retrievalEvidenceDigest,
        LocalDateTime consumedAt) {
}
