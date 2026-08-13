/**
 * @Author : Cui
 * @Date: 2026/08/11 22:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryMetricsTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Autopilot 指标只使用代码内定义的低基数 outcome 标签。 */
class AgentAutopilotRecoveryMetricsTest {

    /**
     * 调用全部公开记录方法，并确认结果中没有业务 ID、reasonCode 或异常文本标签。
     */
    @Test
    void shouldExposeOnlyFiniteLowCardinalityOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentAutopilotRecoveryMetrics metrics = new AgentAutopilotRecoveryMetrics(registry);

        metrics.recordTriggerAccepted();
        metrics.recordTriggerRejected();
        metrics.recordPlanningSucceeded();
        metrics.recordPlanningFailed();
        metrics.recordEvidenceAccepted();
        metrics.recordEvidenceRejected();
        metrics.recordAutomaticRecoveryStarted();
        metrics.recordAttentionRequired();
        metrics.recordDeadLettered();

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        Set<String> outcomes = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getValue())
                .collect(Collectors.toSet());

        assertThat(tagKeys).containsOnly("outcome");
        assertThat(outcomes).containsExactlyInAnyOrder(
                "ACCEPTED", "REJECTED", "SUCCEEDED", "FAILED",
                "RECOVERY_STARTED", "ATTENTION_REQUIRED", "DEAD_LETTERED");
        assertThat(registry.getMeters()).hasSize(9);
    }
}
