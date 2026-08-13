/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptPublisherTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * task-management receipt 发布器测试。
 *
 * <p>publisher 只负责把 data-sync 的领域事实转换成低敏 receipt 请求；
 * 真正“先落 outbox、再投递、失败重试、死信”的可靠性由 DataSyncTaskManagementReceiptOutboxService 测试覆盖。
 * 这样拆分后，测试也能体现代码职责边界。</p>
 */
class DataSyncTaskManagementReceiptPublisherTest {

    /**
     * complete receipt 应只携带数量、完成状态和 checkpoint 可见性策略。
     */
    @Test
    void publishCompleteShouldBuildLowSensitiveReceiptAndEnqueueOutbox() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        DataSyncTaskManagementReceiptPublisher publisher = publisher(outboxService, true);
        SyncTask task = task();
        SyncExecution execution = execution();
        SyncActorContext actor = actor();

        publisher.publishComplete(task, execution, actor, response());

        TaskManagementExecutionReceiptRequest request = capturedRequest(outboxService, task, execution, actor);
        assertThat(request.getReceiptId()).isEqualTo("data-sync-execution-receipt:88:complete");
        assertThat(request.getEventType()).isEqualTo("COMPLETE");
        assertThat(request.getSyncTaskId()).isEqualTo(11L);
        assertThat(request.getSyncExecutionId()).isEqualTo(88L);
        assertThat(request.getTotalRecordsRead()).isEqualTo(12L);
        assertThat(request.getTotalRecordsWritten()).isEqualTo(10L);
        assertThat(request.getProgressPercent()).isEqualTo(100);
        assertThat(request.getCompleted()).isTrue();
        assertThat(request.getFailed()).isFalse();
        assertThat(request.getCheckpointValueVisibility()).isEqualTo("NO_CHECKPOINT_VALUE_IN_RECEIPT");
        assertThat(request.toString()).doesNotContain("jdbc:");
        assertThat(request.toString()).doesNotContain("select ");
    }

    /**
     * failed receipt 应只携带低敏错误码和 issueCode，不携带异常正文或样本。
     */
    @Test
    void publishFailedShouldBuildLowSensitiveReceiptAndEnqueueOutbox() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        DataSyncTaskManagementReceiptPublisher publisher = publisher(outboxService, true);
        SyncTask task = task();
        SyncExecution execution = execution();
        SyncActorContext actor = actor();

        publisher.publishFailed(task, execution, actor(),
                "OUTER_BATCH_LOOP_NOT_IMPLEMENTED",
                List.of("OUTER_BATCH_LOOP_NOT_IMPLEMENTED", "jdbc:mysql://hidden"));

        TaskManagementExecutionReceiptRequest request = capturedRequest(outboxService, task, execution, actor);
        assertThat(request.getReceiptId()).isEqualTo("data-sync-execution-receipt:88:failed");
        assertThat(request.getEventType()).isEqualTo("FAILED");
        assertThat(request.getFailed()).isTrue();
        assertThat(request.getErrorSummary()).isEqualTo("data-sync execution failed, errorCode=OUTER_BATCH_LOOP_NOT_IMPLEMENTED");
        assertThat(request.getWarnings()).contains("issueCode=OUTER_BATCH_LOOP_NOT_IMPLEMENTED");
        assertThat(request.toString()).doesNotContain("jdbc:mysql://hidden");
    }

    /**
     * 配置关闭时，publisher 不应写 outbox，也不应触发 HTTP 投递。
     */
    @Test
    void publishShouldDoNothingWhenReceiptDisabled() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        DataSyncTaskManagementReceiptPublisher publisher = publisher(outboxService, false);

        publisher.publishComplete(task(), execution(), actor(), response());

        verify(outboxService, never()).enqueueAndDispatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * Autopilot 恢复触发不能依赖 task-management 展示投影开关。
     */
    @Test
    void publishFailedShouldTriggerAutopilotEvenWhenReceiptProjectionIsDisabled() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setEnabled(false);
        DataSyncTaskManagementReceiptPublisher publisher = new DataSyncTaskManagementReceiptPublisher(
                outboxService, properties, triggerPublisher);

        publisher.publishFailed(task(), execution(), actor(), "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        verify(triggerPublisher).publishFailed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                eq("TARGET_TIMEOUT"),
                eq(List.of("TARGET_TIMEOUT")));
        verify(outboxService, never()).enqueueAndDispatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 成功收敛同样不能依赖 task-management 展示投影开关，否则夜间恢复成功后 case 会永久停在执行中。
     */
    @Test
    void publishCompleteShouldCloseAutopilotEvenWhenReceiptProjectionIsDisabled() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setEnabled(false);
        DataSyncTaskManagementReceiptPublisher publisher = new DataSyncTaskManagementReceiptPublisher(
                outboxService, properties, triggerPublisher);
        SyncTask task = task();
        SyncExecution execution = execution();

        publisher.publishComplete(task, execution, actor(), response());

        verify(triggerPublisher).publishSucceeded(task, execution);
        verify(outboxService, never()).enqueueAndDispatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * Autopilot 的独立事务失败时，原有 task-management 失败投影仍必须继续写入自己的 outbox。
     *
     * <p>这条回归保护“恢复控制面”和“执行事实投影”的故障隔离：前者可以回滚并等待告警处理，
     * 后者不能因此丢失已经发生的 execution failed 事实。</p>
     */
    /**
     * PARTIALLY_SUCCEEDED remains a partial result for task-management, but failed objects must still open
     * the bounded Autopilot recovery path even when the receipt projection is disabled.
     */
    @Test
    void publishPartiallySucceededShouldTriggerAutopilotForFailedObjectsEvenWhenReceiptProjectionIsDisabled() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setEnabled(false);
        DataSyncTaskManagementReceiptPublisher publisher = new DataSyncTaskManagementReceiptPublisher(
                outboxService, properties, triggerPublisher);
        SyncTask task = task();
        SyncExecution execution = execution();

        publisher.publishPartiallySucceeded(task, execution, actor(), response(), List.of("FAILED_OBJECT_TIMEOUT"));

        verify(triggerPublisher).publishFailed(
                eq(task), eq(execution), eq("PARTIAL_OBJECT_FAILURE"), eq(List.of("FAILED_OBJECT_TIMEOUT")));
        verify(outboxService, never()).enqueueAndDispatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /** A partial receipt without a failed-object count must not manufacture an Autopilot recovery. */
    @Test
    void publishPartiallySucceededShouldNotTriggerAutopilotWithoutFailedObjects() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setEnabled(false);
        DataSyncTaskManagementReceiptPublisher publisher = new DataSyncTaskManagementReceiptPublisher(
                outboxService, properties, triggerPublisher);
        SyncExecution execution = execution();
        execution.setFailedRecordCount(0L);
        DatasourceRunOnceResponse response = response();
        response.setBatchFailedRecordCount(0L);
        response.setTotalFailedRecordCount(0L);

        publisher.publishPartiallySucceeded(task(), execution, actor(), response, List.of("FAILED_OBJECT_TIMEOUT"));

        verify(triggerPublisher, never()).publishFailed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishFailedShouldContinueReceiptProjectionWhenAutopilotTransactionFails() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        SyncAutopilotRecoverySidecarCompensationService compensationService =
                mock(SyncAutopilotRecoverySidecarCompensationService.class);
        SyncAutopilotRecoveryMetrics metrics = mock(SyncAutopilotRecoveryMetrics.class);
        doThrow(new IllegalStateException("outbox insert failed"))
                .when(triggerPublisher).publishFailed(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setEnabled(true);
        DataSyncTaskManagementReceiptPublisher publisher = new DataSyncTaskManagementReceiptPublisher(
                outboxService, properties, triggerPublisher, compensationService, metrics);
        SyncTask task = task();
        SyncExecution execution = execution();
        SyncActorContext actor = actor();

        publisher.publishFailed(task, execution, actor, "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));

        TaskManagementExecutionReceiptRequest request = capturedRequest(outboxService, task, execution, actor);
        assertThat(request.getEventType()).isEqualTo("FAILED");
        assertThat(request.getFailed()).isTrue();
        verify(compensationService).recordFailedTrigger(task, execution,
                "TARGET_TIMEOUT", List.of("TARGET_TIMEOUT"));
        verify(metrics).recordTriggerSidecarFailure();
    }

    @Test
    void publishCompleteShouldContinueReceiptProjectionAndRecordCompensationWhenFinalizationTransactionFails() {
        DataSyncTaskManagementReceiptOutboxService outboxService = mock(DataSyncTaskManagementReceiptOutboxService.class);
        SyncAutopilotRecoveryTriggerPublisher triggerPublisher = mock(SyncAutopilotRecoveryTriggerPublisher.class);
        SyncAutopilotRecoverySidecarCompensationService compensationService =
                mock(SyncAutopilotRecoverySidecarCompensationService.class);
        SyncAutopilotRecoveryMetrics metrics = mock(SyncAutopilotRecoveryMetrics.class);
        doThrow(new IllegalStateException("case transition failed"))
                .when(triggerPublisher).publishSucceeded(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setEnabled(true);
        DataSyncTaskManagementReceiptPublisher publisher = new DataSyncTaskManagementReceiptPublisher(
                outboxService, properties, triggerPublisher, compensationService, metrics);
        SyncTask task = task();
        SyncExecution execution = execution();
        SyncActorContext actor = actor();

        publisher.publishComplete(task, execution, actor, response());

        TaskManagementExecutionReceiptRequest request = capturedRequest(outboxService, task, execution, actor);
        assertThat(request.getEventType()).isEqualTo("COMPLETE");
        assertThat(request.getCompleted()).isTrue();
        verify(compensationService).recordSuccessfulFinalization(task, execution);
        verify(metrics).recordFinalizationSidecarFailure();
    }

    private TaskManagementExecutionReceiptRequest capturedRequest(DataSyncTaskManagementReceiptOutboxService outboxService,
                                                                  SyncTask task,
                                                                  SyncExecution execution,
                                                                  SyncActorContext actor) {
        ArgumentCaptor<TaskManagementExecutionReceiptRequest> captor =
                ArgumentCaptor.forClass(TaskManagementExecutionReceiptRequest.class);
        verify(outboxService).enqueueAndDispatch(eq(task), eq(execution), captor.capture(), eq(actor));
        return captor.getValue();
    }

    private DataSyncTaskManagementReceiptPublisher publisher(DataSyncTaskManagementReceiptOutboxService outboxService,
                                                             boolean enabled) {
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setEnabled(enabled);
        return new DataSyncTaskManagementReceiptPublisher(outboxService, properties);
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        return task;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setSyncTaskId(11L);
        execution.setExecutorId("worker-loop-test");
        execution.setRecordsRead(10L);
        execution.setRecordsWritten(8L);
        execution.setFailedRecordCount(1L);
        return execution;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 0L, "SERVICE_ACCOUNT", "trace-receipt-test");
    }

    private DatasourceRunOnceResponse response() {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setBatchRecordsRead(2L);
        response.setBatchRecordsWritten(2L);
        response.setBatchFailedRecordCount(0L);
        response.setTotalRecordsRead(12L);
        response.setTotalRecordsWritten(10L);
        response.setTotalFailedRecordCount(1L);
        response.setEndOfSource(true);
        return response;
    }
}
