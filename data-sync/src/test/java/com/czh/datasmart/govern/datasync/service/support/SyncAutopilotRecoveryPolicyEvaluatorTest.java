/**
 * @Author : Cui
 * @Date: 2026/08/10 10:00
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryPolicyEvaluatorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SyncAutopilotRecoveryPolicyEvaluatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);
    private static final String FINGERPRINT = "a".repeat(64);

    private final SyncAutopilotRecoveryPolicyEvaluator evaluator = new SyncAutopilotRecoveryPolicyEvaluator();

    @Test
    void evaluateShouldAutoApproveOnlyWhitelistedLowRiskRecovery() {
        SyncAutopilotRecoveryPolicyDecision decision = evaluator.evaluate(
                policy("RETRY_EXECUTION", "RESUME_FROM_CHECKPOINT", "REPLAY_FROM_CHECKPOINT"),
                request(1001L, SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW, 1, 0, 95, true));

        assertThat(decision.state()).isEqualTo(SyncAutopilotRecoveryCaseState.AUTO_APPROVED);
        assertThat(decision.authorizationDigest()).hasSize(64);
        assertThat(decision.policyDigest()).hasSize(64);
        assertThat(decision.deadlineAt()).isEqualTo(NOW.plusMinutes(10));
    }

    @Test
    void evaluateShouldAcceptTheCanonicalAgentRuntimeAuthorizationSnapshot() {
        String canonicalPolicy = """
                {
                  "policyId":"aap_20260811",
                  "executionMode":"AUTOPILOT",
                  "tenantId":7,
                  "applicationId":20,
                  "projectId":101,
                  "maxRecoveryCycles":5,
                  "maxTotalDurationMinutes":120,
                  "maxAutomaticRiskLevel":"LOW",
                  "allowedRecoveryActions":["RETRY_EXECUTION","RESUME_FROM_CHECKPOINT"],
                  "requireApprovalFor":["CHANGE_SCHEMA","DELETE_DATA"],
                  "expiresAt":"2026-08-12T12:00:00Z"
                }
                """;

        SyncAutopilotRecoveryPolicyDecision automatic = evaluator.evaluate(
                canonicalPolicy,
                request(1001L, SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW, 1, 0, 95, true));
        SyncAutopilotRecoveryPolicyDecision highRisk = evaluator.evaluate(
                canonicalPolicy,
                request(1001L, SyncAutopilotRecoveryAction.CHANGE_SCHEMA,
                        SyncAutopilotRiskLevel.HIGH, 1, 0, 95, true));

        assertThat(automatic.state()).isEqualTo(SyncAutopilotRecoveryCaseState.AUTO_APPROVED);
        assertThat(highRisk.state()).isEqualTo(SyncAutopilotRecoveryCaseState.WAITING_APPROVAL);
    }

    /** 高级自动修复必须把当前双主体精确绑定到首次授权，不能只校验内部服务令牌。 */
    @Test
    void principalBindingShouldRequireActiveUserAgentAndDelegationFacts() {
        String policy = """
                {
                  "state":"ACTIVE","userId":"501","actorId":"501",
                  "agentId":"RECOVERY_AGENT","delegationId":"delegation-1"
                }
                """;

        assertThat(evaluator.matchesPrincipalBinding(
                policy, "501", "RECOVERY_AGENT", "delegation-1")).isTrue();
        assertThat(evaluator.matchesPrincipalBinding(
                policy, "501", "RECOVERY_AGENT", "delegation-forged")).isFalse();
        assertThat(evaluator.matchesPrincipalBinding(
                policy.replace("ACTIVE", "REVOKED"),
                "501", "RECOVERY_AGENT", "delegation-1")).isFalse();
    }

    /**
     * +08:00 时区的 17:00 对应 UTC 09:00；相对于固定的 UTC 10:00 测试时钟，该授权已经过期。
     */
    @Test
    void evaluateShouldCompareOffsetExpiryAsAnInstantInsteadOfServerLocalTime() {
        SyncAutopilotRecoveryPolicyDecision decision = evaluator.evaluate(
                policyWithExpiry("2026-08-10T17:00:00+08:00", "RETRY_EXECUTION"),
                request(1001L, SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW, 1, 0, 95, true));

        assertThat(decision.state()).isEqualTo(SyncAutopilotRecoveryCaseState.REJECTED);
    }

    /** 调用方不能通过提交更晚的截止时间扩大已有授权窗口。 */
    @Test
    void evaluateShouldRejectADeadlineBeyondThePolicyWindow() {
        SyncAutopilotRecoveryPolicyDecision decision = evaluator.evaluate(
                policy("RETRY_EXECUTION"),
                new SyncAutopilotRecoveryEvaluationRequest(
                        SyncAutopilotExecutionMode.AUTOPILOT,
                        7L,
                        101L,
                        1001L,
                        1,
                        NOW.plusMinutes(11),
                        FINGERPRINT,
                        0,
                         SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW,
                        FINGERPRINT,
                        "receipt-deadline-boundary",
                        95,
                true,
                true,
                NOW));

        assertThat(decision.state()).isEqualTo(SyncAutopilotRecoveryCaseState.REJECTED);
        assertThat(decision.deadlineAt()).isEqualTo(NOW.plusMinutes(10));
    }

    /** 更短的截止时间会收窄授权盒范围，因此属于合法请求。 */
    @Test
    void evaluateShouldAcceptADeadlineShorterThanThePolicyWindow() {
        SyncAutopilotRecoveryPolicyDecision decision = evaluator.evaluate(
                policy("RETRY_EXECUTION"),
                requestWithDeadline(NOW.plusMinutes(5)));

        assertThat(decision.state()).isEqualTo(SyncAutopilotRecoveryCaseState.AUTO_APPROVED);
        assertThat(decision.deadlineAt()).isEqualTo(NOW.plusMinutes(5));
    }

    /**
     * PostgreSQL 会把纳秒四舍五入为微秒；同一截止时间写库后多出的 100ns 不能被误判成扩权。
     */
    @Test
    void evaluateShouldAcceptPostgresqlMicrosecondRoundingOnPersistedDeadline() {
        String policy = policyWithExpiry(
                "2026-08-10T11:59:59.161252900Z", "RETRY_EXECUTION")
                .replace("\"maxDurationSeconds\":600", "\"maxDurationSeconds\":7200");
        LocalDateTime persistedDeadline = LocalDateTime.parse("2026-08-10T11:59:59.161253");

        SyncAutopilotRecoveryPolicyDecision decision = evaluator.evaluate(
                policy, requestWithDeadline(persistedDeadline));

        assertThat(decision.state()).isEqualTo(SyncAutopilotRecoveryCaseState.AUTO_APPROVED);
        assertThat(decision.deadlineAt()).isEqualTo(persistedDeadline);
    }

    @Test
    void evaluateShouldRouteAuthorizedHighRiskRecoveryToManualApproval() {
        SyncAutopilotRecoveryPolicyDecision decision = evaluator.evaluate(
                policy("RESUME_FROM_CHECKPOINT", "REPLAY_FROM_CHECKPOINT"),
                request(1001L, SyncAutopilotRecoveryAction.REPLAY_FROM_CHECKPOINT,
                        SyncAutopilotRiskLevel.HIGH, 1, 0, 95, true));

        assertThat(decision.state()).isEqualTo(SyncAutopilotRecoveryCaseState.WAITING_APPROVAL);
    }

    @Test
    void evaluateShouldRejectUnauthorizedScopeOrAction() {
        SyncAutopilotRecoveryPolicyDecision wrongScope = evaluator.evaluate(
                policy("RESUME_FROM_CHECKPOINT"),
                request(2002L, SyncAutopilotRecoveryAction.RESUME_FROM_CHECKPOINT,
                        SyncAutopilotRiskLevel.LOW, 1, 0, 95, true));
        SyncAutopilotRecoveryPolicyDecision wrongAction = evaluator.evaluate(
                policy("RESUME_FROM_CHECKPOINT"),
                request(1001L, SyncAutopilotRecoveryAction.RETRY_FAILED_OBJECTS,
                        SyncAutopilotRiskLevel.LOW, 1, 0, 95, true));

        assertThat(wrongScope.state()).isEqualTo(SyncAutopilotRecoveryCaseState.REJECTED);
        assertThat(wrongAction.state()).isEqualTo(SyncAutopilotRecoveryCaseState.REJECTED);
    }

    @Test
    void evaluateShouldRequireAttentionWhenAutomationEvidenceOrBudgetIsUnsafe() {
        SyncAutopilotRecoveryPolicyDecision repeated = evaluator.evaluate(
                policy("RETRY_EXECUTION"),
                request(1001L, SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW, 1, 2, 95, true));
        SyncAutopilotRecoveryPolicyDecision lowConfidence = evaluator.evaluate(
                policy("RETRY_EXECUTION"),
                request(1001L, SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW, 1, 0, 79, true));
        SyncAutopilotRecoveryPolicyDecision missingEvidence = evaluator.evaluate(
                policy("RETRY_EXECUTION"),
                request(1001L, SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW, 1, 0, 95, false));
        SyncAutopilotRecoveryPolicyDecision exhaustedCycles = evaluator.evaluate(
                policy("RETRY_EXECUTION"),
                request(1001L, SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                        SyncAutopilotRiskLevel.LOW, 4, 0, 95, true));

        assertThat(repeated.state()).isEqualTo(SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED);
        assertThat(lowConfidence.state()).isEqualTo(SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED);
        assertThat(missingEvidence.state()).isEqualTo(SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED);
        assertThat(exhaustedCycles.state()).isEqualTo(SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED);
    }

    private SyncAutopilotRecoveryEvaluationRequest request(Long taskId,
                                                            SyncAutopilotRecoveryAction action,
                                                            SyncAutopilotRiskLevel riskLevel,
                                                            int cycle,
                                                            int repeatedErrorCount,
                                                            int confidenceScore,
                                                            boolean evidenceAvailable) {
        return new SyncAutopilotRecoveryEvaluationRequest(
                SyncAutopilotExecutionMode.AUTOPILOT,
                7L,
                101L,
                taskId,
                cycle,
                null,
                FINGERPRINT,
                repeatedErrorCount,
                action,
                riskLevel,
                FINGERPRINT,
                "receipt-20260810-1",
                confidenceScore,
                evidenceAvailable,
                action == SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                NOW
        );
    }

    private SyncAutopilotRecoveryEvaluationRequest requestWithDeadline(LocalDateTime deadlineAt) {
        return new SyncAutopilotRecoveryEvaluationRequest(
                SyncAutopilotExecutionMode.AUTOPILOT,
                7L,
                101L,
                1001L,
                1,
                deadlineAt,
                FINGERPRINT,
                0,
                SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                SyncAutopilotRiskLevel.LOW,
                FINGERPRINT,
                "receipt-shorter-deadline",
                95,
                true,
                true,
                NOW);
    }

    private String policy(String... allowedActions) {
        return policyWithExpiry("2026-08-10T12:00:00", allowedActions);
    }

    private String policyWithExpiry(String expiresAt, String... allowedActions) {
        return """
                {
                  "authorizationId":"approval-20260810-1",
                  "tenantId":7,
                  "projectId":101,
                  "taskId":1001,
                  "expiresAt":"%s",
                  "maxCycles":3,
                  "maxDurationSeconds":600,
                  "maxRepeatedErrorCount":2,
                  "minimumConfidence":80,
                  "maxAutomaticRisk":"LOW",
                  "allowedActions":[%s]
                }
                """.formatted(expiresAt, java.util.Arrays.stream(allowedActions)
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",")));
    }
}
