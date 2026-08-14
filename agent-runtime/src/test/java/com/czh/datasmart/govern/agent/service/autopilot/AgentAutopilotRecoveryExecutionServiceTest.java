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

    /** 字段映射修复必须先通过 Java 参数复核，再由 data-sync 修复并进入恢复后验证。 */
    @Test
    void shouldApplyGovernedFieldMappingRepairAndVerifyTheRequeuedExecution() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = governedRepairResponse("REPAIR_FIELD_MAPPING");
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L, "REPAIR_FIELD_MAPPING");
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L, "REPAIR_FIELD_MAPPING");
        Map<String, Object> parameters = Map.of("repairMode", "METADATA_PROVEN_SAFE");
        AgentAutopilotRecoveryRepairReceipt repairReceipt = new AgentAutopilotRecoveryRepairReceipt(
                "event-1:repair-apply", 81L, 31L, 41L, 41L, "REPAIR_FIELD_MAPPING",
                true, 1, "QUEUED", "RETRYING", "AUTOPILOT_FIELD_MAPPING_REPAIRED",
                List.of(), response.repairFingerprint(), "AUTO_APPROVED", false, null, null);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.repairVerifier.supports("REPAIR_FIELD_MAPPING")).thenReturn(true);
        when(fixture.repairVerifier.verify(trigger, response)).thenReturn(parameters);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "REPAIR_FIELD_MAPPING"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.applyGovernedRepair(trigger, approved, response, parameters))
                .thenReturn(repairReceipt);
        when(fixture.dataSyncClient.recordTransition(
                trigger, approved, "RECOVERY_STARTED", "started", null, 41L)).thenReturn(started);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_FIELD_MAPPING_REPAIRED");
        verify(fixture.pythonClient).verifyPostRecoveryAction(
                trigger, started, "REPAIR_FIELD_MAPPING",
                new AgentAutopilotRecoveryRetryReceipt(31L, 41L, 1, "QUEUED", "RETRYING"));
        verify(fixture.dataSyncClient, never()).retryFailedObjects(any());
    }

    /** 未应用动作携带持久下一轮证明时，本轮应退出但不得重复写失败迁移。 */
    @Test
    void shouldMoveNotAppliedRepairToAttentionRequired() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = governedRepairResponse("REPAIR_FIELD_MAPPING");
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L, "REPAIR_FIELD_MAPPING");
        Map<String, Object> parameters = Map.of("repairMode", "METADATA_PROVEN_SAFE");
        AgentAutopilotRecoveryRepairReceipt repairReceipt = new AgentAutopilotRecoveryRepairReceipt(
                "event-1:repair-apply", 81L, 31L, 41L, 41L, "REPAIR_FIELD_MAPPING",
                false, 0, "FAILED", null, "AUTOPILOT_FIELD_MAPPING_REQUIRES_MANUAL_CHANGE",
                List.of("METADATA_TARGET_REQUIRED_FIELD_UNMAPPED"), response.repairFingerprint(),
                "ATTENTION_REQUIRED", true, "autopilot-trigger:" + "b".repeat(64), 2);
        when(fixture.evidenceVerifier.verify(trigger, response)).thenReturn(true);
        when(fixture.repairVerifier.supports("REPAIR_FIELD_MAPPING")).thenReturn(true);
        when(fixture.repairVerifier.verify(trigger, response)).thenReturn(parameters);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "REPAIR_FIELD_MAPPING"));
        when(fixture.dataSyncClient.recordDecision(trigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.applyGovernedRepair(trigger, approved, response, parameters))
                .thenReturn(repairReceipt);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_FIELD_MAPPING_REQUIRES_MANUAL_CHANGE");
        verifyNoInteractions(fixture.pythonClient);
        verify(fixture.dataSyncClient, never()).retryFailedObjects(any());
        verify(fixture.dataSyncClient, never()).recordTransition(
                trigger, approved, "RECOVERY_FAILED", "repair-not-applied",
                "AUTOPILOT_FIELD_MAPPING_REQUIRES_MANUAL_CHANGE", 41L);
    }

    /**
     * 本地幂等重放预算耗尽后，重试派发失败仍必须保持可重试。
     *
     * <p>{@code RECOVERY_STARTED} 回执保持不变，因此后续 Kafka 投递会复用已启动 case 和同一个幂等键。
     * 下游集成不可用时，服务不能 ACK 人工构造的关注结果，也不能用失败回执覆盖现有生命周期。</p>
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
     * data-sync 成功接受重试并不代表受治理 Kafka 事务已经结束。
     *
     * <p>如果 Python 无法运行或持久化 PRECHECK/MONITOR 事实，该事件必须保持未确认。下一次投递会重放
     * 相同的 data-sync 幂等键和 Specialist 回合 ID；若此处正常返回 {@code RECOVERY_STARTED}，就会
     * 产生缺少审计事实的自治副作用。</p>
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

    /** 已验证的低风险预览只应用一次，随后在同一边界内继续无人值守重试失败对象。 */
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

    /** 跨作用域或格式错误的模型预览必须在创建 case 或下游写入前停止。 */
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

    /** 即使双层策略已批准候选，持久回执不完整时也不能启动重试。 */
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

    /** 重放已启动 case 时继续重试，但不能再次应用已经提交的隔离动作。 */
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

    /**
     * 上一受治理动作未应用时，data-sync 写入的新问题码本身就是可信的结构化证据扩展。
     * 模型因此可以自主选择 SKIP，但必须改选不同动作，并继续通过 Java 参数复核和 data-sync 权威策略。
     */
    @Test
    void shouldAllowDifferentRepairAfterTrustedNotAppliedEvidenceWithoutForcingRag() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger repeatedTrigger = repeatedRepairReplanTrigger(
                "b".repeat(64), "REFRESH_METADATA", "METADATA_TARGET_FIELD_NOT_FOUND");
        AgentAutopilotRecoveryPlanResponse response = governedRepairResponse("REPAIR_FIELD_MAPPING");
        AgentAutopilotRecoveryCaseView approved = caseView("AUTO_APPROVED", 0L, "REPAIR_FIELD_MAPPING");
        AgentAutopilotRecoveryCaseView started = caseView("RECOVERY_STARTED", 1L, "REPAIR_FIELD_MAPPING");
        Map<String, Object> parameters = Map.of("repairMode", "METADATA_PROVEN_SAFE");
        AgentAutopilotRecoveryRepairReceipt repairReceipt = new AgentAutopilotRecoveryRepairReceipt(
                "event-1:repair-apply", 81L, 31L, 41L, 41L, "REPAIR_FIELD_MAPPING",
                true, 1, "QUEUED", "RETRYING", "AUTOPILOT_FIELD_MAPPING_REPAIRED",
                List.of(), response.repairFingerprint(), "AUTO_APPROVED", false, null, null);
        when(fixture.evidenceVerifier.verify(repeatedTrigger, response)).thenReturn(true);
        when(fixture.repairVerifier.supports("REPAIR_FIELD_MAPPING")).thenReturn(true);
        when(fixture.repairVerifier.verify(repeatedTrigger, response)).thenReturn(parameters);
        when(fixture.policyEvaluator.evaluate(any(), any(), any())).thenReturn(
                new AgentAutopilotRecoveryDecision(
                        AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                        "RECOVERY_PREAUTHORIZED", "policy-1", "REPAIR_FIELD_MAPPING"));
        when(fixture.dataSyncClient.recordDecision(repeatedTrigger, response, true)).thenReturn(approved);
        when(fixture.dataSyncClient.applyGovernedRepair(repeatedTrigger, approved, response, parameters))
                .thenReturn(repairReceipt);
        when(fixture.dataSyncClient.recordTransition(
                repeatedTrigger, approved, "RECOVERY_STARTED", "started", null, 41L)).thenReturn(started);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(repeatedTrigger, response);

        assertThat(result.status()).isEqualTo("RECOVERY_STARTED");
        assertThat(result.retrievalDecision()).isEqualTo("SKIP");
        verify(fixture.dataSyncClient).applyGovernedRepair(
                repeatedTrigger, approved, response, parameters);
    }

    /** data-sync 已证明上一动作未应用时，即使模型生成了新指纹，也不得重复选择同一个动作。 */
    @Test
    void shouldRejectSameRepairAfterTrustedNotAppliedEvidence() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger repeatedTrigger = repeatedRepairReplanTrigger(
                "b".repeat(64), "REFRESH_METADATA", "METADATA_TARGET_FIELD_NOT_FOUND");
        AgentAutopilotRecoveryPlanResponse response = governedRepairResponse("REFRESH_METADATA");
        when(fixture.evidenceVerifier.verify(repeatedTrigger, response)).thenReturn(true);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(repeatedTrigger, response);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("RECOVERY_REPEATED_REPAIR_ACTION_UNCHANGED");
        verifyNoInteractions(fixture.policyEvaluator, fixture.dataSyncClient);
    }

    /** 已验证的 SEARCH 候选只能在回调中保留紧凑、可落地核验的检索证明。 */
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

    /** 受限规划在候选执行前停止时，仍会暴露模型明确的 SKIP 决策，但不会授予执行权限。 */
    @Test
    void shouldProjectRetrievalDecisionWhenPlannerStopsBeforeCandidateExecution() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryPlanResponse response = new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "ATTENTION_REQUIRED",
                "RECOVERY_ACTION_NOT_IN_PLATFORM_CATALOG", null, null, false,
                null, "a".repeat(64), 0.94d, true,
                Map.of("evidenceCount", 4), Map.of(), "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(),
                false, "autopilot-recovery:event-1",
                "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY");

        AgentAutopilotRecoveryExecutionResult result = fixture.service.execute(trigger, response);

        assertThat(result.status()).isEqualTo("ATTENTION_REQUIRED");
        assertThat(result.reasonCode()).isEqualTo("RECOVERY_ACTION_NOT_IN_PLATFORM_CATALOG");
        assertThat(result.retrievalDecision()).isEqualTo("SKIP");
        assertThat(result.retrievalStrategy()).isEqualTo("STRUCTURED_DIAGNOSTIC");
        assertThat(result.retrievalEvidenceCount()).isZero();
        assertThat(result.retrievalEvidenceDigest()).isNull();
        verifyNoInteractions(fixture.evidenceVerifier, fixture.policyEvaluator, fixture.dataSyncClient);
    }

    /**
     * 作用域、摘要、来源或新鲜度不合法属于确定性证据拒绝，不是投递失败。
     *
     * <p>每个原因码都由证据验证器检查同一份不可变规划响应后产生。重复处理未变化的响应无法修复其业务
     * 事实，因此执行服务必须返回低敏 {@code REJECTED}，由 consumer 持久回调后确认消息；同时必须在
     * 创建 data-sync case、状态迁移或重试副作用前停止。</p>
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
     * 技术性证据校验失败必须继续暴露给 Kafka 重试处理。
     *
     * <p>这类失败代表 JDK 加密原语不可用或其他本地运行时缺陷，并非确定性证据不匹配。服务可以记录被拒绝
     * 的校验尝试用于观测，但必须重新抛出同一个异常，避免在没有持久重试路径时确认终态结果。</p>
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
                "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY", Map.of(), transientRetryFacts());
    }

    /** 为无人值守重试提供 Java 和 data-sync 都要求的确定性事实。 */
    private Map<String, Object> transientRetryFacts() {
        return Map.of(
                "failureClass", "TRANSIENT_CONNECTOR_OR_WORKER",
                "retryable", true,
                "eligibleForAutomaticRetry", true,
                "failedObjectCount", 1,
                "rootCauseCodes", List.of("CONNECTOR_OR_NETWORK_UNAVAILABLE"));
    }

    /** 构造 Python/Java 应用协议使用的精确 preview 投影和规范指纹。 */
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

    /** 构造一个携带元数据证明型修复参数及跨语言指纹的候选。 */
    private AgentAutopilotRecoveryPlanResponse governedRepairResponse(String action) {
        Map<String, Object> parameters = Map.of("repairMode", "METADATA_PROVEN_SAFE");
        String repairFingerprint = sha256(String.join("|",
                "event-1", "a".repeat(64), "41", action,
                "repairMode=METADATA_PROVEN_SAFE"));
        return new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", action, "LOW", true,
                repairFingerprint, "a".repeat(64), 0.91d, true,
                Map.of(), Map.of(), "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(), true,
                "autopilot-recovery:event-1", "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY",
                Map.of(), Map.of(), parameters, Map.of());
    }

    /** 为测试自有的跨语言指纹夹具计算小写 SHA-256。 */
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

    /**
     * 构造由 data-sync “动作未应用”回执派生的可信下一轮事件。
     * 主原因、上一动作标记和新发现的问题码都来自服务端白名单，不接受模型自由文本。
     */
    private AgentAutopilotVerifiedRecoveryTrigger repeatedRepairReplanTrigger(
            String previousRepairFingerprint,
            String previousAction,
            String structuredIssueCode) {
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        AgentAutopilotRecoveryTriggerEvent repeatedEvent = new AgentAutopilotRecoveryTriggerEvent(
                event.schemaVersion(), event.eventId(), event.rootSessionId(), event.rootRunId(),
                event.tenantId(), event.applicationId(), event.projectId(), event.userId(), event.actorId(),
                event.agentId(), event.delegationId(), event.syncTaskId(), event.rootExecutionId(),
                event.currentExecutionId(), 2, event.maxRecoveryCycles(), event.deadlineAt(),
                event.errorFingerprint(), 1, previousRepairFingerprint,
                List.of(
                        "AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED",
                        "PREVIOUS_REPAIR_ACTION_" + previousAction,
                        structuredIssueCode),
                event.authorizationSnapshot(), event.authorizationSnapshotDigest(), event.triggeredAt());
        return new AgentAutopilotVerifiedRecoveryTrigger(
                repeatedEvent, trigger.session(), trigger.rootRun(), trigger.authorization(),
                trigger.deadlineAt(), trigger.recoveryStartedAt());
    }

    /** 创建 data-sync recovery case 的固定响应。 */
    private AgentAutopilotRecoveryCaseView caseView(String state, Long version) {
        return caseView(state, version, "RETRY_EXECUTION");
    }

    /** 为指定低风险动作创建固定的 data-sync recovery case 响应。 */
    private AgentAutopilotRecoveryCaseView caseView(String state, Long version, String action) {
        return new AgentAutopilotRecoveryCaseView(
                81L, 31L, 40L, 41L, state, version, 1, 5,
                action, null, "d".repeat(64), "e".repeat(64));
    }

    /** 创建恢复后 Specialist 验证前必需的范围绑定 data-sync 回执。 */
    private AgentAutopilotRecoveryRetryReceipt retryReceipt() {
        return new AgentAutopilotRecoveryRetryReceipt(
                31L, 41L, 2, "QUEUED", "RETRYING");
    }

    /** 创建隔离的 mock 依赖和被测服务。 */
    private Fixture fixture() {
        AgentAutopilotRecoveryEvidenceVerifier evidenceVerifier = mock(AgentAutopilotRecoveryEvidenceVerifier.class);
        AgentAutopilotRecoveryRepairVerifier repairVerifier = mock(AgentAutopilotRecoveryRepairVerifier.class);
        AgentAutopilotRecoveryPolicyEvaluator policyEvaluator = mock(AgentAutopilotRecoveryPolicyEvaluator.class);
        AgentAutopilotRecoveryDataSyncClient dataSyncClient = mock(AgentAutopilotRecoveryDataSyncClient.class);
        AgentAutopilotRecoveryPythonClient pythonClient = mock(AgentAutopilotRecoveryPythonClient.class);
        AgentAutopilotRecoveryMetrics metrics = mock(AgentAutopilotRecoveryMetrics.class);
        return new Fixture(
                new AgentAutopilotRecoveryExecutionService(
                        evidenceVerifier, new AgentAutopilotRecoveryQuarantinePreviewVerifier(),
                        repairVerifier, policyEvaluator, dataSyncClient, pythonClient, metrics),
                evidenceVerifier,
                repairVerifier,
                policyEvaluator,
                dataSyncClient,
                pythonClient,
                metrics);
    }

    private record Fixture(
            AgentAutopilotRecoveryExecutionService service,
            AgentAutopilotRecoveryEvidenceVerifier evidenceVerifier,
            AgentAutopilotRecoveryRepairVerifier repairVerifier,
            AgentAutopilotRecoveryPolicyEvaluator policyEvaluator,
            AgentAutopilotRecoveryDataSyncClient dataSyncClient,
            AgentAutopilotRecoveryPythonClient pythonClient,
            AgentAutopilotRecoveryMetrics metrics) {
    }
}
