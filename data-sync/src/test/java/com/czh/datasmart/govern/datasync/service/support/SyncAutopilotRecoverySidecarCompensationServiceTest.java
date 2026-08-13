/**
 * @Author : Cui
 * @Date: 2026/08/11 23:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoverySidecarCompensationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoverySidecarCompensation;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoverySidecarCompensationMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that a sidecar transaction failure stores only low-sensitive replay facts and reuses the existing
 * Autopilot trigger publisher on a later scheduler pass.
 */
class SyncAutopilotRecoverySidecarCompensationServiceTest {

    @Test
    void recordFailedTriggerShouldPersistOneLowSensitiveRetryIntent() {
        SyncAutopilotRecoverySidecarCompensationMapper compensationMapper =
                mock(SyncAutopilotRecoverySidecarCompensationMapper.class);
        SyncAutopilotRecoverySidecarCompensationService service = service(compensationMapper);
        SyncTask task = task();
        SyncExecution execution = execution();

        service.recordFailedTrigger(task, execution, "TARGET_TIMEOUT",
                List.of("TARGET_TIMEOUT", "jdbc:mysql://secret-host"));

        ArgumentCaptor<SyncAutopilotRecoverySidecarCompensation> captor =
                ArgumentCaptor.forClass(SyncAutopilotRecoverySidecarCompensation.class);
        verify(compensationMapper).insertIfAbsent(captor.capture());
        SyncAutopilotRecoverySidecarCompensation compensation = captor.getValue();
        assertThat(compensation.getCompensationKey()).startsWith("autopilot-sidecar:");
        assertThat(compensation.getOperation()).isEqualTo("TRIGGER_FAILURE");
        assertThat(compensation.getCompensationState()).isEqualTo("PENDING");
        assertThat(compensation.getErrorCode()).isEqualTo("TARGET_TIMEOUT");
        assertThat(compensation.getIssueCodesJson()).doesNotContain("jdbc:", "secret-host");
    }

