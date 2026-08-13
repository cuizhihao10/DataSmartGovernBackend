/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryAutonomousQuarantineServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineResult;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryQuarantineReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryQuarantineReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncAutopilotRecoveryAutonomousQuarantineServiceTest {

    private static final String POLICY = """
            {
              "policyId":"policy-1",
              "tenantId":10,
              "projectId":20,
              "taskId":31,
              "expiresAt":"2099-01-01T00:00:00Z",
              "maxRecoveryCycles":5,
              "maxTotalDurationMinutes":120,
              "maxAutomaticRiskLevel":"LOW",
              "allowedRecoveryActions":["APPLY_QUARANTINE","RETRY_EXECUTION"],
              "requireApprovalFor":["CHANGE_SCHEMA"]
            }
            """;
    private static final String ERROR_FINGERPRINT = "f".repeat(64);
    private static final String PREVIEW_DIGEST = "e".repeat(64);
    private static final String ACTION_FINGERPRINT = SyncAutopilotDigestSupport.sha256(
            "event-1|" + ERROR_FINGERPRINT + "|41|APPLY_QUARANTINE|"
                    + PREVIEW_DIGEST + "|501,502");
    private static final String AUTHORIZATION_DIGEST = SyncAutopilotDigestSupport.sha256("policy-1");

    @Test
    void shouldApplyDigestBoundQuarantineAndCompleteDurableReceipt() {
        Fixture fixture = fixture();
        when(fixture.receiptMapper().insertIfAbsent(any())).thenReturn(1);
        when(fixture.quarantineSupport().applyAutonomous(any(), any(), any())).thenReturn(
                new SyncDirtyRecordQuarantineResult(
                        31L, 41L, 2, 2, 2, "APPLIED", "e".repeat(64),
                        List.of(501L, 502L), List.of(), "applied"));
        when(fixture.receiptMapper().completeReceipt(
                "event-1:quarantine-apply", 2, 2, "APPLIED")).thenReturn(1);

        SyncAutopilotRecoveryQuarantineReceiptView result = fixture.service().apply(command());

        assertThat(result.receiptId()).isEqualTo("event-1:quarantine-apply");
        assertThat(result.caseId()).isEqualTo(81L);
        assertThat(result.affectedCount()).isEqualTo(2);
        assertThat(result.operationState()).isEqualTo("APPLIED");
        ArgumentCaptor<SyncAutopilotRecoveryQuarantineReceipt> receipt =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryQuarantineReceipt.class);
        verify(fixture.receiptMapper()).insertIfAbsent(receipt.capture());
        assertThat(receipt.getValue().getReceiptState()).isEqualTo("PROCESSING");
        assertThat(receipt.getValue().getPreviewDigest()).isEqualTo(PREVIEW_DIGEST);
    }

    @Test
    void shouldRejectPreviewDigestThatNoLongerMatchesTheSelectedRows() {
        Fixture fixture = fixture();
        when(fixture.receiptMapper().insertIfAbsent(any())).thenReturn(1);
        when(fixture.quarantineSupport().applyAutonomous(any(), any(), any()))
                .thenThrow(new PlatformBusinessException(
                        com.czh.datasmart.govern.common.error.PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Autopilot quarantine preview digest no longer matches"));

        assertThatThrownBy(() -> fixture.service().apply(command()))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("digest");
        verify(fixture.receiptMapper(), never()).completeReceipt(any(), any(Integer.class),
                any(Integer.class), any());
    }

    @Test
    void shouldRejectCaseOutsideTheAuthorizedTaskAndExecutionScope() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryQuarantineCommand outside = new SyncAutopilotRecoveryQuarantineCommand(
                81L, 0L, 10L, 20L, 32L, 41L, 1,
                "c".repeat(64), "d".repeat(64), "e".repeat(64),
                List.of(501L, 502L), "b".repeat(64), "event-1:quarantine-apply");

        assertThatThrownBy(() -> fixture.service().apply(outside))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("scope");
        verify(fixture.quarantineSupport(), never()).applyAutonomous(any(), any(), any());
    }

    @Test
    void shouldReplayTheOriginalCompletedReceiptWithoutApplyingTwice() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryQuarantineReceipt existing = new SyncAutopilotRecoveryQuarantineReceipt();
        existing.setReceiptId("event-1:quarantine-apply");
        existing.setCaseId(81L);
        existing.setRequestDigest(SyncAutopilotRecoveryAutonomousQuarantineService.requestDigest(command()));
        existing.setPreviewDigest(PREVIEW_DIGEST);
        existing.setActionFingerprint(ACTION_FINGERPRINT);
        existing.setSyncTaskId(31L);
        existing.setExecutionId(41L);
        existing.setSelectedCount(2);
        existing.setAffectedCount(2);
        existing.setOperationState("APPLIED");
        existing.setReceiptState("COMPLETED");
        when(fixture.receiptMapper().selectByReceiptId("event-1:quarantine-apply")).thenReturn(existing);

        SyncAutopilotRecoveryQuarantineReceiptView result = fixture.service().apply(command());

        assertThat(result.affectedCount()).isEqualTo(2);
        verify(fixture.quarantineSupport(), never()).applyAutonomous(any(), any(), any());
        verify(fixture.receiptMapper(), never()).insertIfAbsent(any());
    }

    @Test
    void shouldRejectWhenAuthoritativeTaskNoLongerMatchesTheAuthorizedProject() {
        Fixture fixture = fixture();
        SyncTask movedTask = new SyncTask();
        movedTask.setId(31L);
        movedTask.setTenantId(10L);
        movedTask.setProjectId(99L);
        when(fixture.taskMapper().selectById(31L)).thenReturn(movedTask);

        assertThatThrownBy(() -> fixture.service().apply(command()))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("scope");

        verify(fixture.quarantineSupport(), never()).applyAutonomous(any(), any(), any());
    }

    private Fixture fixture() {
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryQuarantineReceiptMapper receiptMapper =
                mock(SyncAutopilotRecoveryQuarantineReceiptMapper.class);
        SyncDirtyRecordQuarantineSupport quarantineSupport = mock(SyncDirtyRecordQuarantineSupport.class);

        SyncAutopilotRecoveryCase recoveryCase = new SyncAutopilotRecoveryCase();
        recoveryCase.setCaseId(81L);
        recoveryCase.setTenantId(10L);
        recoveryCase.setProjectId(20L);
        recoveryCase.setSyncTaskId(31L);
        recoveryCase.setRootExecutionId(40L);
        recoveryCase.setCurrentExecutionId(41L);
        SyncAutopilotRecoveryPolicyEvaluator evaluator = new SyncAutopilotRecoveryPolicyEvaluator();
        SyncAutopilotRecoveryPolicyDecision policyDecision = evaluator.evaluate(
                POLICY,
                new SyncAutopilotRecoveryEvaluationRequest(
                        com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode.AUTOPILOT,
                        10L, 20L, 31L, 1, LocalDateTime.now().plusMinutes(30),
                        ERROR_FINGERPRINT, 0,
                        com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction.APPLY_QUARANTINE,
                        com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel.LOW,
                        ACTION_FINGERPRINT, "event-1:quarantine-apply", 100, true,
                        LocalDateTime.now()));
        recoveryCase.setAuthorizationDigest(AUTHORIZATION_DIGEST);
        recoveryCase.setPolicyDigest(policyDecision.policyDigest());
        recoveryCase.setCaseState("AUTO_APPROVED");
        recoveryCase.setCycle(1);
        recoveryCase.setMaxCycles(5);
        recoveryCase.setDeadlineAt(LocalDateTime.now().plusMinutes(30));
        recoveryCase.setRecoveryAction("APPLY_QUARANTINE");
        recoveryCase.setRiskLevel("LOW");
        recoveryCase.setRepairFingerprint(ACTION_FINGERPRINT);
        recoveryCase.setLastErrorFingerprint(ERROR_FINGERPRINT);
        recoveryCase.setRepeatedErrorCount(0);
        recoveryCase.setVersion(0L);
        when(caseMapper.selectByCaseId(81L)).thenReturn(recoveryCase);

        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(10L);
        task.setProjectId(20L);
        when(taskMapper.selectById(31L)).thenReturn(task);

        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(31L);
        definition.setTenantId(10L);
        definition.setProjectId(20L);
        definition.setAutopilotPolicy(POLICY);
        when(definitionMapper.selectById(31L)).thenReturn(definition);

        return new Fixture(
                new SyncAutopilotRecoveryAutonomousQuarantineService(
                        caseMapper, taskMapper, definitionMapper, receiptMapper, quarantineSupport,
                        evaluator),
                taskMapper,
                receiptMapper,
                quarantineSupport);
    }

    private SyncAutopilotRecoveryQuarantineCommand command() {
        return new SyncAutopilotRecoveryQuarantineCommand(
                81L, 0L, 10L, 20L, 31L, 41L, 1,
                AUTHORIZATION_DIGEST, currentPolicyDigest(), PREVIEW_DIGEST,
                List.of(501L, 502L), ACTION_FINGERPRINT, "event-1:quarantine-apply");
    }

    private String currentPolicyDigest() {
        return new SyncAutopilotRecoveryPolicyEvaluator().evaluate(
                POLICY,
                new SyncAutopilotRecoveryEvaluationRequest(
                        com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode.AUTOPILOT,
                        10L, 20L, 31L, 1, LocalDateTime.now().plusMinutes(30),
                        ERROR_FINGERPRINT, 0,
                        com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction.APPLY_QUARANTINE,
                        com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel.LOW,
                        ACTION_FINGERPRINT, "event-1:quarantine-apply", 100, true,
                        LocalDateTime.now())).policyDigest();
    }

    private record Fixture(
            SyncAutopilotRecoveryAutonomousQuarantineService service,
            SyncTaskMapper taskMapper,
            SyncAutopilotRecoveryQuarantineReceiptMapper receiptMapper,
            SyncDirtyRecordQuarantineSupport quarantineSupport) {
    }
}
