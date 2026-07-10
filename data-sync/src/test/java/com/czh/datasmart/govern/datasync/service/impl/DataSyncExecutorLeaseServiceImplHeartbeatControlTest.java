/**
 * @Author : Cui
 * @Date: 2026/06/27 02:10
 * @Description DataSmart Govern Backend - DataSyncExecutorLeaseServiceImplHeartbeatControlTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.config.DataSyncExecutorProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionHeartbeatRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionHeartbeatResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncCallbackIdempotencySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLogSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncWorkerExecutionPlanSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行器心跳控制信号测试。
 *
 * <p>这组测试专门覆盖 heartbeat 与 pause/cancel 控制面的交界处。
 * 之所以单独建测试类，是因为这里不是普通“字段更新”逻辑，而是 data-sync 生产闭环里的关键协议：
 * 控制台把任务暂停或取消后，worker 必须通过下一次心跳明确感知停止指令。
 *
 * <p>如果这层协议缺失，系统会出现两个商业化产品中很严重的问题：
 * 1. 用户点击暂停后，后端状态已经显示 PAUSED，但 worker 仍继续读写数据；
 * 2. 用户点击取消后，worker 把数据库更新冲突误判为临时失败，继续重试或写入错误结果。
 */
class DataSyncExecutorLeaseServiceImplHeartbeatControlTest {

