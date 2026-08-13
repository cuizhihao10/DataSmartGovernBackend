package com.czh.datasmart.govern.agent.service.autopilot;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentAutopilotRecoveryPolicyEvaluatorTest {

    private final AgentAutopilotRecoveryPolicyEvaluator evaluator = new AgentAutopilotRecoveryPolicyEvaluator();

    @Test
    void autoApprovesOnlyPreauthorizedLowRiskIdempotentAction() {
        AgentAutopilotRecoveryDecision decision = evaluator.evaluate(
                snapshot(), candidate("RETRY_EXECUTION", "LOW", true), state());

        assertEquals(AgentAutopilotRecoveryDecisionType.AUTO_APPROVED, decision.decision());
    }

    @Test
    void highRiskActionWaitsForApprovalEvenWhenActionIsNamedInPolicy() {
        AgentAutopilotRecoveryDecision decision = evaluator.evaluate(
                snapshot(), candidate("CHANGE_SCHEMA", "HIGH", false), state());

        assertEquals(AgentAutopilotRecoveryDecisionType.WAITING_APPROVAL, decision.decision());
    }

    @Test
    void actionOutsideAuthorizationIsRejected() {
        AgentAutopilotRecoveryDecision decision = evaluator.evaluate(
                snapshot(), candidate("EXPAND_DATA_SCOPE", "LOW", true), state());

        assertEquals(AgentAutopilotRecoveryDecisionType.REJECTED, decision.decision());
    }

    @Test
    void exhaustedLoopAndRepeatedErrorRequireAttention() {
        AgentAutopilotRecoveryLoopState exhausted = new AgentAutopilotRecoveryLoopState(
                5, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
                "sha256:error", 3, true, true, 0.91);

        AgentAutopilotRecoveryDecision decision = evaluator.evaluate(
                snapshot(), candidate("RETRY_EXECUTION", "LOW", true), exhausted);

        assertEquals(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED, decision.decision());
        assertEquals("RECOVERY_CYCLE_LIMIT_REACHED", decision.reasonCode());
    }

    @Test
    void lowConfidenceOrMissingEvidenceRequiresAttention() {
        AgentAutopilotRecoveryLoopState lowConfidence = new AgentAutopilotRecoveryLoopState(
                1, OffsetDateTime.now(ZoneOffset.UTC), "sha256:error", 1,
                false, true, 0.42);

        AgentAutopilotRecoveryDecision decision = evaluator.evaluate(
                snapshot(), candidate("RETRY_EXECUTION", "LOW", true), lowConfidence);

        assertEquals(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED, decision.decision());
        assertEquals("RECOVERY_EVIDENCE_MISSING", decision.reasonCode());
    }

    @Test
    void retryWithoutStructuredTransientFactsRequiresAttention() {
        AgentAutopilotRecoveryDecision decision = evaluator.evaluate(
                snapshot(), candidate("RETRY_EXECUTION", "LOW", true, Map.of()), state());

        assertEquals(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED, decision.decision());
        assertEquals("RECOVERY_AUTOMATIC_RETRY_FACTS_REQUIRED", decision.reasonCode());
    }

    private AgentAutopilotAuthorizationSnapshot snapshot() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AgentAutopilotAuthorizationSnapshot(
                "policy-1", "datasmart.autopilot.authorization.v1", "ACTIVE",
                "session-1", "run-1", 10L, 20L, 30L, "user-7", "user-7",
                "agent-master", "delegation-1", 5, 120, "LOW",
                List.of("RETRY_EXECUTION", "REPLAY_FAILED_SHARDS", "REFRESH_METADATA"),
                List.of("CHANGE_SCHEMA", "DELETE_DATA", "OVERWRITE_TARGET"),
                now.minusMinutes(1), now.plusDays(7), "sha256:policy");
    }

    private AgentAutopilotRecoveryCandidate candidate(String action, String risk, boolean idempotent) {
        return candidate(action, risk, idempotent, transientRetryFacts());
    }

    private AgentAutopilotRecoveryCandidate candidate(String action, String risk, boolean idempotent,
                                                      Map<String, Object> facts) {
        return new AgentAutopilotRecoveryCandidate(
                10L, 20L, 30L, "user-7", "agent-master", "delegation-1",
                action, risk, idempotent, "sha256:repair", "sha256:error", facts);
    }

    private Map<String, Object> transientRetryFacts() {
        return Map.of(
                "failureClass", "TRANSIENT_CONNECTOR_OR_WORKER",
                "retryable", true,
                "eligibleForAutomaticRetry", true,
                "failedObjectCount", 1,
                "rootCauseCodes", List.of("CONNECTOR_OR_NETWORK_UNAVAILABLE"));
    }

    private AgentAutopilotRecoveryLoopState state() {
        return new AgentAutopilotRecoveryLoopState(
                1, OffsetDateTime.now(ZoneOffset.UTC), "sha256:error", 1,
                true, true, 0.91);
    }
}
