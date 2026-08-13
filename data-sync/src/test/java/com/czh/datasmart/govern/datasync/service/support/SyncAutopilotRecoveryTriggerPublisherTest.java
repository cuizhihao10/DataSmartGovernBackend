/**
 * @Author : Cui
 * @Date: 2026/08/11 02:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerPublisherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncAutopilotRecoveryTriggerPublisherTest {

    /**
     * 恢复 trigger 必须使用独立事务，使 transition 与 outbox 原子提交，同时避免回滚调用方
     * 已经持久化的 execution failed 事实。
     */
    @Test
    void shouldDeclareRequiresNewTransactionBoundary() throws Exception {
        Transactional transactional = SyncAutopilotRecoveryTriggerPublisher.class
                .getMethod("publishFailed", SyncTask.class, SyncExecution.class, String.class, List.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void shouldEnqueueLowSensitiveFailureTriggerFromPersistedAuthorization() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        when(definitionMapper.selectById(31L)).thenReturn(definition(policyJson()));

        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper, caseMapper, caseService, outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        publisher.publishFailed(task(), execution(1001L), "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        ArgumentCaptor<SyncAutopilotRecoveryTriggerEvent> event =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerEvent.class);
        verify(outboxService).enqueueAndDispatch(event.capture());
        assertThat(event.getValue().rootSessionId()).isEqualTo("session-1");
        assertThat(event.getValue().rootRunId()).isEqualTo("run-1");
        assertThat(event.getValue().syncTaskId()).isEqualTo(31L);
        assertThat(event.getValue().cycle()).isEqualTo(1);
        assertThat(event.getValue().errorFingerprint()).matches("[0-9a-f]{64}");
        assertThat(event.getValue().issueCodes()).containsExactly("TARGET_TIMEOUT");
        assertThat(event.getValue().toString()).doesNotContain("jdbc", "password", "SELECT");
    }

    @Test
    void shouldAdvanceLineageAfterARecoveryExecutionFails() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        when(definitionMapper.selectById(31L)).thenReturn(definition(policyJson()));
        SyncAutopilotRecoveryCase active = activeCase();
        when(caseMapper.selectRecoveringByCurrentExecution(10L, 31L, 1002L)).thenReturn(active);

        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper, caseMapper, caseService, outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        publisher.publishFailed(task(), execution(1002L), "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        verify(caseService).recordTransition(any(SyncAutopilotRecoveryTransitionCommand.class));
        ArgumentCaptor<SyncAutopilotRecoveryTriggerEvent> event =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerEvent.class);
        verify(outboxService).enqueueAndDispatch(event.capture());
        assertThat(event.getValue().rootExecutionId()).isEqualTo(1001L);
        assertThat(event.getValue().cycle()).isEqualTo(2);
        assertThat(event.getValue().repeatedErrorCount()).isEqualTo(1);
        assertThat(event.getValue().previousRepairFingerprint()).isEqualTo("c".repeat(64));
    }

    /** 成功回执应把同一 execution 的活动恢复案例收敛为 RECOVERY_SUCCEEDED。 */
    @Test
    void shouldCloseActiveRecoveryCaseAfterExecutionSucceeds() {
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryCase active = activeCase();
        when(caseMapper.selectRecoveringByCurrentExecution(10L, 31L, 1002L)).thenReturn(active);
        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                mock(SyncTaskDefinitionMapper.class),
                caseMapper,
                caseService,
                mock(SyncAutopilotRecoveryTriggerOutboxService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        publisher.publishSucceeded(task(), execution(1002L));

        ArgumentCaptor<SyncAutopilotRecoveryTransitionCommand> command =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTransitionCommand.class);
        verify(caseService).recordTransition(command.capture());
        assertThat(command.getValue().caseId()).isEqualTo(81L);
        assertThat(command.getValue().receiptType().name()).isEqualTo("RECOVERY_SUCCEEDED");
        assertThat(command.getValue().currentExecutionId()).isEqualTo(1002L);
    }

    @Test
    void shouldSkipTasksWithoutAutopilotAuthorization() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        when(definitionMapper.selectById(31L)).thenReturn(definition(null));
        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper,
                mock(SyncAutopilotRecoveryCaseMapper.class),
                mock(SyncAutopilotRecoveryCaseService.class),
                outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());

        publisher.publishFailed(task(), execution(1001L), "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        verify(outboxService, never()).enqueueAndDispatch(any());
    }

    /**
     * An expiry represented by a later local clock value can still be expired once its offset is applied.
     */
    @Test
    void shouldRejectExpiredAuthorizationByInstantAcrossOffsets() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        String expiredWithPositiveOffset = OffsetDateTime.now(ZoneOffset.UTC)
                .minusMinutes(1)
                .withOffsetSameInstant(ZoneOffset.ofHours(8))
                .toString();
        when(definitionMapper.selectById(31L)).thenReturn(definition(
                policyJson().replace("2099-01-01T00:00:00Z", expiredWithPositiveOffset)));
        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper,
                mock(SyncAutopilotRecoveryCaseMapper.class),
                mock(SyncAutopilotRecoveryCaseService.class),
                outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());

        publisher.publishFailed(task(), execution(1001L), "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        verify(outboxService, never()).enqueueAndDispatch(any());
    }

    /**
     * The shorter policy expiry is normalized to UTC even when the authorization snapshot uses another offset.
     */
    @Test
    void shouldChooseTheEarliestDeadlineByInstantAndNormalizeItToUtc() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        String expiryWithPositiveOffset = OffsetDateTime.now(ZoneOffset.UTC)
                .plusMinutes(10)
                .withOffsetSameInstant(ZoneOffset.ofHours(8))
                .toString();
        when(definitionMapper.selectById(31L)).thenReturn(definition(
                policyJson().replace("2099-01-01T00:00:00Z", expiryWithPositiveOffset)));
        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper,
                mock(SyncAutopilotRecoveryCaseMapper.class),
                mock(SyncAutopilotRecoveryCaseService.class),
                outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());

        publisher.publishFailed(task(), execution(1001L), "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        ArgumentCaptor<SyncAutopilotRecoveryTriggerEvent> event =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerEvent.class);
        verify(outboxService).enqueueAndDispatch(event.capture());
        OffsetDateTime emittedDeadline = OffsetDateTime.parse(event.getValue().deadlineAt());
        assertThat(emittedDeadline.toInstant()).isEqualTo(OffsetDateTime.parse(expiryWithPositiveOffset).toInstant());
        assertThat(emittedDeadline.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(10L);
        task.setProjectId(20L);
        return task;
    }

    private SyncExecution execution(Long executionId) {
        SyncExecution execution = new SyncExecution();
        execution.setId(executionId);
        execution.setTenantId(10L);
        execution.setProjectId(20L);
        execution.setSyncTaskId(31L);
        return execution;
    }

    private SyncTaskDefinition definition(String policy) {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(31L);
        definition.setTenantId(10L);
        definition.setProjectId(20L);
        definition.setAutopilotPolicy(policy);
        return definition;
    }

    private SyncAutopilotRecoveryCase activeCase() {
        SyncAutopilotRecoveryCase active = new SyncAutopilotRecoveryCase();
        active.setCaseId(81L);
        active.setTenantId(10L);
        active.setProjectId(20L);
        active.setSyncTaskId(31L);
        active.setRootExecutionId(1001L);
        active.setCurrentExecutionId(1002L);
        active.setCaseState(SyncAutopilotRecoveryCaseState.RECOVERY_STARTED.name());
        active.setCycle(1);
        active.setLastErrorFingerprint(SyncAutopilotRecoveryTriggerPublisher.errorFingerprint(
                "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT")));
        active.setRepeatedErrorCount(0);
        active.setRepairFingerprint("c".repeat(64));
        active.setVersion(1L);
        return active;
    }

    private String policyJson() {
        return """
                {
                  "policyId":"policy-1",
                  "policyDigest":"sha256:abcdef",
                  "rootSessionId":"session-1",
                  "rootRunId":"run-1",
                  "tenantId":10,
                  "applicationId":100,
                  "projectId":20,
                  "userId":"9001",
                  "actorId":"9001",
                  "agentId":"OPENCLAW",
                  "delegationId":"delegation-1",
                  "issuedAt":"2026-08-11T00:00:00Z",
                  "expiresAt":"2099-01-01T00:00:00Z",
                  "maxRecoveryCycles":5,
                  "maxTotalDurationMinutes":120,
                  "maxAutomaticRiskLevel":"LOW",
                  "allowedRecoveryActions":["RETRY_EXECUTION"],
                  "requireApprovalFor":["CHANGE_SCHEMA"]
                }
                """;
    }
}
