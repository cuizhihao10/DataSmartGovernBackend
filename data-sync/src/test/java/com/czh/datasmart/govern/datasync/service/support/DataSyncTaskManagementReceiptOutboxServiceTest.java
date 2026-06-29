/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptOutboxServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.TaskManagementReceiptOutboxDispatchResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskManagementReceiptOutbox;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptClient;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptRequest;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskManagementReceiptOutboxMapper;
import com.czh.datasmart.govern.datasync.scheduler.DataSyncTaskManagementReceiptOutboxMetrics;
import com.czh.datasmart.govern.datasync.support.TaskManagementReceiptOutboxState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-management receipt outbox 服务测试。
 *
 * <p>这些测试不启动数据库或 HTTP 服务，而是用 Mapper/Client mock 验证状态流转：
 * PENDING -> DELIVERING -> DELIVERED、PENDING -> DELIVERING -> RETRY_WAIT、
 * 以及超过最大尝试次数后的 DEAD_LETTER。</p>
 */
class DataSyncTaskManagementReceiptOutboxServiceTest {

    @Test
    void enqueueShouldPersistOutboxBeforeImmediateDeliveryAndMarkDelivered() {
        Fixture fixture = fixture(false, false);
        when(fixture.mapper().selectByReceiptId("receipt-88-complete")).thenReturn(null);
        when(fixture.mapper().insert(any(SyncTaskManagementReceiptOutbox.class))).thenAnswer(invocation -> {
            SyncTaskManagementReceiptOutbox outbox = invocation.getArgument(0);
            outbox.setId(700L);
            return 1;
        });
        when(fixture.mapper().markDelivering(eq(700L), anyLong())).thenReturn(1);
        when(fixture.mapper().markDelivered(700L)).thenReturn(1);

        TaskManagementReceiptOutboxDispatchResult result =
                fixture.service().enqueueAndDispatch(task(), execution(), request("COMPLETE"), actor());

        assertThat(result.deliveredCount()).isEqualTo(1);
        ArgumentCaptor<SyncTaskManagementReceiptOutbox> outboxCaptor =
                ArgumentCaptor.forClass(SyncTaskManagementReceiptOutbox.class);
        verify(fixture.mapper()).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getReceiptId()).isEqualTo("receipt-88-complete");
        assertThat(outboxCaptor.getValue().getOutboxState()).isEqualTo(TaskManagementReceiptOutboxState.PENDING.name());
        assertThat(outboxCaptor.getValue().getPayloadJson()).doesNotContain("jdbc:");
        verify(fixture.client()).record(any(TaskManagementExecutionReceiptRequest.class), eq(actor()));
        verify(fixture.mapper()).markDelivered(700L);
    }

    @Test
    void dispatchFailureShouldScheduleRetryWithoutBlockingDefaultFlow() throws Exception {
        Fixture fixture = fixture(true, false);
        SyncTaskManagementReceiptOutbox outbox = outbox("FAILED", 1, 6);
        when(fixture.mapper().selectByReceiptId("receipt-88-failed")).thenReturn(outbox);
        when(fixture.mapper().markDelivering(eq(700L), anyLong())).thenReturn(1);
        when(fixture.mapper().markDeliveryFailure(eq(700L), eq(TaskManagementReceiptOutboxState.RETRY_WAIT.name()),
                any(LocalDateTime.class), eq(null), eq("EXTERNAL_DEPENDENCY_FAILED"), any())).thenReturn(1);

        TaskManagementReceiptOutboxDispatchResult result =
                fixture.service().enqueueAndDispatch(task(), execution(), request("FAILED"), actor());

        assertThat(result.retryScheduledCount()).isEqualTo(1);
        assertThat(result.deadLetteredCount()).isZero();
        verify(fixture.mapper()).markDeliveryFailure(eq(700L), eq(TaskManagementReceiptOutboxState.RETRY_WAIT.name()),
                any(LocalDateTime.class), eq(null), eq("EXTERNAL_DEPENDENCY_FAILED"), any());
    }

    @Test
    void retryShouldMoveToDeadLetterWhenMaxAttemptsReached() throws Exception {
        Fixture fixture = fixture(true, false);
        SyncTaskManagementReceiptOutbox outbox = outbox("FAILED", 5, 6);
        when(fixture.mapper().selectDueReceipts(eq(20), anyLong())).thenReturn(List.of(outbox));
        when(fixture.mapper().markDelivering(eq(700L), anyLong())).thenReturn(1);
        when(fixture.mapper().markDeliveryFailure(eq(700L), eq(TaskManagementReceiptOutboxState.DEAD_LETTER.name()),
                eq(null), any(LocalDateTime.class), eq("EXTERNAL_DEPENDENCY_FAILED"), any())).thenReturn(1);

        TaskManagementReceiptOutboxDispatchResult result = fixture.service().dispatchDue(20, actor());

        assertThat(result.deadLetteredCount()).isEqualTo(1);
        assertThat(result.retryScheduledCount()).isZero();
        verify(fixture.mapper()).markDeliveryFailure(eq(700L), eq(TaskManagementReceiptOutboxState.DEAD_LETTER.name()),
                eq(null), any(LocalDateTime.class), eq("EXTERNAL_DEPENDENCY_FAILED"), any());
        verify(fixture.metrics()).recordDispatchSuccess(any(TaskManagementReceiptOutboxDispatchResult.class));
    }

    @Test
    void deliveryRequiredShouldThrowAfterFailureStateIsPersisted() throws Exception {
        Fixture fixture = fixture(true, true);
        SyncTaskManagementReceiptOutbox outbox = outbox("FAILED", 1, 6);
        when(fixture.mapper().selectByReceiptId("receipt-88-failed")).thenReturn(outbox);
        when(fixture.mapper().markDelivering(eq(700L), anyLong())).thenReturn(1);
        when(fixture.mapper().markDeliveryFailure(eq(700L), eq(TaskManagementReceiptOutboxState.RETRY_WAIT.name()),
                any(LocalDateTime.class), eq(null), eq("EXTERNAL_DEPENDENCY_FAILED"), any())).thenReturn(1);

        assertThatThrownBy(() -> fixture.service().enqueueAndDispatch(task(), execution(), request("FAILED"), actor()))
                .isInstanceOf(PlatformBusinessException.class);

        verify(fixture.mapper()).markDeliveryFailure(eq(700L), eq(TaskManagementReceiptOutboxState.RETRY_WAIT.name()),
                any(LocalDateTime.class), eq(null), eq("EXTERNAL_DEPENDENCY_FAILED"), any());
    }

    @Test
    void deliveredOutboxShouldNotBeDispatchedAgain() throws Exception {
        Fixture fixture = fixture(false, false);
        SyncTaskManagementReceiptOutbox outbox = outbox("COMPLETE", 1, 6);
        outbox.setOutboxState(TaskManagementReceiptOutboxState.DELIVERED.name());
        when(fixture.mapper().selectByReceiptId("receipt-88-complete")).thenReturn(outbox);

        TaskManagementReceiptOutboxDispatchResult result =
                fixture.service().enqueueAndDispatch(task(), execution(), request("COMPLETE"), actor());

        assertThat(result.skippedCount()).isEqualTo(1);
        verify(fixture.client(), never()).record(any(), any());
    }

    private Fixture fixture(boolean clientFails, boolean deliveryRequired) {
        SyncTaskManagementReceiptOutboxMapper mapper = mock(SyncTaskManagementReceiptOutboxMapper.class);
        TaskManagementExecutionReceiptClient client = mock(TaskManagementExecutionReceiptClient.class);
        if (clientFails) {
            org.mockito.Mockito.doThrow(new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                            "task-management unavailable"))
                    .when(client).record(any(TaskManagementExecutionReceiptRequest.class), any(SyncActorContext.class));
        }
        DataSyncTaskManagementReceiptProperties properties = new DataSyncTaskManagementReceiptProperties();
        properties.setDeliveryRequired(deliveryRequired);
        properties.getOutbox().setMaxAttempts(6);
        properties.getOutbox().setBaseBackoffSeconds(30);
        properties.getOutbox().setBatchSize(20);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        DataSyncTaskManagementReceiptOutboxMetrics metrics = mock(DataSyncTaskManagementReceiptOutboxMetrics.class);
        return new Fixture(
                new DataSyncTaskManagementReceiptOutboxService(mapper, client, properties, objectMapper, metrics),
                mapper,
                client,
                metrics);
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
        return execution;
    }

    private TaskManagementExecutionReceiptRequest request(String eventType) {
        TaskManagementExecutionReceiptRequest request = new TaskManagementExecutionReceiptRequest();
        request.setReceiptId("receipt-88-" + eventType.toLowerCase());
        request.setSyncTaskId(11L);
        request.setSyncExecutionId(88L);
        request.setEventType(eventType);
        request.setSourceService("data-sync");
        request.setEventTime(LocalDateTime.now());
        request.setExecutorId("worker-loop-test");
        request.setWarnings(List.of("LOW_SENSITIVE_TEST_WARNING"));
        return request;
    }

    private SyncTaskManagementReceiptOutbox outbox(String eventType, int attemptCount, int maxAttemptCount)
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        SyncTaskManagementReceiptOutbox outbox = new SyncTaskManagementReceiptOutbox();
        outbox.setId(700L);
        outbox.setReceiptId("receipt-88-" + eventType.toLowerCase());
        outbox.setTenantId(7L);
        outbox.setProjectId(101L);
        outbox.setWorkspaceId(301L);
        outbox.setSyncTaskId(11L);
        outbox.setSyncExecutionId(88L);
        outbox.setEventType(eventType);
        outbox.setOutboxState(TaskManagementReceiptOutboxState.PENDING.name());
        outbox.setAttemptCount(attemptCount);
        outbox.setMaxAttemptCount(maxAttemptCount);
        outbox.setActorId(0L);
        outbox.setActorRole("SERVICE_ACCOUNT");
        outbox.setTraceId("trace-receipt-outbox-test");
        outbox.setPayloadJson(objectMapper.writeValueAsString(request(eventType)));
        return outbox;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 0L, "SERVICE_ACCOUNT", "trace-receipt-outbox-test");
    }

    private record Fixture(DataSyncTaskManagementReceiptOutboxService service,
                           SyncTaskManagementReceiptOutboxMapper mapper,
                           TaskManagementExecutionReceiptClient client,
                           DataSyncTaskManagementReceiptOutboxMetrics metrics) {
    }
}
