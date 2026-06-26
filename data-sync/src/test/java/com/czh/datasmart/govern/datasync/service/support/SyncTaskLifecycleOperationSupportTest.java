/**
 * @Author : Cui
 * @Date: 2026/06/27 16:45
 * @Description DataSmart Govern Backend - SyncTaskLifecycleOperationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
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
 * 同步任务生命周期操作支撑组件测试。
 *
 * <p>这组测试直接验证 support，而不是只测 Controller：
 * 1. Controller 只负责 HTTP 入参和 actorContext 组装，生命周期规则不应该堆在 Controller；
 * 2. DataSyncServiceImpl 只做数据范围校验和委托，状态流转细节已经拆到 support；
 * 3. support 承担真实业务规则，因此这里重点覆盖任务状态、execution 状态、审计动作和人工介入边界。
 */
class SyncTaskLifecycleOperationSupportTest {

    /**
     * 暂停运行中任务时，应同时把最近 execution 标记为 PAUSED，并把任务主状态标记为 PAUSED。
     *
     * <p>这验证的是“协作式暂停”控制信号：服务端不会假装杀死 worker，
     * 但会让后续 worker 回调因为 execution 状态不再是 RUNNING/RETRYING 而被拒绝。
     */
    @Test
    void pauseRunningTaskShouldMarkTaskAndExecutionPaused() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        SyncTask task = task(SyncTaskState.RUNNING, 88L);
        SyncExecution execution = execution(88L, SyncExecutionState.RUNNING);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.executionMapper().updateById(any(SyncExecution.class))).thenReturn(1);
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.PAUSED.name(), null, 88L)).thenReturn(1);

        SyncTaskOperationResult result = fixture.support().pauseTask(task, request("下游维护窗口暂停"), actor);

        ArgumentCaptor<SyncExecution> executionCaptor = ArgumentCaptor.forClass(SyncExecution.class);
        verify(fixture.executionMapper()).updateById(executionCaptor.capture());
        assertThat(executionCaptor.getValue().getExecutionState()).isEqualTo(SyncExecutionState.PAUSED.name());
        assertThat(executionCaptor.getValue().getErrorSummary()).isEqualTo("下游维护窗口暂停");
        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.PAUSED.name(), null, 88L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.PAUSE_TASK),
                eq(actor), contains("action=pause"));
        assertThat(result.state()).isEqualTo(SyncTaskState.PAUSED.name());
    }

    /**
     * 恢复暂停任务时，应创建新的 QUEUED execution，并把任务主状态回到 QUEUED。
     *
     * <p>旧 execution 不被复活，是为了保留暂停历史；新 execution 继续走已有租约协议。
     */
    @Test
    void resumePausedTaskShouldCreateQueuedExecutionAndMarkTaskQueued() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        SyncTask task = task(SyncTaskState.PAUSED, 88L);
        SyncExecution queuedExecution = execution(99L, SyncExecutionState.QUEUED);
        when(fixture.executionCreationSupport().createQueuedExecution(task, actor)).thenReturn(queuedExecution);
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.MANUAL.name(), 99L))
                .thenReturn(1);

        SyncTaskOperationResult result = fixture.support().resumeTask(task, request("维护完成，恢复执行"), actor);

        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.QUEUED.name(), SyncTriggerType.MANUAL.name(), 99L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(99L), eq(SyncAuditActionType.RESUME_TASK),
                eq(actor), contains("action=resume"));
        assertThat(result.state()).isEqualTo(SyncTaskState.QUEUED.name());
    }

    /**
     * 重试失败任务时，应创建新的 execution，并把任务主状态标记为 RETRYING。
     *
     * <p>execution 仍是 QUEUED，是因为 worker 队列只认 execution_state；
     * task 主状态使用 RETRYING，则能让运营台看出这次排队来源是失败后的重试。
     */
    @Test
    void retryFailedTaskShouldCreateQueuedExecutionAndMarkTaskRetrying() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        SyncTask task = task(SyncTaskState.FAILED, 88L);
        SyncExecution queuedExecution = execution(100L, SyncExecutionState.QUEUED);
        when(fixture.executionCreationSupport().createQueuedExecution(task, actor)).thenReturn(queuedExecution);
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.RETRYING.name(), SyncTriggerType.MANUAL.name(), 100L))
                .thenReturn(1);

        SyncTaskOperationResult result = fixture.support().retryTask(task, request("临时网络故障已恢复"), actor);

        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.RETRYING.name(), SyncTriggerType.MANUAL.name(), 100L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(100L), eq(SyncAuditActionType.RETRY_TASK),
                eq(actor), contains("action=retry"));
        assertThat(result.state()).isEqualTo(SyncTaskState.RETRYING.name());
    }

    /**
     * 取消运行中任务时，应把最近 execution 推进到 CANCELLED 并记录完成时间。
     */
    @Test
    void cancelRunningTaskShouldMarkExecutionAndTaskCancelled() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        SyncTask task = task(SyncTaskState.RUNNING, 88L);
        SyncExecution execution = execution(88L, SyncExecutionState.RUNNING);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution);
        when(fixture.executionMapper().updateById(any(SyncExecution.class))).thenReturn(1);
        when(fixture.taskMapper().markLifecycleState(1L, SyncTaskState.CANCELLED.name(), null, 88L)).thenReturn(1);

        SyncTaskOperationResult result = fixture.support().cancelTask(task, request("需求撤销"), actor);

        ArgumentCaptor<SyncExecution> executionCaptor = ArgumentCaptor.forClass(SyncExecution.class);
        verify(fixture.executionMapper()).updateById(executionCaptor.capture());
        assertThat(executionCaptor.getValue().getExecutionState()).isEqualTo(SyncExecutionState.CANCELLED.name());
        assertThat(executionCaptor.getValue().getFinishedAt()).isNotNull();
        verify(fixture.taskMapper()).markLifecycleState(1L, SyncTaskState.CANCELLED.name(), null, 88L);
        verify(fixture.auditSupport()).saveAudit(eq(7L), eq(1L), eq(88L), eq(SyncAuditActionType.CANCEL_TASK),
                eq(actor), contains("action=cancel"));
        assertThat(result.state()).isEqualTo(SyncTaskState.CANCELLED.name());
    }

    /**
     * AWAITING_OPERATOR_ACTION 不能走普通 retry。
     *
     * <p>这个场景是商业化运维闭环的重要边界：
     * 如果任务已经被系统判定需要人工介入，普通用户不能通过 retry 按钮绕过运营确认和事故处理。
     */
    @Test
    void retryAwaitingOperatorActionShouldBeRejectedBeforeCreatingExecution() {
        Fixture fixture = fixture();
        SyncTask task = task(SyncTaskState.AWAITING_OPERATOR_ACTION, 88L);

        assertThrows(PlatformBusinessException.class,
                () -> fixture.support().retryTask(task, request("直接再试一次"), actor()));

        verify(fixture.executionCreationSupport(), never()).createQueuedExecution(any(), any());
    }

    private Fixture fixture() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionCreationSupport executionCreationSupport = mock(SyncExecutionCreationSupport.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncTaskLifecycleOperationSupport support = new SyncTaskLifecycleOperationSupport(
                taskMapper,
                executionMapper,
                new SyncTaskStateMachineSupport(),
                executionCreationSupport,
                auditSupport);
        return new Fixture(support, taskMapper, executionMapper, executionCreationSupport, auditSupport);
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
        return execution;
    }

    private SyncTaskLifecycleOperationRequest request(String reason) {
        SyncTaskLifecycleOperationRequest request = new SyncTaskLifecycleOperationRequest();
        request.setReason(reason);
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                7L,
                1001L,
                "PROJECT_OWNER",
                "trace-sync-lifecycle",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    /**
     * 测试夹具。
     *
     * <p>把 support 和它依赖的 mapper/support mock 放在一起，可以让每个用例只关注业务动作，
     * 不需要在测试方法里重复构造样板对象。
     */
    private record Fixture(SyncTaskLifecycleOperationSupport support,
                           SyncTaskMapper taskMapper,
                           SyncExecutionMapper executionMapper,
                           SyncExecutionCreationSupport executionCreationSupport,
                           SyncAuditSupport auditSupport) {
    }
}
