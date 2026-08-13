/**
 * @Author : Cui
 * @Date: 2026/08/11 22:50
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryMetricsTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 data-sync Autopilot 指标不会引入业务对象高基数标签。 */
class SyncAutopilotRecoveryMetricsTest {

    /** Calls every finite Autopilot outcome and proves that V23 adds no high-cardinality metric labels. */
    @Test
    void shouldExposeOnlyFiniteTriggerAndTerminalOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncAutopilotRecoveryMetrics metrics = new SyncAutopilotRecoveryMetrics(registry);

        metrics.recordTriggerAccepted();
        metrics.recordTriggerRejected();
        metrics.recordRecoverySucceeded();
        metrics.recordRecoveryFailed();
        metrics.recordTriggerSidecarFailure();
        metrics.recordFinalizationSidecarFailure();
        metrics.recordSidecarCompensationDeadLetter();

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        Set<String> outcomes = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getValue())
                .collect(Collectors.toSet());

        assertThat(tagKeys).containsOnly("outcome");
        assertThat(outcomes).containsExactlyInAnyOrder(
                "ACCEPTED", "REJECTED", "RECOVERED", "FAILED", "TRIGGER", "FINALIZATION", "DEAD_LETTER");
        assertThat(registry.getMeters()).hasSize(7);
    }
}
