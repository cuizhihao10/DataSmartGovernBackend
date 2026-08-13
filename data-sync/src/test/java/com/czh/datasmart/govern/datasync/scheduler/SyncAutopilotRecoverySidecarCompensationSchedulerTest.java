/**
 * @Author : Cui
 * @Date: 2026/08/11 21:45
 * @Description DataSmart Govern Backend - SyncAutopilotRecoverySidecarCompensationSchedulerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoverySidecarCompensationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies that the V23 scheduler is assembled as a thin configuration gate around the durable claim loop.
 */
class SyncAutopilotRecoverySidecarCompensationSchedulerTest {

    /**
     * A fully enabled scheduler must delegate exactly once; row-level idempotency remains inside the service.
     */
    @Test
    void replayDueCompensationsShouldDelegateWhenV23SchedulingIsEnabled() {
        SyncAutopilotRecoverySidecarCompensationService compensationService =
                mock(SyncAutopilotRecoverySidecarCompensationService.class);
        SyncAutopilotRecoverySidecarCompensationScheduler scheduler =
                new SyncAutopilotRecoverySidecarCompensationScheduler(
                        compensationService, new SyncAutopilotRecoveryTriggerProperties());

        scheduler.replayDueCompensations();

        verify(compensationService).replayDue();
    }

    /**
     * Disabling only the V23 scheduler must leave the Kafka trigger outbox configuration untouched and skip I/O.
     */
    @Test
    void replayDueCompensationsShouldNotDelegateWhenV23SchedulingIsDisabled() {
        SyncAutopilotRecoverySidecarCompensationService compensationService =
                mock(SyncAutopilotRecoverySidecarCompensationService.class);
        SyncAutopilotRecoveryTriggerProperties properties = new SyncAutopilotRecoveryTriggerProperties();
        properties.getSidecarCompensation().setSchedulerEnabled(false);
        SyncAutopilotRecoverySidecarCompensationScheduler scheduler =
                new SyncAutopilotRecoverySidecarCompensationScheduler(compensationService, properties);

        scheduler.replayDueCompensations();

        verify(compensationService, never()).replayDue();
    }
}
