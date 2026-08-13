/**
 * @Author : Cui
 * @Date: 2026/08/11 21:00
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Java/data-sync 双策略和 recovery lifecycle receipt 的执行顺序。 */
class AgentAutopilotRecoveryExecutionServiceTest {

    /** 双方都 AUTO_APPROVED 时，应先写 started receipt，再调用失败对象重试。 */
    @Test
    void shouldStartRecoveryAndRequeueFailedObjectsAfterDoubleApproval() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response();
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L);
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.recordTransition(
                trigger, approved, "RECOVERY_STARTED", "started", null)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(trigger)).thenReturn(retryReceipt());

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_FAILED_OBJECTS_REQUEUED");
        assertThat(result.caseId()).isEqualTo(81L);
        verify(fixture.dataSyncClient).recordTransition(
                trigger, approved, "RECOVERY_STARTED", "started", null);
        verify(fixture.dataSyncClient).retryFailedObjects(trigger);
        verify(fixture.pythonClient).verifyPostRecoveryAction(
                trigger, started, "RETRY_EXECUTION", retryReceipt());
    }

    /** data-sync 第二策略层未批准时，Java 不得执行 retry。 */
    @Test
    void shouldRespectDataSyncWaitingApprovalDecision() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response();
        AgentAutopilotRecoveryCaseView waiting = caseView("WAITING_APPROVAL", 0L);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(waiting);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("WAITING_APPROVAL");
        verify(fixture.dataSyncClient, never()).retryFailedObjects(any());
    }

    /**
     * A retry dispatch failure must remain retryable after the local idempotent replay budget is exhausted.
     *
     * <p>The {@code RECOVERY_STARTED} receipt remains intact, so a later Kafka delivery uses the existing started
     * case to replay the same idempotency key. The service must not ACK an artificial attention result or overwrite
     * the lifecycle with a failure receipt while the downstream integration is unavailable.</p>
     */
    @Test
    void shouldPropagateRetryDispatchFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response();
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L);
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.recordTransition(
                trigger, approved, "RECOVERY_STARTED", "started", null)).thenReturn(started);
        IllegalStateException failure = new IllegalStateException("offline");
        when(fixture.dataSyncClient.retryFailedObjects(trigger)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.execute(trigger, response)).isSameAs(failure);

        verify(fixture.dataSyncClient, times(2)).retryFailedObjects(trigger);
        verify(fixture.dataSyncClient, never()).recordTransition(
                trigger,
                started,
                "RECOVERY_FAILED",
                "failed",
                "AUTOPILOT_RETRY_DISPATCH_FAILED");
    }

    /**
     * 第一次调用发生短暂网络故障时，应使用相同 eventId 再调用一次并接受 data-sync 的幂等结果。
     *
     * <p>该测试模拟“请求可能没有到达”或“服务端已提交但响应丢失”。第二次调用成功后 case 仍保持
     * {@code RECOVERY_STARTED}，不得写入失败 receipt，也不得要求用户人工介入。</p>
     */
    @Test
    void shouldReplayIdempotentRetryOnceAfterTransientDispatchFailure() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response();
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L);
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.recordTransition(
                trigger, approved, "RECOVERY_STARTED", "started", null)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(trigger))
                .thenThrow(new IllegalStateException("response-lost"))
                .thenReturn(retryReceipt());

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_FAILED_OBJECTS_REQUEUED");
        verify(fixture.dataSyncClient, times(2)).retryFailedObjects(trigger);
        verify(fixture.dataSyncClient, never()).recordTransition(
                trigger, started, "RECOVERY_FAILED", "failed", "AUTOPILOT_RETRY_DISPATCH_FAILED");
    }

    /**
     * A successful data-sync retry is not the end of the governed Kafka transaction.
     *
     * <p>If Python cannot run or persist PRECHECK/MONITOR facts, this event must remain unacknowledged. The next
     * delivery replays the same data-sync idempotency key and the same Specialist turn IDs; returning a normal
     * {@code RECOVERY_STARTED} result here would create an unaudited autonomous side effect.</p>
     */
    @Test
    void shouldPropagatePostRecoveryVerificationFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response();
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L);
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.recordTransition(
                trigger, approved, "RECOVERY_STARTED", "started", null)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(trigger)).thenReturn(retryReceipt());
        IllegalStateException failure = new IllegalStateException(
                "PYTHON_AUTOPILOT_POST_RECOVERY_VERIFICATION_RESPONSE_INVALID");
        when(fixture.pythonClient.verifyPostRecoveryAction(
                trigger, started, "RETRY_EXECUTION", retryReceipt())).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.execute(trigger, response)).isSameAs(failure);

        verify(fixture.dataSyncClient).retryFailedObjects(trigger);
        verify(fixture.pythonClient).verifyPostRecoveryAction(
                trigger, started, "RETRY_EXECUTION", retryReceipt());
    }

    /**
     * Kafka 重投时若 decision receipt 已显示 {@code RECOVERY_STARTED}，应直接重放幂等 retry，
     * 而不是再次写 started transition 或把已启动案例误判为未批准。
     */
    @Test
    void shouldResumeIdempotentDispatchWhenDecisionReplayReturnsRecoveryStarted() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response();
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(trigger)).thenReturn(retryReceipt());

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        verify(fixture.dataSyncClient, never()).recordTransition(
                trigger, started, "RECOVERY_STARTED", "started", null);
        verify(fixture.dataSyncClient).retryFailedObjects(trigger);
    }

    /** A verified low-risk preview is applied once, then the same bounded failed-object retry continues unattended. */
    @Test
    void shouldApplyVerifiedQuarantineBeforeStartingBoundedRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = quarantineResponse();
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L, "APPLY_QUARANTINE");
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L, "APPLY_QUARANTINE");
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "APPLY_QUARANTINE"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.applyAutonomousQuarantine(
                eq(trigger), eq(approved), eq(response), any())).thenReturn(
                new AgentAutopilotRecoveryQuarantineApplyReceipt(
                        "event-1:quarantine-apply", 81L, 31L, 41L, 2, 2,
                        "APPLIED", "COMPLETED", "e".repeat(64), response.repairFingerprint()));
        when(fixture.dataSyncClient.recordTransition(
                trigger, approved, "RECOVERY_STARTED", "started", null)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(trigger)).thenReturn(retryReceipt());

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_FAILED_OBJECTS_REQUEUED");
        verify(fixture.dataSyncClient).applyAutonomousQuarantine(
                eq(trigger), eq(approved), eq(response), any());
        verify(fixture.dataSyncClient).retryFailedObjects(trigger);
    }

    /** A cross-scope or malformed model preview stops before a case or downstream write can be created. */
    @Test
    void shouldRequireValidQuarantinePreviewBeforeAnySideEffect() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse valid = quarantineResponse();
        Map<String, Object> invalidPreview = new java.util.LinkedHashMap<>(valid.quarantinePreview());
        invalidPreview.put("executionId", 999L);
        AgentAutopilotRecoveryPlanResponse invalid = new AgentAutopilotRecoveryPlanResponse(
                valid.schemaVersion(), valid.eventId(), valid.status(), valid.reasonCode(), valid.action(),
                valid.riskLevel(), valid.idempotent(), valid.repairFingerprint(), valid.errorFingerprint(),
                valid.confidence(), valid.evidenceAvailable(), valid.evidenceAudit(), valid.evidenceScope(),
                valid.retrievalDecision(), valid.retrievalStrategy(), valid.retrievalAudit(),
                valid.strategyChanged(), valid.checkpointThreadId(), valid.payloadPolicy(), invalidPreview);
        when(fixture.evidenceVerifier.verify(trigger, invalid)).thenReturn(true);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, invalid);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_QUARANTINE_PREVIEW_SCOPE_MISMATCH");
        verifyNoInteractions(fixture.policyEvaluator, fixture.dataSyncClient);
    }

    /** An incomplete durable receipt cannot start retry even when both policy layers approved the candidate. */
    @Test
    void shouldStopBeforeRetryWhenQuarantineReceiptIsNotDurablyApplied() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = quarantineResponse();
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L, "APPLY_QUARANTINE");
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "APPLY_QUARANTINE"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.applyAutonomousQuarantine(
                eq(trigger), eq(approved), eq(response), any())).thenReturn(
                new AgentAutopilotRecoveryQuarantineApplyReceipt(
                        "event-1:quarantine-apply", 81L, 31L, 41L, 2, 0,
                        "PROCESSING", "PROCESSING", "e".repeat(64), response.repairFingerprint()));

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_QUARANTINE_APPLY_NOT_COMPLETED");
        verify(fixture.dataSyncClient, never()).retryFailedObjects(any());
    }

    /** A replayed started case continues retry without applying the already committed quarantine a second time. */
    @Test
    void shouldResumeQuarantineRecoveryWithoutReapplyingReceipt() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = quarantineResponse();
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L, "APPLY_QUARANTINE");
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "APPLY_QUARANTINE"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(trigger)).thenReturn(retryReceipt());

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        verify(fixture.dataSyncClient, never()).applyAutonomousQuarantine(any(), any(), any(), any());
        verify(fixture.dataSyncClient).retryFailedObjects(trigger);
    }

    /** 相同错误重复使用同一修复指纹且未声明策略变化时，必须在副作用前阻断。 */
    @Test
    void shouldBlockRepeatedRepairWhenStrategyDidNotChange() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger repeatedTrigger = repeatedTrigger("b".repeat(64));
        AgentAutopilotRecoveryPlanResponse response = response("b".repeat(64), false);
        when(fixture.evidenceVerifier.verify(repeatedTrigger, response)).thenReturn(true);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(repeatedTrigger, response);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("RECOVERY_REPEATED_REPAIR_STRATEGY_UNCHANGED");
        verify(fixture.dataSyncClient, never()).recordDecision(any(), any(), eq(true));
        verify(fixture.dataSyncClient, never()).retryFailedObjects(any());
    }

    /** 自报 strategyChanged=true 也不能掩盖完全相同的修复指纹。 */
    @Test
    void shouldBlockSameRepeatedRepairEvenWhenPythonClaimsStrategyChanged() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger repeatedTrigger = repeatedTrigger("b".repeat(64));
        AgentAutopilotRecoveryPlanResponse response = response(
                "b".repeat(64), true, "SEARCH", Map.of("evidenceCount", 1));
        when(fixture.evidenceVerifier.verify(repeatedTrigger, response)).thenReturn(true);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(repeatedTrigger, response);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("RECOVERY_REPEATED_REPAIR_STRATEGY_UNCHANGED");
        verifyNoInteractions(fixture.policyEvaluator, fixture.dataSyncClient);
    }

    /** 仅改变修复指纹但没有新的受控检索审计，仍可能是随机换 hash，不能自动重跑。 */
    @Test
    void shouldRequireVerifiedRetrievalExpansionForRepeatedError() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger repeatedTrigger = repeatedTrigger("b".repeat(64));
        AgentAutopilotRecoveryPlanResponse response = response("c".repeat(64), true);
        when(fixture.evidenceVerifier.verify(repeatedTrigger, response)).thenReturn(true);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(repeatedTrigger, response);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("RECOVERY_REPEATED_ERROR_RETRIEVAL_NOT_EXPANDED");
        verifyNoInteractions(fixture.policyEvaluator, fixture.dataSyncClient);
    }

    /** 新指纹与已验证 SEARCH 审计同时存在时，重复错误才可继续进入双策略和幂等重排队。 */
    @Test
    void shouldAllowChangedRepairAfterVerifiedRetrievalExpansion() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger repeatedTrigger = repeatedTrigger("b".repeat(64));
        AgentAutopilotRecoveryPlanResponse response = response(
                "c".repeat(64), false, "SEARCH", Map.of("evidenceCount", 1));
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L);
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L);
        when(fixture.evidenceVerifier.verify(repeatedTrigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(repeatedTrigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.recordTransition(
                repeatedTrigger, approved, "RECOVERY_STARTED", "started", null)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(repeatedTrigger)).thenReturn(retryReceipt());

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(repeatedTrigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        verify(fixture.dataSyncClient).retryFailedObjects(repeatedTrigger);
    }

    /** A verified SEARCH candidate must retain only its compact grounded retrieval proof in the callback result. */
    @Test
    void shouldProjectVerifiedSearchEvidenceIntoTheLowSensitiveExecutionResult() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response(
                "b".repeat(64), true, "SEARCH",
                Map.of("evidenceCount", 2, "evidenceDigest", "sha256:" + "c".repeat(64)));
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L);
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "AUTOPILOT_LOW_RISK_ACTION_APPROVED", "policy-1", "RETRY_EXECUTION"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.recordTransition(trigger, approved,
                "RECOVERY_STARTED", "started", null)).thenReturn(started);
        when(fixture.dataSyncClient.retryFailedObjects(trigger)).thenReturn(retryReceipt());

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.retrievalDecision()).isEqualTo("SEARCH");
        assertThat(result.retrievalStrategy()).isEqualTo("STRUCTURED_DIAGNOSTIC");
        assertThat(result.retrievalEvidenceCount()).isEqualTo(2);
        assertThat(result.retrievalEvidenceDigest()).isEqualTo("sha256:" + "c".repeat(64));
    }

    /**
     * Scope, digest, source, and freshness violations are deterministic evidence denials rather than delivery failures.
     *
     * <p>Each supplied reason is emitted by the evidence verifier after it has examined the same immutable planner
     * response. Retrying that unchanged response cannot repair its business facts, so the execution service must return
     * a low-sensitive {@code REJECTED} result for the consumer to durably callback and acknowledge. It must stop before
     * creating a data-sync case, state transition, or retry side effect.</p>
     */
    @Test
    void shouldReturnRejectedForPermanentEvidenceViolationsWithoutStartingRecovery() {
        for (String reasonCode : List.of(
                "AUTOPILOT_RECOVERY_EVIDENCE_SCOPE_MISMATCH",
                "AUTOPILOT_EVIDENCE_DIGEST_MISMATCH",
                "AUTOPILOT_DIAGNOSTIC_SOURCE_NOT_AUTHORITATIVE",
                "AUTOPILOT_EVIDENCE_TIME_OUT_OF_RANGE")) {
            Fixture fixture = fixture();
            AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
            AgentAutopilotRecoveryPlanResponse response = response();
            when(fixture.evidenceVerifier.verify(trigger, response)).thenThrow(
                    new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, reasonCode));

            AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

            assertThat(result).isEqualTo(new AgentAutopilotRecoveryExecutionResult(
                    "event-1", "REJECTED", reasonCode, null, 41L));
            verify(fixture.metrics).recordEvidenceRejected();
            verifyNoInteractions(fixture.policyEvaluator, fixture.dataSyncClient);
        }
    }

    /**
     * Technical evidence-verification failures remain visible to Kafka retry handling.
     *
     * <p>This represents failures such as an unavailable JDK crypto primitive or another local runtime defect, not a
     * deterministic evidence mismatch. The service may record the rejected verification attempt for observability, but
     * it must rethrow the same exception so no terminal result is acknowledged without a durable retry path.</p>
     */
    @Test
    void shouldPropagateTechnicalEvidenceVerificationFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = response();
        IllegalStateException failure = new IllegalStateException("JDK_SHA_256_UNAVAILABLE");
        when(fixture.evidenceVerifier.verify(trigger, response)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.execute(trigger, response)).isSameAs(failure);

        verify(fixture.metrics).recordEvidenceRejected();
        verifyNoInteractions(fixture.policyEvaluator, fixture.dataSyncClient);
    }

    /** 创建包含 mocked 持久对象的可信触发器；本测试只关注执行顺序而非仓储恢复。 */
    private AgentAutopilotVerifiedRecoveryTrigger trigger() {
        AgentAutopilotRecoveryTriggerEvent event = new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1", "event-1", "session-1", "run-1",
                11L, 12L, 13L, "14", "14", "main-agent", "delegation-1",
                31L, 40L, 41L, 1, 5, "2099-01-01T00:00:00Z", "a".repeat(64),
                0, null, java.util.List.of("OBJECT_TRANSFER_FAILED"), Map.of(),
                "sha256:" + "c".repeat(64), "2026-08-11T00:00:00Z");
        return new AgentAutopilotVerifiedRecoveryTrigger(
                event,
                mock(com.czh.datasmart.govern.agent.service.session.AgentSessionRecord.class),
                mock(com.czh.datasmart.govern.agent.service.session.AgentRunRecord.class),
                mock(AgentAutopilotAuthorizationSnapshot.class),
                java.time.OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                java.time.OffsetDateTime.parse("2026-08-11T00:00:00Z"));
    }

    /** 创建默认低风险、幂等、证据充分的 Python 候选。 */
    private AgentAutopilotRecoveryPlanResponse response() {
        return response("b".repeat(64), true);
    }

    /** 允许测试覆盖修复指纹和策略变化标记。 */
    private AgentAutopilotRecoveryPlanResponse response(String repairFingerprint, boolean strategyChanged) {
        return response(repairFingerprint, strategyChanged, "SKIP", Map.of());
    }

    /** 允许测试覆盖检索决策与审计摘要，模拟首次诊断和重复错误扩大检索两种合同。 */
    private AgentAutopilotRecoveryPlanResponse response(String repairFingerprint,
                                                        boolean strategyChanged,
                                                        String retrievalDecision,
                                                        Map<String, Object> retrievalAudit) {
        return new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", "RETRY_EXECUTION", "LOW", true,
                repairFingerprint, "a".repeat(64), 0.91d, true,
                Map.of(), Map.of(), retrievalDecision, "STRUCTURED_DIAGNOSTIC", retrievalAudit,
                strategyChanged, "autopilot-recovery:event-1",
                "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY");
    }

    /** Builds the exact preview projection and canonical fingerprint used by the Python/Java apply protocol. */
    private AgentAutopilotRecoveryPlanResponse quarantineResponse() {
        String confirmationDigest = "e".repeat(64);
        String repairFingerprint = sha256(String.join("|",
                "event-1", "a".repeat(64), "41", "APPLY_QUARANTINE",
                confirmationDigest, "501,502"));
        return new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", "APPLY_QUARANTINE", "LOW", true,
                repairFingerprint, "a".repeat(64), 0.91d, true,
                Map.of(), Map.of(), "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(),
                true, "autopilot-recovery:event-1",
                "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY",
                Map.of(
                        "taskId", 31L,
                        "executionId", 41L,
                        "selectedCount", 2,
                        "eligibleCount", 2,
                        "issueCodes", List.of(),
                        "confirmationDigest", confirmationDigest,
                        "selectedSampleIds", List.of(501L, 502L),
                        "outputRef", "agent-runtime://run-1/quarantine-preview"));
    }

    /** Computes lowercase SHA-256 for test-owned cross-language fingerprint fixtures. */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 将默认可信触发器转换为同一错误已发生过一次的后续循环。 */
    private AgentAutopilotVerifiedRecoveryTrigger repeatedTrigger(String previousRepairFingerprint) {
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        AgentAutopilotRecoveryTriggerEvent repeatedEvent = new AgentAutopilotRecoveryTriggerEvent(
                event.schemaVersion(), event.eventId(), event.rootSessionId(), event.rootRunId(),
                event.tenantId(), event.applicationId(), event.projectId(), event.userId(), event.actorId(),
                event.agentId(), event.delegationId(), event.syncTaskId(), event.rootExecutionId(),
                event.currentExecutionId(), 2, event.maxRecoveryCycles(), event.deadlineAt(),
                event.errorFingerprint(), 1, previousRepairFingerprint, event.issueCodes(),
                event.authorizationSnapshot(), event.authorizationSnapshotDigest(), event.triggeredAt());
        return new AgentAutopilotVerifiedRecoveryTrigger(
                repeatedEvent, trigger.session(), trigger.rootRun(), trigger.authorization(),
                trigger.deadlineAt(), trigger.recoveryStartedAt());
    }

    /** 创建 data-sync recovery case 的固定响应。 */
    private AgentAutopilotRecoveryCaseView caseView(String state, Long version) {
        return caseView(state, version, "RETRY_EXECUTION");
    }

    /** Creates a data-sync recovery case response for a named low-risk action. */
    private AgentAutopilotRecoveryCaseView caseView(String state, Long version, String action) {
        return new AgentAutopilotRecoveryCaseView(
                81L, 31L, 40L, 41L, state, version, 1, 5,
                action, null, "d".repeat(64), "e".repeat(64));
    }

    /** Creates the scope-bound data-sync receipt required before post-recovery Specialist verification. */
    private AgentAutopilotRecoveryRetryReceipt retryReceipt() {
        return new AgentAutopilotRecoveryRetryReceipt(
                31L, 41L, 2, "QUEUED", "RETRYING");
    }

    /** 创建隔离的 mock 依赖和被测服务。 */
    private Fixture fixture() {
        AgentAutopilotRecoveryEvidenceVerifier evidenceVerifier = mock(AgentAutopilotRecoveryEvidenceVerifier.class);
        AgentAutopilotRecoveryPolicyEvaluator policyEvaluator = mock(AgentAutopilotRecoveryPolicyEvaluator.class);
        AgentAutopilotRecoveryDataSyncClient dataSyncClient = mock(AgentAutopilotRecoveryDataSyncClient.class);
        AgentAutopilotRecoveryPythonClient pythonClient = mock(AgentAutopilotRecoveryPythonClient.class);
        AgentAutopilotRecoveryMetrics metrics = mock(AgentAutopilotRecoveryMetrics.class);
        return new Fixture(
                new AgentAutopilotRecoveryExecutionService(
                        evidenceVerifier, new AgentAutopilotRecoveryQuarantinePreviewVerifier(),
                        policyEvaluator, dataSyncClient, pythonClient, metrics),
                evidenceVerifier,
                policyEvaluator,
                dataSyncClient,
                pythonClient,
                metrics);
    }

    private record Fixture(
            AgentAutopilotRecoveryExecutionService service,
            AgentAutopilotRecoveryEvidenceVerifier evidenceVerifier,
            AgentAutopilotRecoveryPolicyEvaluator policyEvaluator,
            AgentAutopilotRecoveryDataSyncClient dataSyncClient,
            AgentAutopilotRecoveryPythonClient pythonClient,
            AgentAutopilotRecoveryMetrics metrics) {
    }
}
