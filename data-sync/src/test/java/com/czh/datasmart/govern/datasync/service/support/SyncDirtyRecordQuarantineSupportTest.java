/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncDirtyRecordQuarantineSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineRequest;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncDirtyRecordQuarantineSupportTest {

    @Test
    void shouldKeepManualApplyBehindExplicitConfirmation() {
        Fixture fixture = fixture("{\"strategy\":\"PRIMARY_KEY_EQ\",\"column\":\"id\",\"value\":1}");
        SyncDirtyRecordQuarantineRequest request = request();
        request.setConfirmed(false);

        assertThatThrownBy(() -> fixture.support().apply(fixture.task(), request, actor()))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("confirmed=true");
        verify(fixture.errorSampleMapper(), never()).update(any(), any());
    }

    @Test
    void shouldRejectAutonomousApplyWhenSelectorIsNotExactPrimaryKeyEquality() {
        Fixture fixture = fixture("{\"strategy\":\"RANGE\",\"column\":\"id\",\"value\":1}");
        SyncDirtyRecordQuarantineRequest request = request();

        assertThatThrownBy(() -> fixture.support().applyAutonomous(fixture.task(), request, actor()))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("PRIMARY_KEY_EQ");
        verify(fixture.errorSampleMapper(), never()).update(any(), any());
    }

    private Fixture fixture(String selectorJson) {
        SyncErrorSampleMapper errorSampleMapper = mock(SyncErrorSampleMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(10L);
        task.setProjectId(20L);
        SyncExecution execution = new SyncExecution();
        execution.setId(41L);
        execution.setSyncTaskId(31L);
        when(executionMapper.selectById(41L)).thenReturn(execution);
        SyncErrorSample sample = new SyncErrorSample();
        sample.setId(501L);
        sample.setSyncTaskId(31L);
        sample.setExecutionId(41L);
        sample.setTenantId(10L);
        sample.setRetryable(true);
        sample.setResolutionStatus("OPEN");
        sample.setSourceRecordKey(selectorJson);
        when(errorSampleMapper.selectList(any())).thenReturn(List.of(sample));
        return new Fixture(
                new SyncDirtyRecordQuarantineSupport(
                        errorSampleMapper, executionMapper, auditSupport, new ObjectMapper()),
                errorSampleMapper,
                task);
    }

    private SyncDirtyRecordQuarantineRequest request() {
        SyncDirtyRecordQuarantineRequest request = new SyncDirtyRecordQuarantineRequest();
        request.setExecutionId(41L);
        request.setErrorSampleIds(List.of(501L));
        request.setQuarantineAllRetryableInExecution(false);
        request.setReason(SyncDirtyRecordQuarantineSupport.AUTOPILOT_QUARANTINE_REASON);
        request.setConfirmationDigest("e".repeat(64));
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(10L, null, "AGENT_AUTOPILOT", "event-1");
    }

    private record Fixture(
            SyncDirtyRecordQuarantineSupport support,
            SyncErrorSampleMapper errorSampleMapper,
            SyncTask task) {
    }
}
