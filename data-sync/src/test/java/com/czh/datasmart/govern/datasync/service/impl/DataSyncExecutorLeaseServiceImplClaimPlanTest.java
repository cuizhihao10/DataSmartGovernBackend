/**
 * @Author : Cui
 * @Date: 2026/06/29 03:18
 * @Description DataSmart Govern Backend - DataSyncExecutorLeaseServiceImplClaimPlanTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.datasync.config.DataSyncExecutorProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncCallbackIdempotencySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncWorkerExecutionPlanSupport;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * claim 返回 workerPlan 的链路测试。
 *
 * <p>该测试验证的是“租约认领”和“执行计划下发”之间的集成点。
 * 对商业化同步产品来说，worker 不能只拿到一个 executionId 就开始猜测如何执行；它必须拿到服务端生成的低敏计划，
 * 明确知道当前同步模式、checkpoint 建议和阻断状态。否则后续真实 JDBC runner 很容易把执行策略散落在多个 worker 中。</p>
 */
class DataSyncExecutorLeaseServiceImplClaimPlanTest {

    @Test
    void claimNextShouldAttachWorkerExecutionPlan() {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncCallbackIdempotencySupport idempotencySupport = mock(SyncCallbackIdempotencySupport.class);
        SyncWorkerExecutionPlanSupport planSupport = mock(SyncWorkerExecutionPlanSupport.class);
        DataSyncExecutorLeaseServiceImpl service = new DataSyncExecutorLeaseServiceImpl(
                executionMapper,
                taskMapper,
                auditSupport,
                new DataSyncExecutorProperties(),
                idempotencySupport,
                planSupport);

        SyncExecution queued = execution(SyncExecutionState.QUEUED);
        SyncExecution claimed = execution(SyncExecutionState.RUNNING);
        SyncTask task = task();
        SyncWorkerExecutionPlanView plan = plan();
        when(executionMapper.selectNextClaimCandidate(7L)).thenReturn(queued);
        when(executionMapper.claimQueuedExecution(88L, "worker-1", 300L)).thenReturn(1);
        when(executionMapper.selectById(88L)).thenReturn(claimed);
        when(taskMapper.selectById(11L)).thenReturn(task);
        when(planSupport.buildPlan(claimed, task)).thenReturn(plan);

        SyncExecutionClaimResult result = service.claimNext(request(), actor());

        assertThat(result.claimed()).isTrue();
        assertThat(result.execution()).isSameAs(claimed);
        assertThat(result.task()).isSameAs(task);
        assertThat(result.workerPlan()).isSameAs(plan);
        verify(taskMapper).updateById(task);
        verify(planSupport).buildPlan(claimed, task);
        verify(executionMapper).claimQueuedExecution(88L, "worker-1", 300L);
    }

    private SyncExecutionClaimRequest request() {
        SyncExecutionClaimRequest request = new SyncExecutionClaimRequest();
        request.setTenantId(7L);
        request.setExecutorId("worker-1");
        request.setLeaseSeconds(300L);
        return request;
    }

    private SyncExecution execution(SyncExecutionState state) {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionNo(1L);
        execution.setExecutionState(state.name());
        execution.setTriggerType(SyncTriggerType.MANUAL.name());
        execution.setExecutorId(state == SyncExecutionState.RUNNING ? "worker-1" : null);
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(5));
        return execution;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(22L);
        task.setCurrentState(SyncTaskState.QUEUED.name());
        return task;
    }

    private SyncWorkerExecutionPlanView plan() {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_TO_RUN",
                7L,
                101L,
                301L,
                11L,
                88L,
                1L,
                SyncExecutionState.RUNNING.name(),
                SyncTriggerType.MANUAL.name(),
                "worker-1",
                LocalDateTime.now().plusMinutes(5),
                22L,
                10001L,
                10002L,
                "MYSQL",
                "POSTGRESQL",
                "FULL",
                true,
                true,
                "APPEND",
                false,
                false,
                false,
                true,
                "SNAPSHOT_BOUNDED",
                false,
                "SEGMENT_RETRY",
                true,
                true,
                true,
                true,
                true,
                List.of(),
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of(),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY"
        );
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "SERVICE_ACCOUNT",
                "trace-claim-plan",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }
}
