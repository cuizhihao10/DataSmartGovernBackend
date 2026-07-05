/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - DataSyncWorkerLoopServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.datasync.config.DataSyncWorkerLoopProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncExecutorLeaseService;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineRunnerDispatchResult;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineRunnerDispatchService;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * data-sync worker loop 服务测试。
 *
 * <p>测试目标不是验证 JDBC 读写，而是验证闭环编排是否可靠：
 * 1. 空队列时正常返回，不把“没有任务”当异常；
 * 2. claim 成功后能把 execution 交给 run-once dispatch；
 * 3. 模板缺失时会 fail-closed，避免 RUNNING 悬挂；
 * 4. dispatch 内部异常时也会写低敏 fail，不泄露异常消息、SQL、URL 或配置正文。</p>
 */
class DataSyncWorkerLoopServiceImplTest {

    /**
     * 队列为空是正常状态，worker loop 应返回成功摘要并标记 queueDrained。
     */
    @Test
    void runOnceShouldReturnEmptyResultWhenNoExecutionCanBeClaimed() {
        DataSyncExecutorLeaseService leaseService = mock(DataSyncExecutorLeaseService.class);
        when(leaseService.claimNext(any(SyncExecutionClaimRequest.class), any(SyncActorContext.class)))
                .thenReturn(new SyncExecutionClaimResult(false, "empty", null, null, null));
        DataSyncWorkerLoopServiceImpl service = service(leaseService, mock(SyncTemplateMapper.class),
                mock(SyncOfflineRunnerDispatchService.class), mock(SyncExecutionLifecycleSupport.class));

        SyncWorkerLoopRunResult result = service.runOnce(request(), actor());

        assertThat(result.claimAttempts()).isEqualTo(1);
        assertThat(result.claimedCount()).isZero();
        assertThat(result.queueDrained()).isTrue();
        assertThat(result.payloadPolicy()).contains("NO_SQL");
    }

