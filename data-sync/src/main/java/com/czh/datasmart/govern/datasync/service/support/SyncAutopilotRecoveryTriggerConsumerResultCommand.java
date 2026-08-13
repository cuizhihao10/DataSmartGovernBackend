/**
 * @Author : Cui
 * @Date: 2026/08/11 21:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerConsumerResultCommand.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryConsumerResultStatus;

/**
 * Validated facts submitted by an authenticated Autopilot trigger consumer.
 *
 * <p>The controller turns the external status into a server enum and normalizes the reason code before creating
 * this command. The service still validates the values because future internal callers must not be able to bypass
 * the same persistence boundary. The command intentionally has no raw model output, payload, error body, or
 * evidence text, so those data types cannot reach the outbox schema through this API.</p>
 *
 * @param status finite consumer outcome enum
 * @param reasonCode compact uppercase reason code used for safe audit and metric grouping
 * @param caseId optional data-sync recovery case associated with the consumer outcome
 * @param currentExecutionId execution that must match the original outbox event
 * @param retrievalDecision validated SEARCH/SKIP decision, absent before planning
 * @param retrievalStrategy validated bounded strategy code, absent before planning
 * @param retrievalEvidenceCount grounded RAG evidence count; zero for SKIP
 * @param retrievalEvidenceDigest grounded evidence-ID digest; absent for SKIP
 */
public record SyncAutopilotRecoveryTriggerConsumerResultCommand(
        SyncAutopilotRecoveryConsumerResultStatus status,
        String reasonCode,
        Long caseId,
        Long currentExecutionId,
        String retrievalDecision,
        String retrievalStrategy,
        Integer retrievalEvidenceCount,
        String retrievalEvidenceDigest) {
}
