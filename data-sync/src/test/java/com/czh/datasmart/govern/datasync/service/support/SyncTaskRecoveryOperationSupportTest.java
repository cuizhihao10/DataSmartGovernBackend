/**
 * @Author : Cui
 * @Date: 2026/06/27 22:35
 * @Description DataSmart Govern Backend - SyncTaskRecoveryOperationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionRecoveryPlanMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
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
 * 同步任务 replay/backfill 恢复操作测试。
 *
 * <p>这组测试验证的是“控制面恢复契约”，不是实际数据搬运：
 * 1. replay/backfill 会创建新的 QUEUED execution；
 * 2. 恢复窗口、来源 execution 和来源 checkpoint 会落入恢复计划表；
 * 3. 任务主状态会推进到 QUEUED，并写入 REPLAY/BACKFILL 触发方式；
 * 4. 活跃运行状态、空补数窗口和跨任务恢复来源会被 fail-closed。
 */
class SyncTaskRecoveryOperationSupportTest {

    /**
     * replay 未显式传 checkpoint 时，应选择来源 execution 最新 checkpoint。
     *
     * <p>这对应最常见的失败恢复：用户看到最近一次执行失败，点击“从最近断点回放”。
     * 服务层不需要前端额外查询 checkpoint 列表，而是自动使用该 execution 最新恢复点。
     */
    @Test
    void replayShouldUseLatestCheckpointWhenCheckpointIdAbsent() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        SyncTask task = task(SyncTaskState.FAILED, 88L);
        SyncExecution sourceExecution = execution(88L, SyncExecutionState.FAILED);
        SyncExecution queuedExecution = execution(99L, SyncExecutionState.QUEUED);
        SyncCheckpoint checkpoint = checkpoint(500L, 88L);
        SyncTaskRecoveryOperationRequest request = new SyncTaskRecoveryOperationRequest();
        request.setReason("修复目标端后从最近断点回放");

