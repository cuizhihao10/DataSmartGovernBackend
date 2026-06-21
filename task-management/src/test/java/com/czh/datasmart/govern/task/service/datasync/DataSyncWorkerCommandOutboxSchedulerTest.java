/**
 * @Author : Cui
 * @Date: 2026/06/21 00:35
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxSchedulerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSync worker outbox 后台 scheduler 测试。
 *
 * <p>这组测试不验证 Spring 定时器本身，而是验证调度业务语义：</p>
 * <p>1. 默认或子开关关闭时不能触发真实恢复/投递；</p>
 * <p>2. 所有安全开关打开后才允许运行；</p>
 * <p>3. 一轮调度必须先恢复 stale DISPATCHING，再投递 PENDING/到期 DEFERRED；</p>
 * <p>4. scheduler 使用配置中的 executorId 和批量上限，避免硬编码调度身份或无限批量投递。</p>
 */
class DataSyncWorkerCommandOutboxSchedulerTest {

    @Test
    void schedulerShouldNotRunWhenDataSyncOutboxSwitchDisabled() {
        AgentAsyncToolWorkerProperties properties = enabledBaseProperties();
        properties.setDataSyncOutboxSchedulerEnabled(false);
        DataSyncWorkerCommandOutboxSchedulerService schedulerService =
                mock(DataSyncWorkerCommandOutboxSchedulerService.class);
        DataSyncWorkerCommandOutboxScheduler scheduler =
                new DataSyncWorkerCommandOutboxScheduler(properties, schedulerService);

        scheduler.dispatchScheduledTick();

        verify(schedulerService, never()).dispatchScheduledTick();
    }

    @Test
    void schedulerShouldRunWhenAllSafetySwitchesAllow() {
        AgentAsyncToolWorkerProperties properties = enabledBaseProperties();
        properties.setDataSyncOutboxSchedulerEnabled(true);
        DataSyncWorkerCommandOutboxSchedulerService schedulerService =
                mock(DataSyncWorkerCommandOutboxSchedulerService.class);
        when(schedulerService.dispatchScheduledTick()).thenReturn(tickResult());
        DataSyncWorkerCommandOutboxScheduler scheduler =
                new DataSyncWorkerCommandOutboxScheduler(properties, schedulerService);

        scheduler.dispatchScheduledTick();

        verify(schedulerService).dispatchScheduledTick();
    }

    @Test
    void schedulerServiceShouldRecoverBeforeDispatchAndUseConfiguredLimits() {
        AgentAsyncToolWorkerProperties properties = enabledBaseProperties();
        properties.setExecutorId("datasync-scheduler-001");
        properties.setDataSyncOutboxRecoveryLimitPerTick(7);
        properties.setDataSyncOutboxDispatchLimitPerTick(11);
        DataSyncWorkerCommandOutboxRecoveryService recoveryService =
                mock(DataSyncWorkerCommandOutboxRecoveryService.class);
        DataSyncWorkerCommandDeliveryService deliveryService =
                mock(DataSyncWorkerCommandDeliveryService.class);
        when(recoveryService.recoverStaleDispatching(org.mockito.ArgumentMatchers.any()))
                .thenReturn(recoveryResult());
        when(deliveryService.dispatchBatch(org.mockito.ArgumentMatchers.any()))
                .thenReturn(dispatchResult());
        DataSyncWorkerCommandOutboxSchedulerService schedulerService =
                new DataSyncWorkerCommandOutboxSchedulerService(properties, recoveryService, deliveryService);

        DataSyncWorkerOutboxSchedulerTickResult result = schedulerService.dispatchScheduledTick();

        InOrder order = inOrder(recoveryService, deliveryService);
        ArgumentCaptor<DataSyncWorkerOutboxRecoveryRequest> recoveryCaptor =
                ArgumentCaptor.forClass(DataSyncWorkerOutboxRecoveryRequest.class);
        ArgumentCaptor<DataSyncWorkerOutboxDispatchBatchRequest> dispatchCaptor =
                ArgumentCaptor.forClass(DataSyncWorkerOutboxDispatchBatchRequest.class);
        order.verify(recoveryService).recoverStaleDispatching(recoveryCaptor.capture());
        order.verify(deliveryService).dispatchBatch(dispatchCaptor.capture());
        assertEquals("datasync-scheduler-001", recoveryCaptor.getValue().getExecutorId());
        assertEquals(7, recoveryCaptor.getValue().getLimit());
        assertEquals("datasync-scheduler-001", dispatchCaptor.getValue().getExecutorId());
        assertEquals(11, dispatchCaptor.getValue().getLimit());
        assertTrue(dispatchCaptor.getValue().includeDeferredCommands());
        assertTrue(result.hasMeaningfulWork());
        assertEquals(2, result.warnings().size());
    }

    private AgentAsyncToolWorkerProperties enabledBaseProperties() {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setEnabled(true);
        properties.setDryRunOnly(false);
        properties.setSchedulerEnabled(true);
        return properties;
    }

    private DataSyncWorkerOutboxSchedulerTickResult tickResult() {
        return new DataSyncWorkerOutboxSchedulerTickResult(
                "datasmart.task.data-sync-worker-outbox.scheduler-tick.v1",
                "datasync-scheduler-001",
                LocalDateTime.now(),
                recoveryResult(),
                dispatchResult(),
                List.of("recovered", "dispatched")
        );
    }

    private DataSyncWorkerOutboxRecoveryResult recoveryResult() {
        return new DataSyncWorkerOutboxRecoveryResult(
                "datasmart.task.data-sync-worker-outbox.stale-recovery.v1",
                "datasync-scheduler-001",
                LocalDateTime.now(),
                300,
                30,
                LocalDateTime.now().minusMinutes(5),
                7,
                7,
                1,
                1,
                1,
                0,
                0,
                "test recovery policy",
                List.of(),
                List.of("recovered")
        );
    }

    private DataSyncWorkerOutboxDispatchBatchResult dispatchResult() {
        return new DataSyncWorkerOutboxDispatchBatchResult(
                "datasmart.task.data-sync-worker-outbox.dispatch-batch.v1",
                "datasync-scheduler-001",
                LocalDateTime.now(),
                1,
                1,
                1,
                0,
                0,
                0,
                List.of(),
                List.of("dispatched")
        );
    }
}
