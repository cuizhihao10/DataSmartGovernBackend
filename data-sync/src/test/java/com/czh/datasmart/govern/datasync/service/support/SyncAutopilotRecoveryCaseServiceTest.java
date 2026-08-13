/**
 * @Author : Cui
 * @Date: 2026/08/11 02:00
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCaseServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryReceipt;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryReceiptMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryReceiptType;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncAutopilotRecoveryCaseServiceTest {

    @Test
    void shouldPersistDecisionAndCompleteIdempotencyReceipt() {
        Fixture fixture = fixture();
        when(fixture.caseMapper().insertIfAbsent(any())).thenReturn(1);
        when(fixture.caseMapper().selectByIdentity(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> recoveryCase(81L, SyncAutopilotRecoveryCaseState.AUTO_APPROVED, 0L));
        when(fixture.receiptMapper().insertIfAbsent(any())).thenReturn(1);
        when(fixture.receiptMapper().completeReceipt("decision-1", "AUTO_APPROVED", 0L)).thenReturn(1);

        SyncAutopilotRecoveryCaseView result = fixture.service().recordDecision(decision("decision-1"));

        assertThat(result.caseId()).isEqualTo(81L);
        assertThat(result.state()).isEqualTo(SyncAutopilotRecoveryCaseState.AUTO_APPROVED);
        assertThat(result.version()).isZero();
        ArgumentCaptor<SyncAutopilotRecoveryReceipt> receipt =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryReceipt.class);
        verify(fixture.receiptMapper()).insertIfAbsent(receipt.capture());
        assertThat(receipt.getValue().getReceiptType()).isEqualTo("DECISION_RECORDED");
        assertThat(receipt.getValue().getReceiptState()).isEqualTo("PROCESSING");
    }

    @Test
    void shouldReplayCompletedReceiptWithoutApplyingDecisionAgain() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryDecisionCommand originalDecision = decision("decision-1");
        SyncAutopilotRecoveryCase existingCase = recoveryCase(
                81L, SyncAutopilotRecoveryCaseState.AUTO_APPROVED, 0L);
        when(fixture.caseMapper().selectByIdentity(any(), any(), any(), any(), any())).thenReturn(existingCase);
        SyncAutopilotRecoveryReceipt existingReceipt = new SyncAutopilotRecoveryReceipt();
        existingReceipt.setReceiptId("decision-1");
        existingReceipt.setCaseId(81L);
        existingReceipt.setReceiptDigest(SyncAutopilotRecoveryCaseService.decisionReceiptDigest(originalDecision));
        existingReceipt.setReceiptState("COMPLETED");
        existingReceipt.setResultingCaseState("AUTO_APPROVED");
        existingReceipt.setResultingVersion(0L);
        when(fixture.receiptMapper().selectByReceiptId("decision-1")).thenReturn(existingReceipt);

        SyncAutopilotRecoveryCaseView result = fixture.service().recordDecision(originalDecision);

        assertThat(result.caseId()).isEqualTo(81L);
        assertThat(result.state()).isEqualTo(SyncAutopilotRecoveryCaseState.AUTO_APPROVED);
    }

    @Test
    void shouldRejectReceiptIdReuseWithDifferentFacts() {
        Fixture fixture = fixture();
        when(fixture.caseMapper().selectByIdentity(any(), any(), any(), any(), any()))
                .thenReturn(recoveryCase(81L, SyncAutopilotRecoveryCaseState.AUTO_APPROVED, 0L));
        SyncAutopilotRecoveryReceipt existingReceipt = new SyncAutopilotRecoveryReceipt();
        existingReceipt.setReceiptId("decision-1");
        existingReceipt.setCaseId(81L);
        existingReceipt.setReceiptDigest("0".repeat(64));
        existingReceipt.setReceiptState("COMPLETED");
        when(fixture.receiptMapper().selectByReceiptId("decision-1")).thenReturn(existingReceipt);

        assertThatThrownBy(() -> fixture.service().recordDecision(decision("decision-1")))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("receiptId");
    }

    /** The decision receipt binding must include deadline to prevent unsafe receipt reuse. */
    @Test
    void decisionReceiptDigestShouldChangeWhenDeadlineChanges() {
        SyncAutopilotRecoveryDecisionCommand original = decision("decision-digest");
        SyncAutopilotRecoveryDecisionCommand changed = new SyncAutopilotRecoveryDecisionCommand(
                original.tenantId(), original.projectId(), original.syncTaskId(), original.rootExecutionId(),
                original.currentExecutionId(), original.cycle(), original.deadlineAt().plusMinutes(1),
                original.errorFingerprint(), original.repeatedErrorCount(), original.action(), original.riskLevel(),
                original.repairFingerprint(), original.receiptId(), original.confidenceScore(),
                original.evidenceAvailable());

        assertThat(SyncAutopilotRecoveryCaseService.decisionReceiptDigest(original))
                .isNotEqualTo(SyncAutopilotRecoveryCaseService.decisionReceiptDigest(changed));
    }

    @Test
    void shouldRejectStaleOptimisticTransition() {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryCase existingCase = recoveryCase(
                81L, SyncAutopilotRecoveryCaseState.AUTO_APPROVED, 2L);
        when(fixture.caseMapper().selectByCaseId(81L)).thenReturn(existingCase);

        SyncAutopilotRecoveryTransitionCommand command = new SyncAutopilotRecoveryTransitionCommand(
                81L,
                1L,
                "transition-1",
                SyncAutopilotRecoveryReceiptType.RECOVERY_STARTED,
                1002L,
                1,
                "a".repeat(64),
                0,
                null
        );

        assertThatThrownBy(() -> fixture.service().recordTransition(command))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("version");
    }

    private Fixture fixture() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryReceiptMapper receiptMapper = mock(SyncAutopilotRecoveryReceiptMapper.class);

        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(10L);
        task.setProjectId(20L);
        when(taskMapper.selectById(31L)).thenReturn(task);

        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(31L);
        definition.setTenantId(10L);
        definition.setProjectId(20L);
        definition.setAutopilotPolicy(policyJson());
        when(definitionMapper.selectById(31L)).thenReturn(definition);

        SyncExecution root = new SyncExecution();
        root.setId(1001L);
        root.setTenantId(10L);
        root.setProjectId(20L);
        root.setSyncTaskId(31L);
        when(executionMapper.selectById(1001L)).thenReturn(root);

        SyncObjectExecution failedObject = new SyncObjectExecution();
        failedObject.setObjectState("FAILED");
        /*
         * 使用真实的分片前范围探测工作单元事实。只有在 data-sync 自有的持久账本中独立发现这个瞬态 FAILED
         * 行之后，recordDecision 才能接受受限的 Python 投影。
         */
        failedObject.setWorkUnitType(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_RANGE_PROBE);
        failedObject.setLastErrorType("CONNECTOR_TRANSPORT_UNAVAILABLE");
        failedObject.setLastErrorCode("DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE");
        SyncObjectExecutionMapper objectExecutionMapper = mock(SyncObjectExecutionMapper.class);
        when(objectExecutionMapper.selectByExecutionId(1001L)).thenReturn(List.of(failedObject));
        SyncErrorSampleMapper errorSampleMapper = mock(SyncErrorSampleMapper.class);

        SyncAutopilotRecoveryCaseService service = new SyncAutopilotRecoveryCaseService(
                taskMapper,
                definitionMapper,
                executionMapper,
                objectExecutionMapper,
                errorSampleMapper,
                caseMapper,
                receiptMapper,
                new SyncAutopilotRecoveryPolicyEvaluator(),
                new SyncAutopilotRecoveryCaseStateMachine()
        );
        return new Fixture(service, caseMapper, receiptMapper);
    }

    private SyncAutopilotRecoveryDecisionCommand decision(String receiptId) {
        return new SyncAutopilotRecoveryDecisionCommand(
                10L,
                20L,
                31L,
                1001L,
                1001L,
                1,
                LocalDateTime.now().plusMinutes(30),
                "a".repeat(64),
                0,
                SyncAutopilotRecoveryAction.RETRY_EXECUTION,
                SyncAutopilotRiskLevel.LOW,
                "b".repeat(64),
                receiptId,
                95,
                true,
                Map.of(
                        "failureClass", "TRANSIENT_CONNECTOR_OR_WORKER",
                        "retryable", true,
                        "eligibleForAutomaticRetry", true,
                        "failedObjectCount", 1,
                        "rootCauseCodes", List.of("CONNECTOR_OR_NETWORK_UNAVAILABLE")
                )
        );
    }

    private SyncAutopilotRecoveryCase recoveryCase(Long caseId,
                                                    SyncAutopilotRecoveryCaseState state,
                                                    Long version) {
        SyncAutopilotRecoveryCase recoveryCase = new SyncAutopilotRecoveryCase();
        recoveryCase.setCaseId(caseId);
        recoveryCase.setTenantId(10L);
        recoveryCase.setProjectId(20L);
        recoveryCase.setSyncTaskId(31L);
        recoveryCase.setRootExecutionId(1001L);
        recoveryCase.setCurrentExecutionId(1001L);
        recoveryCase.setExecutionMode("AUTOPILOT");
        recoveryCase.setAuthorizationDigest(SyncAutopilotDigestSupport.sha256("policy-1"));
        recoveryCase.setPolicyDigest(policyDecision().policyDigest());
        recoveryCase.setCaseState(state.name());
        recoveryCase.setCycle(1);
        recoveryCase.setMaxCycles(5);
        recoveryCase.setDeadlineAt(LocalDateTime.now().plusMinutes(30));
        recoveryCase.setLastErrorFingerprint("a".repeat(64));
        recoveryCase.setRepeatedErrorCount(0);
        recoveryCase.setRecoveryAction("RETRY_EXECUTION");
        recoveryCase.setRiskLevel("LOW");
        recoveryCase.setRepairFingerprint("b".repeat(64));
        recoveryCase.setVersion(version);
        return recoveryCase;
    }

    private String policyJson() {
        return """
                {
                  "policyId":"policy-1",
                  "tenantId":10,
                  "projectId":20,
                  "expiresAt":"2099-01-01T00:00:00Z",
                  "maxRecoveryCycles":5,
                  "maxTotalDurationMinutes":120,
                  "maxAutomaticRiskLevel":"LOW",
                  "allowedRecoveryActions":["RETRY_EXECUTION"],
                  "requireApprovalFor":["CHANGE_SCHEMA"]
                }
                """;
    }

    private SyncAutopilotRecoveryPolicyDecision policyDecision() {
        SyncAutopilotRecoveryDecisionCommand command = decision("policy-digest");
        return new SyncAutopilotRecoveryPolicyEvaluator().evaluate(
                policyJson(),
                new SyncAutopilotRecoveryEvaluationRequest(
                        com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode.AUTOPILOT,
                        command.tenantId(), command.projectId(), command.syncTaskId(), command.cycle(),
                        command.deadlineAt(), command.errorFingerprint(), command.repeatedErrorCount(),
                        command.action(), command.riskLevel(), command.repairFingerprint(), command.receiptId(),
                        command.confidenceScore(), command.evidenceAvailable(), LocalDateTime.now()
                )
        );
    }

    private record Fixture(SyncAutopilotRecoveryCaseService service,
                           SyncAutopilotRecoveryCaseMapper caseMapper,
                           SyncAutopilotRecoveryReceiptMapper receiptMapper) {
    }
}
