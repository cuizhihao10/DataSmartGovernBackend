/**
 * @Author : Cui
 * @Date: 2026/07/05 15:00
 * @Description DataSmart Govern Backend - SyncOfflineRunnerReportCallbackServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 专用离线 Runner 低敏报告回调测试。
 *
 * <p>这组测试验证“专用 Runner 回传报告”不会重新发明一套状态机：</p>
 * <p>1. 进度报告只更新低敏计数和心跳；</p>
 * <p>2. checkpoint 报告只用 checkpointRef/digest 生成不可还原的低敏 checkpointValue；</p>
 * <p>3. 成功和失败终态继续委托已有 lifecycle，并发布 task-management 低敏 receipt；</p>
 * <p>4. 任何路径都不需要 SQL、对象名、字段名、连接串或原始 checkpoint。</p>
 */
class SyncOfflineRunnerReportCallbackServiceTest {

    @Test
    void progressReportShouldUpdateLowSensitiveCountersOnly() {
        Fixture fixture = fixture();
        SyncOfflineRunnerReportRequest request = baseRequest("RUNNING");
        request.setRecordsRead(1000L);
        request.setRecordsWritten(980L);
        request.setFailedRecordCount(2L);
        request.setCheckpointDigest("sha256-progress");
        request.setIssueCodes(List.of("RUNNER_PROGRESS_ACCEPTED"));

        SyncOfflineRunnerReportResult result =
                fixture.service().applyReport(11L, 88L, request, actor());

        assertThat(result.actionApplied()).isEqualTo("PROGRESS_ACCEPTED");
        assertThat(result.executionState()).isEqualTo("RUNNING");
        ArgumentCaptor<SyncExecution> executionCaptor = ArgumentCaptor.forClass(SyncExecution.class);
        verify(fixture.executionMapper()).updateById(executionCaptor.capture());
        assertThat(executionCaptor.getValue().getRecordsRead()).isEqualTo(1000L);
        assertThat(executionCaptor.getValue().getRecordsWritten()).isEqualTo(980L);
        assertThat(executionCaptor.getValue().getFailedRecordCount()).isEqualTo(2L);
        assertThat(executionCaptor.getValue().getCheckpointRef()).isEqualTo("digest:sha256-progress");
        verify(fixture.lifecycleSupport(), never()).completeExecution(any(), any(), any(), any());
        verify(fixture.lifecycleSupport(), never()).failExecution(any(), any(), any(), any());
        verify(fixture.reportMetrics()).recordReport(any(SyncOfflineRunnerReportRequest.class),
                any(SyncOfflineRunnerReportResult.class));
    }

    @Test
    void checkpointReportShouldWriteDigestCheckpointThroughLifecycle() {
        Fixture fixture = fixture();
        SyncOfflineRunnerReportRequest request = baseRequest("CHECKPOINT");
        request.setCheckpointType("SCHEDULED_WINDOW");
        request.setCheckpointRef("runner-checkpoint-001");
        request.setCheckpointDigest("sha256-window");
        request.setShardOrPartition("runner-shard-01");
        request.setRecordsRead(2000L);
        request.setRecordsWritten(1980L);
        when(fixture.executionMapper().selectById(88L)).thenReturn(execution());

        SyncOfflineRunnerReportResult result =
                fixture.service().applyReport(11L, 88L, request, actor());

        assertThat(result.actionApplied()).isEqualTo("CHECKPOINT_WRITTEN");
        ArgumentCaptor<SyncExecutionCheckpointRequest> checkpointCaptor =
                ArgumentCaptor.forClass(SyncExecutionCheckpointRequest.class);
        verify(fixture.lifecycleSupport()).writeCheckpoint(any(SyncTask.class), any(SyncExecution.class),
                checkpointCaptor.capture(), any(SyncActorContext.class));
        assertThat(checkpointCaptor.getValue().getCheckpointType()).isEqualTo("SCHEDULED_WINDOW");
        assertThat(checkpointCaptor.getValue().getCheckpointValue())
                .isEqualTo("checkpointRef=runner-checkpoint-001;checkpointDigest=sha256-window");
        assertThat(checkpointCaptor.getValue().getShardOrPartition()).isEqualTo("runner-shard-01");
        verify(fixture.reportMetrics()).recordReport(any(SyncOfflineRunnerReportRequest.class),
                any(SyncOfflineRunnerReportResult.class));
    }

