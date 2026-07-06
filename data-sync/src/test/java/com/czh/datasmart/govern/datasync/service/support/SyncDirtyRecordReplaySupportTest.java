/**
 * @Author : Cui
 * @Date: 2026/07/08 00:22
 * @Description DataSmart Govern Backend - SyncDirtyRecordReplaySupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionRecoveryPlanMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 脏数据修复重放测试。
 *
 * <p>这组测试验证的是“结构化脏数据落盘后的治理闭环”，不是 JDBC 读写本身：
 * 1. 可重试错误样本可以派生成新的 replay execution；
 * 2. 恢复计划中会写入低敏 errorSampleSelector，worker 后续可据此读取待重放样本；
 * 3. 未确认修复、不可重试样本和跨范围样本会 fail-closed；
 * 4. 审计只记录样本数量、来源 execution、新 execution 和策略摘要，不记录原始坏行。</p>
 */
class SyncDirtyRecordReplaySupportTest {

    /**
     * 精确选择错误样本时，应创建 replay execution 与恢复计划。
     */
    @Test
    void selectedRetryableSamplesShouldCreateReplayPlan() {
        Fixture fixture = fixture();
        SyncTask task = task(SyncTaskState.SUCCEEDED);
        SyncExecution sourceExecution = execution(88L, SyncExecutionState.SUCCEEDED);
        SyncExecution replayExecution = execution(99L, SyncExecutionState.QUEUED);
        SyncDirtyRecordReplayRequest request = request(List.of(501L, 502L), false);
        when(fixture.executionMapper().selectById(88L)).thenReturn(sourceExecution);
        when(fixture.errorSampleMapper().selectList(any())).thenReturn(List.of(sample(501L, true), sample(502L, true)));
        when(fixture.executionCreationSupport().createQueuedExecution(task, actor(), SyncTriggerType.REPLAY))
                .thenReturn(replayExecution);
        when(fixture.recoveryPlanMapper().insert(any(SyncExecutionRecoveryPlan.class))).thenAnswer(invocation -> {
            SyncExecutionRecoveryPlan plan = invocation.getArgument(0);
            plan.setId(7001L);
            return 1;
        });
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.REPLAY.name(), 99L))
                .thenReturn(1);

        SyncDirtyRecordReplayResult result = fixture.support().replayDirtyRecords(task, request, actor());

        ArgumentCaptor<SyncExecutionRecoveryPlan> planCaptor = ArgumentCaptor.forClass(SyncExecutionRecoveryPlan.class);
        verify(fixture.recoveryPlanMapper()).insert(planCaptor.capture());
        SyncExecutionRecoveryPlan plan = planCaptor.getValue();
        assertThat(plan.getRecoveryType()).isEqualTo(SyncTriggerType.REPLAY.name());
        assertThat(plan.getSourceExecutionId()).isEqualTo(88L);
        assertThat(plan.getShardOrPartition()).isEqualTo("DIRTY_RECORD_REPLAY");
        assertThat(plan.getErrorSampleSelector()).contains("\"selectorMode\":\"SELECTED_IDS\"");
        assertThat(plan.getErrorSampleSelector()).contains("\"errorSampleIds\":[501,502]");
        assertThat(plan.getErrorSampleSelector()).doesNotContain("sample_payload");
        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.REPLAY.name(), 99L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(99L),
                eq(SyncAuditActionType.REPLAY_DIRTY_RECORDS), eq(actor()), contains("sampleCount=2"));
        assertThat(result.recoveryPlanId()).isEqualTo(7001L);
        assertThat(result.selectorMode()).isEqualTo("SELECTED_IDS");
        assertThat(result.selectedSampleCount()).isEqualTo(2);
    }

    /**
     * 未显式确认修复时，不允许派生重放计划。
     */
    @Test
    void replayShouldRequireRepairConfirmationBeforeReadingSamples() {
        Fixture fixture = fixture();
        SyncTask task = task(SyncTaskState.FAILED);
        SyncDirtyRecordReplayRequest request = request(List.of(501L), false);
        request.setRepairConfirmed(false);

        assertThrows(PlatformBusinessException.class,
                () -> fixture.support().replayDirtyRecords(task, request, actor()));

        verify(fixture.executionMapper(), never()).selectById(any());
        verify(fixture.recoveryPlanMapper(), never()).insert(any(SyncExecutionRecoveryPlan.class));
    }

    /**
     * retryable=false 的错误样本不能走普通修复重放。
     *
     * <p>例如字段映射错误或类型转换错误，如果不先修配置，重放只会再次失败。
     * 这类样本后续应进入更高风险审批或配置修复流程，而不是直接重跑。</p>
     */
    @Test
    void nonRetryableSamplesShouldBeRejected() {
        Fixture fixture = fixture();
        SyncTask task = task(SyncTaskState.FAILED);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution(88L, SyncExecutionState.FAILED));
        when(fixture.errorSampleMapper().selectList(any())).thenReturn(List.of(sample(501L, false)));

        assertThrows(PlatformBusinessException.class,
                () -> fixture.support().replayDirtyRecords(task, request(List.of(501L), false), actor()));

        verify(fixture.executionCreationSupport(), never()).createQueuedExecution(any(), any(), any());
        verify(fixture.recoveryPlanMapper(), never()).insert(any(SyncExecutionRecoveryPlan.class));
    }

    /**
     * 全选可重试样本模式必须显式声明，并使用 ALL_RETRYABLE_IN_EXECUTION selector。
     */
    @Test
    void allRetryableModeShouldCreateAllRetryableSelector() {
        Fixture fixture = fixture();
        SyncTask task = task(SyncTaskState.PARTIALLY_SUCCEEDED);
        SyncExecution sourceExecution = execution(88L, SyncExecutionState.PARTIALLY_SUCCEEDED);
        SyncExecution replayExecution = execution(100L, SyncExecutionState.QUEUED);
        SyncDirtyRecordReplayRequest request = request(List.of(), true);
        request.setMaxSampleCount(10);
        when(fixture.executionMapper().selectById(88L)).thenReturn(sourceExecution);
        when(fixture.errorSampleMapper().selectList(any())).thenReturn(List.of(sample(601L, true)));
        when(fixture.executionCreationSupport().createQueuedExecution(task, actor(), SyncTriggerType.REPLAY))
                .thenReturn(replayExecution);
        when(fixture.recoveryPlanMapper().insert(any(SyncExecutionRecoveryPlan.class))).thenAnswer(invocation -> {
            SyncExecutionRecoveryPlan plan = invocation.getArgument(0);
            plan.setId(7002L);
            return 1;
        });
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.REPLAY.name(), 100L))
                .thenReturn(1);

        SyncDirtyRecordReplayResult result = fixture.support().replayDirtyRecords(task, request, actor());

        ArgumentCaptor<SyncExecutionRecoveryPlan> planCaptor = ArgumentCaptor.forClass(SyncExecutionRecoveryPlan.class);
        verify(fixture.recoveryPlanMapper()).insert(planCaptor.capture());
        assertThat(planCaptor.getValue().getErrorSampleSelector()).contains("\"selectorMode\":\"ALL_RETRYABLE_IN_EXECUTION\"");
        assertThat(result.selectorMode()).isEqualTo("ALL_RETRYABLE_IN_EXECUTION");
        assertThat(result.selectedSampleCount()).isEqualTo(1);
    }

    private Fixture fixture() {
        SyncErrorSampleMapper errorSampleMapper = mock(SyncErrorSampleMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionRecoveryPlanMapper recoveryPlanMapper = mock(SyncExecutionRecoveryPlanMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionCreationSupport executionCreationSupport = mock(SyncExecutionCreationSupport.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncDirtyRecordReplaySupport support = new SyncDirtyRecordReplaySupport(
                errorSampleMapper,
                executionMapper,
                recoveryPlanMapper,
                taskMapper,
                executionCreationSupport,
                auditSupport,
                new ObjectMapper()
        );
        return new Fixture(support, errorSampleMapper, executionMapper, recoveryPlanMapper, taskMapper,
                executionCreationSupport, auditSupport);
    }

    private SyncDirtyRecordReplayRequest request(List<Long> ids, boolean allRetryable) {
        SyncDirtyRecordReplayRequest request = new SyncDirtyRecordReplayRequest();
        request.setExecutionId(88L);
        request.setErrorSampleIds(ids);
        request.setReplayAllRetryableInExecution(allRetryable);
        request.setRepairConfirmed(true);
        request.setRepairStrategy("mapping_fixed_replay");
        request.setReason("已修复字段映射后重放错误样本");
        return request;
    }

    private SyncTask task(SyncTaskState state) {
        SyncTask task = new SyncTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setCurrentState(state.name());
        return task;
    }

    private SyncExecution execution(Long id, SyncExecutionState state) {
        SyncExecution execution = new SyncExecution();
        execution.setId(id);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(1L);
        execution.setExecutionState(state.name());
        return execution;
    }

    private SyncErrorSample sample(Long id, boolean retryable) {
        SyncErrorSample sample = new SyncErrorSample();
        sample.setId(id);
        sample.setTenantId(7L);
        sample.setProjectId(101L);
        sample.setWorkspaceId(301L);
        sample.setSyncTaskId(1L);
        sample.setExecutionId(88L);
        sample.setErrorType(retryable ? "DUPLICATE_KEY" : "FIELD_MAPPING_ERROR");
        sample.setRetryable(retryable);
        sample.setSourceRecordKey("rowHash=abc");
        sample.setSamplePayload("{\"columns\":\"[id,name]\",\"rowHash\":\"abc\"}");
        return sample;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "PROJECT_OWNER",
                "trace-dirty-record-replay",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    /**
     * 测试夹具。
     *
     * <p>脏数据修复重放涉及错误样本、execution、恢复计划、任务主状态和审计。
     * 用 fixture 把依赖集中起来，可以让每个测试只表达业务规则本身。</p>
     */
    private record Fixture(SyncDirtyRecordReplaySupport support,
                           SyncErrorSampleMapper errorSampleMapper,
                           SyncExecutionMapper executionMapper,
                           SyncExecutionRecoveryPlanMapper recoveryPlanMapper,
                           SyncTaskMapper taskMapper,
                           SyncExecutionCreationSupport executionCreationSupport,
                           SyncAuditSupport auditSupport) {
    }
}