        when(fixture.executionMapper().selectById(88L)).thenReturn(sourceExecution);
        when(fixture.checkpointMapper().selectOne(any())).thenReturn(checkpoint);
        when(fixture.executionCreationSupport().createQueuedExecution(task, actor, SyncTriggerType.REPLAY))
                .thenReturn(queuedExecution);
        when(fixture.recoveryPlanMapper().insert(any(SyncExecutionRecoveryPlan.class))).thenAnswer(invocation -> {
            SyncExecutionRecoveryPlan plan = invocation.getArgument(0);
            plan.setId(7001L);
            return 1;
        });
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.REPLAY.name(), 99L))
                .thenReturn(1);

        SyncTaskOperationResult result = fixture.support().replayTask(task, request, actor);

        ArgumentCaptor<SyncExecutionRecoveryPlan> planCaptor = ArgumentCaptor.forClass(SyncExecutionRecoveryPlan.class);
        verify(fixture.recoveryPlanMapper()).insert(planCaptor.capture());
        SyncExecutionRecoveryPlan plan = planCaptor.getValue();
        assertThat(plan.getRecoveryType()).isEqualTo(SyncTriggerType.REPLAY.name());
        assertThat(plan.getExecutionId()).isEqualTo(99L);
        assertThat(plan.getSourceExecutionId()).isEqualTo(88L);
        assertThat(plan.getSourceCheckpointId()).isEqualTo(500L);
        assertThat(plan.getWindowStart()).isNull();
        assertThat(plan.getPlanState()).isEqualTo("CREATED");
        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.REPLAY.name(), 99L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(99L), eq(SyncAuditActionType.REPLAY_TASK),
                eq(actor), contains("recoveryType=REPLAY"));
        assertThat(result.state()).isEqualTo(SyncTaskState.QUEUED.name());
    }

    /**
     * backfill 必须提供至少一个补数边界。
     *
     * <p>没有窗口的 backfill 会退化成普通 run，运营台和审计员无法判断这次到底补了什么数据。
     * 因此服务层应在创建 execution 前拒绝。
     */
    @Test
    void backfillShouldRejectEmptyWindowBeforeCreatingExecution() {
        Fixture fixture = fixture();
        SyncTask task = task(SyncTaskState.CONFIGURED, null);

        assertThrows(PlatformBusinessException.class,
                () -> fixture.support().backfillTask(task, new SyncTaskRecoveryOperationRequest(), actor()));

        verify(fixture.executionCreationSupport(), never()).createQueuedExecution(any(), any(), any());
        verify(fixture.recoveryPlanMapper(), never()).insert(any());
    }

    /**
     * backfill 提供窗口后，应创建恢复计划并把任务推进到 QUEUED。
     */
    @Test
    void backfillShouldCreateQueuedExecutionAndRecoveryPlan() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        SyncTask task = task(SyncTaskState.SUCCEEDED, 88L);
        SyncExecution queuedExecution = execution(101L, SyncExecutionState.QUEUED);
        SyncTaskRecoveryOperationRequest request = new SyncTaskRecoveryOperationRequest();
        request.setWindowStart("2026-06-01");
        request.setWindowEnd("2026-06-07");
        request.setShardOrPartition("dt=2026-06");
        request.setReason("客户要求重刷六月第一周分区");

        when(fixture.executionCreationSupport().createQueuedExecution(task, actor, SyncTriggerType.BACKFILL))
                .thenReturn(queuedExecution);
        when(fixture.recoveryPlanMapper().insert(any(SyncExecutionRecoveryPlan.class))).thenAnswer(invocation -> {
            SyncExecutionRecoveryPlan plan = invocation.getArgument(0);
            plan.setId(7002L);
            return 1;
        });
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.BACKFILL.name(), 101L))
                .thenReturn(1);

        SyncTaskOperationResult result = fixture.support().backfillTask(task, request, actor);

        ArgumentCaptor<SyncExecutionRecoveryPlan> planCaptor = ArgumentCaptor.forClass(SyncExecutionRecoveryPlan.class);
        verify(fixture.recoveryPlanMapper()).insert(planCaptor.capture());
        SyncExecutionRecoveryPlan plan = planCaptor.getValue();
        assertThat(plan.getRecoveryType()).isEqualTo(SyncTriggerType.BACKFILL.name());
        assertThat(plan.getExecutionId()).isEqualTo(101L);
        assertThat(plan.getWindowStart()).isEqualTo("2026-06-01");
        assertThat(plan.getWindowEnd()).isEqualTo("2026-06-07");
        assertThat(plan.getShardOrPartition()).isEqualTo("dt=2026-06");
        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.BACKFILL.name(), 101L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(101L), eq(SyncAuditActionType.BACKFILL_TASK),
                eq(actor), contains("recoveryType=BACKFILL"));
        assertThat(result.state()).isEqualTo(SyncTaskState.QUEUED.name());
    }

    /**
     * RUNNING 任务不能直接 replay。
     *
     * <p>活跃任务仍可能写 checkpoint 或完成回调，如果此时直接派生 replay，会制造并行恢复和数据一致性风险。
     */
    @Test
    void replayShouldRejectRunningTaskBeforeCreatingExecution() {
        Fixture fixture = fixture();
        SyncTask task = task(SyncTaskState.RUNNING, 88L);

        assertThrows(PlatformBusinessException.class,
                () -> fixture.support().replayTask(task, new SyncTaskRecoveryOperationRequest(), actor()));

        verify(fixture.executionCreationSupport(), never()).createQueuedExecution(any(), any(), any());
        verify(fixture.recoveryPlanMapper(), never()).insert(any());
    }

    private Fixture fixture() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        SyncExecutionRecoveryPlanMapper recoveryPlanMapper = mock(SyncExecutionRecoveryPlanMapper.class);
        SyncExecutionCreationSupport executionCreationSupport = mock(SyncExecutionCreationSupport.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncTaskRecoveryOperationSupport support = new SyncTaskRecoveryOperationSupport(
                taskMapper,
                executionMapper,
                checkpointMapper,
                recoveryPlanMapper,
                executionCreationSupport,
                auditSupport);
        return new Fixture(support, taskMapper, executionMapper, checkpointMapper, recoveryPlanMapper,
                executionCreationSupport, auditSupport);
    }

    private SyncTask task(SyncTaskState state, Long lastExecutionId) {
        SyncTask task = new SyncTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setCurrentState(state.name());
        task.setLastExecutionId(lastExecutionId);
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
        execution.setCreateTime(LocalDateTime.now());
        return execution;
    }

    private SyncCheckpoint checkpoint(Long id, Long executionId) {
        SyncCheckpoint checkpoint = new SyncCheckpoint();
        checkpoint.setId(id);
        checkpoint.setTenantId(7L);
        checkpoint.setProjectId(101L);
        checkpoint.setWorkspaceId(301L);
        checkpoint.setSyncTaskId(1L);
        checkpoint.setExecutionId(executionId);
        checkpoint.setCheckpointTime(LocalDateTime.now());
        return checkpoint;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "PROJECT_OWNER",
                "trace-sync-recovery",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    /**
     * 测试夹具。
     *
     * <p>恢复操作依赖 mapper、execution 创建支撑和审计支撑。把它们收拢到夹具里，
     * 每个测试就能专注表达“输入状态 -> 恢复计划 -> 状态流转”的业务规则。
     */
    private record Fixture(SyncTaskRecoveryOperationSupport support,
                           SyncTaskMapper taskMapper,
                           SyncExecutionMapper executionMapper,
                           SyncCheckpointMapper checkpointMapper,
                           SyncExecutionRecoveryPlanMapper recoveryPlanMapper,
                           SyncExecutionCreationSupport executionCreationSupport,
                           SyncAuditSupport auditSupport) {
    }
}