    @Test
    void succeededReportShouldDelegateCompleteAndPublishReceipt() {
        Fixture fixture = fixture();
        SyncOfflineRunnerReportRequest request = baseRequest("SUCCEEDED");
        request.setRecordsRead(3000L);
        request.setRecordsWritten(3000L);
        request.setCheckpointDigest("sha256-final");
        SyncExecution completed = execution();
        completed.setExecutionState(SyncExecutionState.SUCCEEDED.name());
        completed.setCheckpointRef("digest:sha256-final");
        when(fixture.executionMapper().selectById(88L)).thenReturn(completed);

        SyncOfflineRunnerReportResult result =
                fixture.service().applyReport(11L, 88L, request, actor());

        assertThat(result.actionApplied()).isEqualTo("EXECUTION_COMPLETED");
        assertThat(result.executionState()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
        verify(fixture.lifecycleSupport()).completeExecution(any(SyncTask.class), any(SyncExecution.class),
                completeCaptor.capture(), any(SyncActorContext.class));
        assertThat(completeCaptor.getValue().getCheckpointRef()).isEqualTo("digest:sha256-final");
        verify(fixture.receiptPublisher()).publishComplete(any(SyncTask.class), any(SyncExecution.class),
                any(SyncActorContext.class), any());
        verify(fixture.reportMetrics()).recordReport(any(SyncOfflineRunnerReportRequest.class),
                any(SyncOfflineRunnerReportResult.class));
    }

    @Test
    void failedReportShouldDelegateFailWithoutSamplePayload() {
        Fixture fixture = fixture();
        SyncOfflineRunnerReportRequest request = baseRequest("FAILED");
        request.setErrorType("RUNNER_TIMEOUT");
        request.setErrorCode("DATAX_RUNNER_TIMEOUT");
        request.setErrorMessage("runner timeout digest=sha256-timeout");
        request.setRetryable(true);
        request.setIssueCodes(List.of("RUNNER_TIMEOUT"));
        SyncExecution failed = execution();
        failed.setExecutionState(SyncExecutionState.FAILED.name());
        when(fixture.executionMapper().selectById(88L)).thenReturn(failed);

        SyncOfflineRunnerReportResult result =
                fixture.service().applyReport(11L, 88L, request, actor());

        assertThat(result.actionApplied()).isEqualTo("EXECUTION_FAILED");
        ArgumentCaptor<SyncExecutionFailRequest> failCaptor =
                ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
        verify(fixture.lifecycleSupport()).failExecution(any(SyncTask.class), any(SyncExecution.class),
                failCaptor.capture(), any(SyncActorContext.class));
        assertThat(failCaptor.getValue().getErrorType()).isEqualTo("RUNNER_TIMEOUT");
        assertThat(failCaptor.getValue().getErrorCode()).isEqualTo("DATAX_RUNNER_TIMEOUT");
        assertThat(failCaptor.getValue().getSamplePayload()).isNull();
        assertThat(failCaptor.getValue().getSourceRecordKey()).isNull();
        assertThat(failCaptor.getValue().getTargetRecordKey()).isNull();
        verify(fixture.receiptPublisher()).publishFailed(any(SyncTask.class), any(SyncExecution.class),
                any(SyncActorContext.class), any(), any());
        verify(fixture.reportMetrics()).recordReport(any(SyncOfflineRunnerReportRequest.class),
                any(SyncOfflineRunnerReportResult.class));
    }

    @Test
    void unsupportedRunnerStatusShouldRecordFailureMetricBeforeRethrow() {
        Fixture fixture = fixture();
        SyncOfflineRunnerReportRequest request = baseRequest("UNKNOWN_STATUS");

        assertThatThrownBy(() -> fixture.service().applyReport(11L, 88L, request, actor()))
                .hasMessageContaining("不支持的离线 Runner 报告状态");

        verify(fixture.reportMetrics()).recordFailure(any(SyncOfflineRunnerReportRequest.class),
                any(RuntimeException.class));
        verify(fixture.reportMetrics(), never()).recordReport(any(SyncOfflineRunnerReportRequest.class),
                any(SyncOfflineRunnerReportResult.class));
    }

    private Fixture fixture() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
        SyncCallbackIdempotencySupport idempotencySupport = mock(SyncCallbackIdempotencySupport.class);
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncOfflineRunnerReportMetrics reportMetrics = mock(SyncOfflineRunnerReportMetrics.class);
        when(taskMapper.selectById(11L)).thenReturn(task());
        when(executionMapper.selectById(88L)).thenReturn(execution());
        SyncOfflineRunnerReportCallbackService service = new SyncOfflineRunnerReportCallbackService(
                taskMapper, executionMapper, lifecycleSupport, idempotencySupport, receiptPublisher, reportMetrics);
        return new Fixture(service, taskMapper, executionMapper, lifecycleSupport, idempotencySupport,
                receiptPublisher, reportMetrics);
    }

    private SyncOfflineRunnerReportRequest baseRequest(String status) {
        SyncOfflineRunnerReportRequest request = new SyncOfflineRunnerReportRequest();
        request.setExecutorId("worker-1");
        request.setAdapterCode("TEST_DATAX_STYLE_RUNNER");
        request.setRunnerStatus(status);
        request.setIdempotencyKey("offline-report-" + status.toLowerCase());
        return request;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setCurrentState("RUNNING");
        return task;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setExecutorId("worker-1");
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
        return execution;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-offline-runner-report",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false);
    }

    private record Fixture(SyncOfflineRunnerReportCallbackService service,
                           SyncTaskMapper taskMapper,
                           SyncExecutionMapper executionMapper,
                           SyncExecutionLifecycleSupport lifecycleSupport,
                           SyncCallbackIdempotencySupport idempotencySupport,
                           DataSyncTaskManagementReceiptPublisher receiptPublisher,
                           SyncOfflineRunnerReportMetrics reportMetrics) {
    }
}
