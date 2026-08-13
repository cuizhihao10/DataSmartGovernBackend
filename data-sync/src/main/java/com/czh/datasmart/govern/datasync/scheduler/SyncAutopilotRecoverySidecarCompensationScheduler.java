/**
 * @Author : Cui
 * @Date: 2026/08/11 21:40
 * @Description DataSmart Govern Backend - SyncAutopilotRecoverySidecarCompensationScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoverySidecarCompensationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replays V23 Autopilot sidecar compensation facts after an isolated trigger or finalization transaction failed.
 *
 * <p>This scheduler never creates a recovery decision, retries a sync execution, or sends Kafka directly. Its
 * only responsibility is to invoke the compensation service, which conditionally claims durable journal rows and
 * reuses the existing governed publisher. The local {@link AtomicBoolean} avoids overlapping scans in one JVM;
 * multi-instance ownership remains enforced by the database claim token.</p>
 */
@Slf4j
@Component
public class SyncAutopilotRecoverySidecarCompensationScheduler {

    private final SyncAutopilotRecoverySidecarCompensationService compensationService;
    private final SyncAutopilotRecoveryTriggerProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates the scheduler with the journal service and its nested V23 configuration.
     *
     * <p>No background work starts in this constructor. Spring invokes {@link #replayDueCompensations()} according
     * to the configured fixed delay, which lets application startup complete before the scheduler reads durable
     * recovery state.</p>
     *
     * @param compensationService service that performs durable claim and replay work
     * @param properties global and V23-specific Autopilot recovery settings
     */
    public SyncAutopilotRecoverySidecarCompensationScheduler(
            SyncAutopilotRecoverySidecarCompensationService compensationService,
            SyncAutopilotRecoveryTriggerProperties properties) {
        this.compensationService = compensationService;
        this.properties = properties;
    }

    /**
     * Scans one bounded V23 compensation batch when both global and V23 scheduling are enabled.
     *
     * <p>A disabled setting returns before any database work. When a previous local pass is still active, this
     * method also returns because the service's database claim protects cross-instance competition but cannot
     * save an in-process thread from avoidable duplicate scans. An unexpected mapper/runtime failure is logged
     * and released so a later fixed-delay tick can retry the scheduler itself.</p>
     */
    @Scheduled(
            initialDelayString = "${datasmart.data-sync.autopilot-recovery-trigger.sidecar-compensation.initial-delay-ms:45000}",
            fixedDelayString = "${datasmart.data-sync.autopilot-recovery-trigger.sidecar-compensation.fixed-delay-ms:30000}")
    public void replayDueCompensations() {
        SyncAutopilotRecoveryTriggerProperties.SidecarCompensation configuration =
                properties.getSidecarCompensation();
        if (!properties.isEnabled() || configuration == null || !configuration.isEnabled()
                || !configuration.isSchedulerEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Autopilot sidecar compensation scheduler skipped a local re-entry");
            return;
        }
        try {
            int resolved = compensationService.replayDue();
            if (resolved > 0) {
                log.info("Resolved {} durable Autopilot sidecar compensations", resolved);
            }
        } catch (RuntimeException exception) {
            log.warn("Autopilot sidecar compensation scheduler pass failed, exceptionType={}",
                    exception.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