    @Test
    void replayDueShouldInvokeExistingTriggerPublisherThenResolveCompensation() {
        SyncAutopilotRecoverySidecarCompensationMapper compensationMapper =
                mock(SyncAutopilotRecoverySidecarCompensationMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        SyncAutopilotRecoveryTriggerProperties properties = new SyncAutopilotRecoveryTriggerProperties();
        SyncAutopilotRecoverySidecarCompensationService service =
                new SyncAutopilotRecoverySidecarCompensationService(
                        compensationMapper, taskMapper, executionMapper, triggerPublisher, properties,
                        new ObjectMapper());
        SyncAutopilotRecoverySidecarCompensation compensation = new SyncAutopilotRecoverySidecarCompensation();
        compensation.setId(91L);
        compensation.setOperation("TRIGGER_FAILURE");
        compensation.setSyncTaskId(31L);
        compensation.setSyncExecutionId(1001L);
        compensation.setErrorCode("TARGET_TIMEOUT");
        compensation.setIssueCodesJson("[\"TARGET_TIMEOUT\"]");
        compensation.setAttemptCount(0);
        compensation.setMaxAttemptCount(3);
        when(compensationMapper.selectDue(20, 300L)).thenReturn(List.of(compensation));
        when(compensationMapper.markDispatching(eq(91L), eq(300L), anyString())).thenReturn(1);
        when(compensationMapper.markResolved(eq(91L), anyString())).thenReturn(1);
        SyncTask task = task();
        SyncExecution execution = execution();
        when(taskMapper.selectById(31L)).thenReturn(task);
        when(executionMapper.selectById(1001L)).thenReturn(execution);

        int resolved = service.replayDue();

        assertThat(resolved).isEqualTo(1);
        verify(triggerPublisher).publishFailed(eq(task), eq(execution), eq("TARGET_TIMEOUT"),
                eq(List.of("TARGET_TIMEOUT")));
        ArgumentCaptor<String> claimToken = ArgumentCaptor.forClass(String.class);
        verify(compensationMapper).markDispatching(eq(91L), eq(300L), claimToken.capture());
        verify(compensationMapper).markResolved(eq(91L), eq(claimToken.getValue()));
    }

    @Test
    void replayDueShouldDeadLetterAnExhaustedReplayAndRecordOneLowCardinalityMetric() {
        SyncAutopilotRecoverySidecarCompensationMapper compensationMapper =
                mock(SyncAutopilotRecoverySidecarCompensationMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        SyncAutopilotRecoveryMetrics metrics = mock(SyncAutopilotRecoveryMetrics.class);
        SyncAutopilotRecoveryTriggerProperties properties = new SyncAutopilotRecoveryTriggerProperties();
        SyncAutopilotRecoverySidecarCompensationService service =
                new SyncAutopilotRecoverySidecarCompensationService(
                        compensationMapper, taskMapper, executionMapper, triggerPublisher, properties,
                        new ObjectMapper(), metrics);
        SyncAutopilotRecoverySidecarCompensation compensation = compensation();
        compensation.setAttemptCount(2);
        compensation.setMaxAttemptCount(3);
        when(compensationMapper.selectDue(20, 300L)).thenReturn(List.of(compensation));
        when(compensationMapper.markDispatching(eq(91L), eq(300L), anyString())).thenReturn(1);
        when(taskMapper.selectById(31L)).thenReturn(task());
        when(executionMapper.selectById(1001L)).thenReturn(execution());
        doThrow(new IllegalStateException("sidecar unavailable"))
                .when(triggerPublisher).publishFailed(any(), any(), anyString(), any());
        when(compensationMapper.markFailure(eq(91L), anyString(), eq("DEAD_LETTER"), isNull(),
                any(LocalDateTime.class), eq("AUTOPILOT_SIDECAR_REPLAY_FAILED"),
                eq("Autopilot sidecar replay could not be completed"))).thenReturn(1);

        int resolved = service.replayDue();

        assertThat(resolved).isZero();
        verify(metrics).recordSidecarCompensationDeadLetter();
    }

    @Test
    void replayDueShouldFinalizeStaleExhaustedClaimsBeforeSelectingNewWork() {
        SyncAutopilotRecoverySidecarCompensationMapper compensationMapper =
                mock(SyncAutopilotRecoverySidecarCompensationMapper.class);
        SyncAutopilotRecoveryMetrics metrics = mock(SyncAutopilotRecoveryMetrics.class);
        SyncAutopilotRecoverySidecarCompensationService service =
                new SyncAutopilotRecoverySidecarCompensationService(
                        compensationMapper,
                        mock(SyncTaskMapper.class),
                        mock(SyncExecutionMapper.class),
                        mock(SyncAutopilotRecoveryTriggerPublisher.class),
                        new SyncAutopilotRecoveryTriggerProperties(),
                        new ObjectMapper(),
                        metrics);
        when(compensationMapper.deadLetterExhaustedStaleClaims(300L, 20)).thenReturn(2);
        when(compensationMapper.selectDue(20, 300L)).thenReturn(List.of());

        int resolved = service.replayDue();

        assertThat(resolved).isZero();
        verify(metrics, times(2)).recordSidecarCompensationDeadLetter();
    }

    private SyncAutopilotRecoverySidecarCompensationService service(
            SyncAutopilotRecoverySidecarCompensationMapper compensationMapper) {
        return new SyncAutopilotRecoverySidecarCompensationService(
                compensationMapper,
                mock(SyncTaskMapper.class),
                mock(SyncExecutionMapper.class),
                mock(SyncAutopilotRecoveryTriggerPublisher.class),
                new SyncAutopilotRecoveryTriggerProperties(),
                new ObjectMapper());
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(10L);
        task.setProjectId(20L);
        return task;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(1001L);
        execution.setSyncTaskId(31L);
        execution.setTenantId(10L);
        execution.setProjectId(20L);
        return execution;
    }

    /** Creates one due trigger-replay row with the minimum durable identity required by the service. */
    private SyncAutopilotRecoverySidecarCompensation compensation() {
        SyncAutopilotRecoverySidecarCompensation compensation = new SyncAutopilotRecoverySidecarCompensation();
        compensation.setId(91L);
        compensation.setOperation("TRIGGER_FAILURE");
        compensation.setSyncTaskId(31L);
        compensation.setSyncExecutionId(1001L);
        compensation.setErrorCode("TARGET_TIMEOUT");
        compensation.setIssueCodesJson("[\"TARGET_TIMEOUT\"]");
        return compensation;
    }
}
