/**
 * @Author : Cui
 * @Date: 2026/08/11 02:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerPublisherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryCaseMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
        SyncExecutionPolicyMapper policyMapper = mock(SyncExecutionPolicyMapper.class);
        SyncExecutionPolicy override = new SyncExecutionPolicy();
        override.setId(92L);
        override.setEnabled(true);
        when(policyMapper.selectOne(any())).thenReturn(override);
        when(definitionMapper.selectById(31L)).thenReturn(definition(policyJson()));
        SyncAutopilotRecoveryCase active = activeCase();
        when(caseMapper.selectRecoveringByCurrentExecution(10L, 31L, 1002L)).thenReturn(active);

        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper, caseMapper, caseService, outboxService,
                policyMapper, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        publisher.publishFailed(task(), execution(1002L), "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        verify(caseService).recordTransition(any(SyncAutopilotRecoveryTransitionCommand.class));
        ArgumentCaptor<SyncAutopilotRecoveryTriggerEvent> event =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerEvent.class);
        verify(outboxService).enqueueAndDispatch(event.capture());
        assertThat(event.getValue().rootExecutionId()).isEqualTo(1001L);
        assertThat(event.getValue().cycle()).isEqualTo(2);
        assertThat(event.getValue().repeatedErrorCount()).isEqualTo(1);
        assertThat(event.getValue().previousRepairFingerprint()).isEqualTo("c".repeat(64));
        ArgumentCaptor<SyncExecutionPolicy> disabled = ArgumentCaptor.forClass(SyncExecutionPolicy.class);
        verify(policyMapper).updateById(disabled.capture());
        assertThat(disabled.getValue().getEnabled()).isFalse();
        assertThat(disabled.getValue().getDescription()).contains("恢复执行失败");
    }

    /**
     * 低风险修复已经安全执行但未满足应用前提时，系统应把新发现的问题作为下一轮证据，
     * 而不是把整个无人值守 Loop 永久停在第一次不合适的动作上。
     */
    @Test
    void shouldEnqueueNextCycleWhenGovernedRepairWasNotApplied() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        when(definitionMapper.selectById(31L)).thenReturn(definition(policyJson()));
        SyncAutopilotRecoveryCase active = activeCase();
        active.setCaseState(SyncAutopilotRecoveryCaseState.AUTO_APPROVED.name());
        active.setRecoveryAction("REFRESH_METADATA");

        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper, caseMapper, caseService, outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        SyncAutopilotRecoveryRepairReplanResult result = publisher.publishRepairNotApplied(
                task(), execution(1002L), active,
                "AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED",
                List.of("METADATA_TARGET_FIELD_NOT_FOUND"));

        assertThat(result.queued()).isTrue();
        assertThat(result.nextCycle()).isEqualTo(2);
        assertThat(result.eventId()).startsWith("autopilot-trigger:");
        ArgumentCaptor<SyncAutopilotRecoveryTransitionCommand> transition =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTransitionCommand.class);
        verify(caseService).recordTransition(transition.capture());
        assertThat(transition.getValue().receiptType().name()).isEqualTo("RECOVERY_FAILED");
        assertThat(transition.getValue().cycle()).isEqualTo(2);
        assertThat(transition.getValue().attentionReason())
                .isEqualTo("AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED");

        ArgumentCaptor<SyncAutopilotRecoveryTriggerEvent> event =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerEvent.class);
        verify(outboxService).enqueueAndDispatch(event.capture());
        assertThat(event.getValue().cycle()).isEqualTo(2);
        assertThat(event.getValue().previousRepairFingerprint()).isEqualTo("c".repeat(64));
        assertThat(event.getValue().issueCodes()).contains(
                "AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED",
                "PREVIOUS_REPAIR_ACTION_REFRESH_METADATA",
                "METADATA_TARGET_FIELD_NOT_FOUND");
    }

    /**
     * PostgreSQL timestamp 只能保存微秒，纳秒授权截止时间写入 case 后可能向上舍入。
     * 下一轮事件必须重新取原始授权到期时间作为上界，不能因 100 纳秒差值被 Java 误判为越权。
     */
    @Test
    void shouldCapReplanDeadlineAtOriginalAuthorizationExpiryAfterDatabaseRounding() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        String authorizationExpiry = "2099-01-01T00:00:00.123456700Z";
        when(definitionMapper.selectById(31L)).thenReturn(definition(
                policyJson().replace("2099-01-01T00:00:00Z", authorizationExpiry)));
        SyncAutopilotRecoveryCase active = activeCase();
        active.setCaseState(SyncAutopilotRecoveryCaseState.AUTO_APPROVED.name());
        active.setRecoveryAction("REFRESH_METADATA");
        active.setDeadlineAt(LocalDateTime.parse("2099-01-01T00:00:00.123457"));

        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper, mock(SyncAutopilotRecoveryCaseMapper.class), caseService, outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        publisher.publishRepairNotApplied(
                task(), execution(1002L), active,
                "AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED",
                List.of("METADATA_TARGET_FIELD_NOT_FOUND"));

        ArgumentCaptor<SyncAutopilotRecoveryTriggerEvent> event =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerEvent.class);
        verify(outboxService).enqueueAndDispatch(event.capture());
        assertThat(OffsetDateTime.parse(event.getValue().deadlineAt()).toInstant())
                .isEqualTo(OffsetDateTime.parse(authorizationExpiry).toInstant());
    }

    /** 授权轮次已经耗尽时仍需收敛旧 case，但绝不能继续发布下一轮事件。 */
    @Test
    void shouldStopRepairReplanWhenRecoveryCycleBudgetIsExhausted() {
        SyncTaskDefinitionMapper definitionMapper = mock(SyncTaskDefinitionMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryTriggerOutboxService outboxService = mock(SyncAutopilotRecoveryTriggerOutboxService.class);
        when(definitionMapper.selectById(31L)).thenReturn(definition(policyJson()));
        SyncAutopilotRecoveryCase active = activeCase();
        active.setCaseState(SyncAutopilotRecoveryCaseState.AUTO_APPROVED.name());
        active.setRecoveryAction("REFRESH_METADATA");
        active.setCycle(5);
        active.setMaxCycles(5);

        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                definitionMapper, mock(SyncAutopilotRecoveryCaseMapper.class), caseService, outboxService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        SyncAutopilotRecoveryRepairReplanResult result = publisher.publishRepairNotApplied(
                task(), execution(1002L), active,
                "AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED",
                List.of("METADATA_TARGET_FIELD_NOT_FOUND"));

        assertThat(result.queued()).isFalse();
        assertThat(result.nextCycle()).isEqualTo(6);
        verify(caseService).recordTransition(any(SyncAutopilotRecoveryTransitionCommand.class));
        verify(outboxService, never()).enqueueAndDispatch(any());
    }

    /** 成功回执应把同一 execution 的活动恢复案例收敛为 RECOVERY_SUCCEEDED。 */
    @Test
    void shouldCloseActiveRecoveryCaseAfterExecutionSucceeds() {
        SyncAutopilotRecoveryCaseMapper caseMapper = mock(SyncAutopilotRecoveryCaseMapper.class);
        SyncAutopilotRecoveryCaseService caseService = mock(SyncAutopilotRecoveryCaseService.class);
        SyncAutopilotRecoveryCase active = activeCase();
        SyncExecutionPolicyMapper policyMapper = mock(SyncExecutionPolicyMapper.class);
        SyncExecutionPolicy override = new SyncExecutionPolicy();
        override.setId(91L);
        override.setEnabled(true);
        when(policyMapper.selectOne(any())).thenReturn(override);
        when(caseMapper.selectRecoveringByCurrentExecution(10L, 31L, 1002L)).thenReturn(active);
        SyncAutopilotRecoveryTriggerPublisher publisher = new SyncAutopilotRecoveryTriggerPublisher(
                mock(SyncTaskDefinitionMapper.class),
                caseMapper,
                caseService,
                mock(SyncAutopilotRecoveryTriggerOutboxService.class),
                policyMapper,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                null);

        publisher.publishSucceeded(task(), execution(1002L));

        ArgumentCaptor<SyncAutopilotRecoveryTransitionCommand> command =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTransitionCommand.class);
        verify(caseService).recordTransition(command.capture());
        assertThat(command.getValue().caseId()).isEqualTo(81L);
        assertThat(command.getValue().receiptType().name()).isEqualTo("RECOVERY_SUCCEEDED");
        assertThat(command.getValue().currentExecutionId()).isEqualTo(1002L);
        ArgumentCaptor<SyncExecutionPolicy> disabled = ArgumentCaptor.forClass(SyncExecutionPolicy.class);
        verify(policyMapper).updateById(disabled.capture());
        assertThat(disabled.getValue().getEnabled()).isFalse();
        assertThat(disabled.getValue().getDescription()).contains("恢复成功");
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
     * 带时区偏移的本地时间即使钟面值更晚，换算为同一时刻后仍可能已经过期。
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
     * 即使授权快照使用其他时区偏移，更短的策略截止时间也必须规范化为 UTC 后再发布。
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