    /**
     * RUNNING execution 的正常心跳应续租，并返回 CONTINUE。
     *
     * <p>该用例验证未触发控制信号时的原有主路径不会被破坏。
     * 返回 DTO 中只包含低敏计数、状态和租约时间，worker 不再依赖完整数据库实体。
     */
    @Test
    void heartbeatShouldExtendLeaseAndReturnContinueForRunningExecution() {
        Fixture fixture = fixture();
        SyncExecution running = execution(SyncExecutionState.RUNNING, "worker-1");
        SyncExecution refreshed = execution(SyncExecutionState.RUNNING, "worker-1");
        refreshed.setRecordsRead(120L);
        refreshed.setRecordsWritten(118L);
        refreshed.setHeartbeatTime(LocalDateTime.now());
        refreshed.setLeaseExpireTime(LocalDateTime.now().plusSeconds(300));
        when(fixture.executionMapper().selectById(88L)).thenReturn(running, refreshed);
        when(fixture.executionMapper().heartbeatLease(88L, "worker-1", 120L, 118L, 300L)).thenReturn(1);

        SyncExecutionHeartbeatResult result = fixture.service().heartbeat(88L, request("worker-1", 120L, 118L, 300L), actor());

        assertThat(result.controlAction()).isEqualTo("CONTINUE");
        assertThat(result.shouldContinue()).isTrue();
        assertThat(result.leaseExtended()).isTrue();
        assertThat(result.recordsRead()).isEqualTo(120L);
        assertThat(result.recordsWritten()).isEqualTo(118L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.RUN_TASK),
                eq(actor()), contains("heartbeat"));
        verify(fixture.idempotencySupport()).markSucceeded(eq(7L), eq("HEARTBEAT"), eq("1:88"), eq("heartbeat-key"),
                contains("leaseExpireTime"));
    }

    /**
     * PAUSED execution 的心跳应返回 STOP_FOR_PAUSE，并且不能续租。
     *
     * <p>暂停是“协作式停止”，服务端不杀 worker，而是让 worker 在下一次心跳时收到明确控制动作。
     * 这里特别验证 heartbeatLease 没有被调用，避免暂停状态被心跳误续租回运行窗口。
     */
    @Test
    void heartbeatShouldReturnStopForPauseWithoutExtendingLease() {
        Fixture fixture = fixture();
        SyncExecution paused = execution(SyncExecutionState.PAUSED, "worker-1");
        when(fixture.executionMapper().selectById(88L)).thenReturn(paused);

        SyncExecutionHeartbeatResult result = fixture.service().heartbeat(88L, request("worker-1", 120L, 118L, 300L), actor());

        assertThat(result.controlAction()).isEqualTo("STOP_FOR_PAUSE");
        assertThat(result.shouldContinue()).isFalse();
        assertThat(result.leaseExtended()).isFalse();
        verify(fixture.executionMapper(), never()).heartbeatLease(eq(88L), eq("worker-1"), eq(120L), eq(118L), eq(300L));
        verify(fixture.idempotencySupport(), never()).isDuplicate(eq(7L), eq(1L), eq(88L), eq("HEARTBEAT"),
                eq("1:88"), eq("heartbeat-key"), eq("worker-1"), contains("heartbeat"));
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.RUN_TASK),
                eq(actor()), contains("STOP_FOR_PAUSE"));
    }

    /**
     * CANCELLED execution 的心跳应返回 STOP_FOR_CANCEL，并且不能续租。
     *
     * <p>取消代表业务意图已经终止，worker 收到该响应后不应继续写 checkpoint、complete 或 fail。
     */
    @Test
    void heartbeatShouldReturnStopForCancelWithoutExtendingLease() {
        Fixture fixture = fixture();
        SyncExecution cancelled = execution(SyncExecutionState.CANCELLED, "worker-1");
        when(fixture.executionMapper().selectById(88L)).thenReturn(cancelled);

        SyncExecutionHeartbeatResult result = fixture.service().heartbeat(88L, request("worker-1", 120L, 118L, 300L), actor());

        assertThat(result.controlAction()).isEqualTo("STOP_FOR_CANCEL");
        assertThat(result.shouldContinue()).isFalse();
        assertThat(result.leaseExtended()).isFalse();
        verify(fixture.executionMapper(), never()).heartbeatLease(eq(88L), eq("worker-1"), eq(120L), eq(118L), eq(300L));
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.RUN_TASK),
                eq(actor()), contains("STOP_FOR_CANCEL"));
    }

    /**
     * 心跳续租 UPDATE 失败后，如果最新状态已经变成 CANCELLED，应返回停止指令。
     *
     * <p>该用例模拟真实并发竞态：
     * 1. worker 心跳开始时读到 RUNNING；
     * 2. 控制台几乎同时取消任务，把 execution 改成 CANCELLED；
     * 3. heartbeatLease 因 WHERE execution_state='RUNNING' 不再命中；
     * 4. 服务端重新读取最新状态，并返回 STOP_FOR_CANCEL。
     */
    @Test
    void heartbeatShouldReturnStopForCancelWhenLeaseUpdateLosesRace() {
        Fixture fixture = fixture();
        SyncExecution running = execution(SyncExecutionState.RUNNING, "worker-1");
        SyncExecution cancelled = execution(SyncExecutionState.CANCELLED, "worker-1");
        when(fixture.executionMapper().selectById(88L)).thenReturn(running, cancelled);
        when(fixture.executionMapper().heartbeatLease(88L, "worker-1", 120L, 118L, 300L)).thenReturn(0);

        SyncExecutionHeartbeatResult result = fixture.service().heartbeat(88L, request("worker-1", 120L, 118L, 300L), actor());

        assertThat(result.controlAction()).isEqualTo("STOP_FOR_CANCEL");
        assertThat(result.shouldContinue()).isFalse();
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.RUN_TASK),
                eq(actor()), contains("STOP_FOR_CANCEL"));
    }

    /**
     * 非租约持有者不能读取 PAUSED/CANCELLED 控制信号。
     *
     * <p>如果错误 worker 可以通过 heartbeat 探测暂停/取消状态，就会形成 executionId 枚举风险。
     * 因此即使响应只是低敏控制动作，也必须先校验 executorId。
     */
    @Test
    void heartbeatShouldRejectControlSignalWhenExecutorDoesNotMatch() {
        Fixture fixture = fixture();
        SyncExecution paused = execution(SyncExecutionState.PAUSED, "worker-owner");
        when(fixture.executionMapper().selectById(88L)).thenReturn(paused);

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service().heartbeat(88L, request("worker-other", 120L, 118L, 300L), actor()));

        verify(fixture.executionMapper(), never()).heartbeatLease(eq(88L), eq("worker-other"), eq(120L), eq(118L), eq(300L));
        verify(fixture.auditSupport(), never()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.RUN_TASK),
                eq(actor()), contains("STOP_FOR_PAUSE"));
    }

    private Fixture fixture() {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncCallbackIdempotencySupport idempotencySupport = mock(SyncCallbackIdempotencySupport.class);
        DataSyncExecutorLeaseServiceImpl service = new DataSyncExecutorLeaseServiceImpl(
                executionMapper,
                taskMapper,
                auditSupport,
                new DataSyncExecutorProperties(),
                idempotencySupport,
                mock(SyncWorkerExecutionPlanSupport.class),
                mock(SyncExecutionLogSupport.class));
        SyncTask task = new SyncTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        when(taskMapper.selectById(1L)).thenReturn(task);
        return new Fixture(service, executionMapper, auditSupport, idempotencySupport);
    }

    private SyncExecution execution(SyncExecutionState state, String executorId) {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(1L);
        execution.setExecutionState(state.name());
        execution.setExecutorId(executorId);
        execution.setRecordsRead(10L);
        execution.setRecordsWritten(9L);
        return execution;
    }

    private SyncExecutionHeartbeatRequest request(String executorId, Long recordsRead, Long recordsWritten, Long leaseSeconds) {
        SyncExecutionHeartbeatRequest request = new SyncExecutionHeartbeatRequest();
        request.setExecutorId(executorId);
        request.setRecordsRead(recordsRead);
        request.setRecordsWritten(recordsWritten);
        request.setLeaseSeconds(leaseSeconds);
        request.setIdempotencyKey("heartbeat-key");
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "SERVICE_ACCOUNT",
                "trace-heartbeat-control",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    /**
     * 测试夹具，把 service 和心跳路径会观察的依赖放在一起。
     */
    private record Fixture(DataSyncExecutorLeaseServiceImpl service,
                           SyncExecutionMapper executionMapper,
                           SyncAuditSupport auditSupport,
                           SyncCallbackIdempotencySupport idempotencySupport) {
    }
}
