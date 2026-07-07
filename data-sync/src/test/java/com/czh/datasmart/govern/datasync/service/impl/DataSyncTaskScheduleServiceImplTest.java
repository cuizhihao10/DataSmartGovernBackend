/**
 * @Author : Cui
 * @Date: 2026/07/07 23:18
 * @Description DataSmart Govern Backend - DataSyncTaskScheduleServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.datasync.config.DataSyncTaskSchedulerProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskScheduleConfigSupport;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时同步任务调度服务测试。
 *
 * <p>这组测试不启动 Spring，也不连接真实数据库。它固定的是控制面业务规则：
 * 到期任务会生成 SCHEDULED execution；默认不允许并发；misfire/concurrency 不会绕过调度游标原子抢占。
 * 真实 MySQL -> PostgreSQL 数据搬运仍由已有 worker-loop E2E 覆盖。</p>
 */
class DataSyncTaskScheduleServiceImplTest {

    @Test
    void dispatchDueTasksShouldCreateScheduledExecutionWhenTaskIsDue() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionCreationSupport creationSupport = mock(SyncExecutionCreationSupport.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncTaskScheduleServiceImpl service = service(taskMapper, executionMapper, creationSupport, auditSupport);

        SyncTask task = dueFixedRateTask();
        when(taskMapper.selectDueScheduledTasks(eq(7L), any(LocalDateTime.class), eq(20))).thenReturn(List.of(task));
        when(executionMapper.countActiveExecutionsForTask(task.getId())).thenReturn(0L);
        when(taskMapper.advanceScheduledTaskAfterDispatch(
                eq(task.getId()), eq(0L), any(LocalDateTime.class), any(LocalDateTime.class),
                any(LocalDateTime.class), eq(null), eq(1L), anyLong())).thenReturn(1);
        SyncExecution execution = new SyncExecution();
        execution.setId(9001L);
        when(creationSupport.createQueuedExecution(eq(task), any(SyncActorContext.class), any())).thenReturn(execution);

        SyncTaskScheduleDispatchResult result = service.dispatchDueTasks(request(), actorContext());

        assertThat(result.scannedTaskCount()).isEqualTo(1);
        assertThat(result.dispatchedTaskCount()).isEqualTo(1);
        assertThat(result.createdExecutionCount()).isEqualTo(1);
        assertThat(result.executionIds()).containsExactly(9001L);
        verify(taskMapper).markScheduledExecutionCreated(task.getId(), 9001L);
        verify(auditSupport).saveAudit(any(), eq(task.getId()), eq(9001L), any(), any(), any());
    }

    @Test
    void dispatchDueTasksShouldNotCreateNewExecutionWhenConcurrentRunIsActive() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionCreationSupport creationSupport = mock(SyncExecutionCreationSupport.class);
        DataSyncTaskScheduleServiceImpl service = service(taskMapper, executionMapper, creationSupport, mock(SyncAuditSupport.class));

        SyncTask task = dueFixedRateTask();
        when(taskMapper.selectDueScheduledTasks(eq(7L), any(LocalDateTime.class), eq(20))).thenReturn(List.of(task));
        when(executionMapper.countActiveExecutionsForTask(task.getId())).thenReturn(1L);

        SyncTaskScheduleDispatchResult result = service.dispatchDueTasks(request(), actorContext());

        assertThat(result.scannedTaskCount()).isEqualTo(1);
        assertThat(result.createdExecutionCount()).isZero();
        assertThat(result.skippedByConcurrencyCount()).isEqualTo(1);
        assertThat(result.issueCodes()).contains("SCHEDULE_CONCURRENT_RUN_BLOCKED");
        verify(creationSupport, never()).createQueuedExecution(any(), any(), any());
        verify(taskMapper, never()).advanceScheduledTaskAfterDispatch(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void dispatchDueTasksShouldAdvanceCursorWithoutExecutionWhenMisfirePolicyIsSkip() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionCreationSupport creationSupport = mock(SyncExecutionCreationSupport.class);
        DataSyncTaskScheduleServiceImpl service = service(taskMapper, executionMapper, creationSupport, mock(SyncAuditSupport.class));

        SyncTask task = dueSkipTask();
        when(taskMapper.selectDueScheduledTasks(eq(7L), any(LocalDateTime.class), eq(20))).thenReturn(List.of(task));
        when(executionMapper.countActiveExecutionsForTask(task.getId())).thenReturn(0L);
        when(taskMapper.advanceScheduledTaskAfterDispatch(
                eq(task.getId()), eq(0L), any(LocalDateTime.class), eq(null),
                any(LocalDateTime.class), eq(null), eq(0L), anyLong())).thenReturn(1);

        SyncTaskScheduleDispatchResult result = service.dispatchDueTasks(request(), actorContext());

        assertThat(result.createdExecutionCount()).isZero();
        assertThat(result.skippedByMisfirePolicyCount()).isEqualTo(1);
        assertThat(result.issueCodes()).contains("SCHEDULE_MISFIRE_SKIPPED");
        verify(creationSupport, never()).createQueuedExecution(any(), any(), any());
    }

    private DataSyncTaskScheduleServiceImpl service(SyncTaskMapper taskMapper,
                                                    SyncExecutionMapper executionMapper,
                                                    SyncExecutionCreationSupport creationSupport,
                                                    SyncAuditSupport auditSupport) {
        DataSyncTaskSchedulerProperties properties = new DataSyncTaskSchedulerProperties();
        properties.setTenantId(7L);
        properties.setBatchSize(20);
        properties.setMaxCatchUpRunsPerTask(3);
        return new DataSyncTaskScheduleServiceImpl(
                taskMapper,
                executionMapper,
                creationSupport,
                new SyncTaskScheduleConfigSupport(new ObjectMapper()),
                auditSupport,
                properties
        );
    }

    private SyncTaskScheduleDispatchRequest request() {
        SyncTaskScheduleDispatchRequest request = new SyncTaskScheduleDispatchRequest();
        request.setTenantId(7L);
        request.setLimit(20);
        return request;
    }

    private SyncActorContext actorContext() {
        return new SyncActorContext(7L, 0L, "SERVICE_ACCOUNT", "trace-scheduler-test");
    }

    private SyncTask dueFixedRateTask() {
        SyncTask task = baseDueTask();
        task.setScheduleConfig("""
                {"type":"FIXED_RATE","intervalSeconds":60,"misfirePolicy":"FIRE_ONCE","allowConcurrentRuns":false}
                """);
        task.setNextFireTime(LocalDateTime.now().minusMinutes(5));
        return task;
    }

    private SyncTask dueSkipTask() {
        SyncTask task = baseDueTask();
        task.setScheduleConfig("""
                {"type":"FIXED_RATE","intervalSeconds":60,"misfirePolicy":"SKIP","allowConcurrentRuns":false}
                """);
        task.setNextFireTime(LocalDateTime.now().minusMinutes(5));
        return task;
    }

    private SyncTask baseDueTask() {
        SyncTask task = new SyncTask();
        task.setId(7001L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(6001L);
        task.setName("scheduled full sync");
        task.setCurrentState(SyncTaskState.SCHEDULED.name());
        task.setScheduleEnabled(true);
        task.setScheduleVersion(0L);
        task.setScheduleDispatchCount(0L);
        task.setScheduleMisfireCount(0);
        return task;
    }
}