    /**
     * claim 成功且 dispatch 返回 complete 时，worker loop 应聚合完成数量并继续保持低敏摘要。
     */
    @Test
    void runOnceShouldDispatchClaimedExecutionAndReportCompletedSummary() {
        DataSyncExecutorLeaseService leaseService = mock(DataSyncExecutorLeaseService.class);
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncOfflineRunnerDispatchService dispatchService = mock(SyncOfflineRunnerDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncExecution execution = execution();
        SyncTask task = task();
        SyncTemplate template = template();
        SyncWorkerExecutionPlanView plan = workerPlan();
        when(leaseService.claimNext(any(SyncExecutionClaimRequest.class), any(SyncActorContext.class)))
                .thenReturn(new SyncExecutionClaimResult(true, "claimed", execution, task, plan));
        when(templateMapper.selectById(22L)).thenReturn(template);
        when(dispatchService.dispatchOffline(eq(execution), eq(task), eq(template), eq(plan), any(SyncActorContext.class)))
                .thenReturn(new SyncOfflineRunnerDispatchResult(true, true, false,
                        "DISPATCHED_AND_COMPLETED", 88L, "SOURCE_EXHAUSTED_COMPLETE_REQUIRED",
                        "MINIMAL_BRIDGE_END_TO_END_SUPPORTED", List.of(),
                        SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY));
        DataSyncWorkerLoopServiceImpl service = service(leaseService, templateMapper, dispatchService, lifecycleSupport);

        SyncWorkerLoopRunResult result = service.runOnce(request(), actor());

        assertThat(result.claimedCount()).isEqualTo(1);
        assertThat(result.dispatchedCount()).isEqualTo(1);
        assertThat(result.completedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(result.executions()).singleElement().satisfies(item -> {
            assertThat(item.executionId()).isEqualTo(88L);
            assertThat(item.outcome()).isEqualTo("COMPLETED");
            assertThat(item.issueCodes()).isEmpty();
        });
        verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());

        ArgumentCaptor<SyncExecutionClaimRequest> claimCaptor = ArgumentCaptor.forClass(SyncExecutionClaimRequest.class);
        verify(leaseService).claimNext(claimCaptor.capture(), any(SyncActorContext.class));
        assertThat(claimCaptor.getValue().getExecutorId()).isEqualTo("worker-loop-test");
        assertThat(claimCaptor.getValue().getTenantId()).isEqualTo(7L);
        assertThat(claimCaptor.getValue().getLeaseSeconds()).isEqualTo(120L);
    }

    /**
     * 模板缺失属于结构性配置问题；worker loop 应立即 fail-closed，不能等待租约过期才恢复。
     */
    @Test
    void runOnceShouldFailClosedWhenClaimedTaskTemplateIsMissing() {
        DataSyncExecutorLeaseService leaseService = mock(DataSyncExecutorLeaseService.class);
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncOfflineRunnerDispatchService dispatchService = mock(SyncOfflineRunnerDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncExecution execution = execution();
        SyncTask task = task();
        when(leaseService.claimNext(any(SyncExecutionClaimRequest.class), any(SyncActorContext.class)))
                .thenReturn(new SyncExecutionClaimResult(true, "claimed", execution, task, workerPlan()));
        when(templateMapper.selectById(22L)).thenReturn(null);
        DataSyncWorkerLoopServiceImpl service = service(leaseService, templateMapper, dispatchService, lifecycleSupport);

        SyncWorkerLoopRunResult result = service.runOnce(request(), actor());

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.issueCodes()).contains("SYNC_TEMPLATE_NOT_FOUND");
        verify(dispatchService, never()).dispatchOffline(any(), any(), any(), any(), any());
        assertFailRequest(lifecycleSupport, "SYNC_TEMPLATE_NOT_FOUND");
    }

    /**
     * dispatch 抛出异常时，worker loop 也应低敏 fail-closed，不把异常正文传给调用方。
     */
    @Test
    void runOnceShouldFailClosedWhenDispatchThrowsException() {
        DataSyncExecutorLeaseService leaseService = mock(DataSyncExecutorLeaseService.class);
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncOfflineRunnerDispatchService dispatchService = mock(SyncOfflineRunnerDispatchService.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncExecution execution = execution();
        SyncTask task = task();
        SyncTemplate template = template();
        SyncWorkerExecutionPlanView plan = workerPlan();
        when(leaseService.claimNext(any(SyncExecutionClaimRequest.class), any(SyncActorContext.class)))
                .thenReturn(new SyncExecutionClaimResult(true, "claimed", execution, task, plan));
        when(templateMapper.selectById(22L)).thenReturn(template);
        when(dispatchService.dispatchOffline(eq(execution), eq(task), eq(template), eq(plan), any(SyncActorContext.class)))
                .thenThrow(new IllegalStateException("jdbc://should-not-leak"));
        DataSyncWorkerLoopServiceImpl service = service(leaseService, templateMapper, dispatchService, lifecycleSupport);

        SyncWorkerLoopRunResult result = service.runOnce(request(), actor());

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.issueCodes()).contains("WORKER_LOOP_DISPATCH_EXCEPTION");
        assertThat(result.toString()).doesNotContain("jdbc://should-not-leak");
        assertFailRequest(lifecycleSupport, "WORKER_LOOP_DISPATCH_EXCEPTION");
    }

    private DataSyncWorkerLoopServiceImpl service(DataSyncExecutorLeaseService leaseService,
                                                  SyncTemplateMapper templateMapper,
                                                  SyncOfflineRunnerDispatchService dispatchService,
                                                  SyncExecutionLifecycleSupport lifecycleSupport) {
        DataSyncWorkerLoopProperties properties = new DataSyncWorkerLoopProperties();
        properties.setExecutorId("worker-loop-test");
        properties.setTenantId(7L);
        return new DataSyncWorkerLoopServiceImpl(leaseService, templateMapper, dispatchService, lifecycleSupport, properties);
    }

    private void assertFailRequest(SyncExecutionLifecycleSupport lifecycleSupport, String expectedCode) {
        ArgumentCaptor<SyncExecutionFailRequest> failCaptor = ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(lifecycleSupport).failExecution(any(SyncTask.class), any(SyncExecution.class),
                failCaptor.capture(), any(SyncActorContext.class));
        assertThat(failCaptor.getValue().getErrorCode()).isEqualTo(expectedCode);
        assertThat(failCaptor.getValue().getSamplePayload()).isNull();
        assertThat(failCaptor.getValue().getSourceRecordKey()).isNull();
        assertThat(failCaptor.getValue().getTargetRecordKey()).isNull();
        assertThat(failCaptor.getValue().getErrorMessage()).doesNotContain("jdbc://");
    }

    private SyncWorkerLoopRunRequest request() {
        SyncWorkerLoopRunRequest request = new SyncWorkerLoopRunRequest();
        request.setMaxExecutions(1);
        request.setLeaseSeconds(120L);
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 0L, "SERVICE_ACCOUNT", "trace-worker-loop-test");
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionNo(3L);
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setTriggerType(SyncTriggerType.MANUAL.name());
        execution.setExecutorId("worker-loop-test");
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(2));
        execution.setTriggeredBy(0L);
        return execution;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(22L);
        task.setCurrentState("RUNNING");
        return task;
    }

    private SyncTemplate template() {
        SyncTemplate template = new SyncTemplate();
        template.setId(22L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(10002L);
        template.setSyncMode("FULL");
        template.setEnabled(true);
        return template;
    }

    private SyncWorkerExecutionPlanView workerPlan() {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_TO_RUN",
                7L,
                101L,
                301L,
                11L,
                88L,
                3L,
                SyncExecutionState.RUNNING.name(),
                SyncTriggerType.MANUAL.name(),
                "worker-loop-test",
                LocalDateTime.now().plusMinutes(2),
                22L,
                10001L,
                10002L,
                "MYSQL",
                "POSTGRESQL",
                "FULL",
                "OFFLINE",
                "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER",
                "SINGLE_OBJECT",
                true,
                false,
                false,
                1,
                false,
                true,
                true,
                true,
                "APPEND",
                false,
                true,
                false,
                true,
                "SNAPSHOT_BOUNDED",
                false,
                "SEGMENT_RETRY",
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of(),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY");
    }
}
