/**
 * @Author : Cui
 * @Date: 2026/06/05 00:24
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotIndexTelemetryTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Skill 可见性快照索引遥测测试。
 *
 * <p>该测试重点不是“Counter 能不能加一”这么简单，而是固定低基数标签契约。
 * Prometheus 可以承受 store、outcome、bindingStatus 这类有限枚举；不能承受 runId、tenantId、
 * requestId 或 manifestFingerprint 这类请求级/版本级动态标签。</p>
 */
class AgentSkillVisibilitySnapshotIndexTelemetryTest {

    @Test
    void materializationMetricsShouldUseLowCardinalityLabels() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentSkillVisibilitySnapshotIndexTelemetry telemetry =
                new AgentSkillVisibilitySnapshotIndexTelemetry(meterRegistry);

        telemetry.recordMaterialized(record("BOUND_REMOTE_MANIFEST"), "mysql");
        telemetry.recordDuplicate(record("CUSTOMER_DYNAMIC_STATUS"), "customer-store");

        assertEquals(1.0, meterRegistry.counter(
                "datasmart_agent_runtime_skill_visibility_index_materialization_total",
                "outcome", "materialized",
                "store", "mysql",
                "bindingStatus", "BOUND_REMOTE_MANIFEST"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "datasmart_agent_runtime_skill_visibility_index_materialization_total",
                "outcome", "duplicate",
                "store", "OTHER",
                "bindingStatus", "OTHER"
        ).count());
        assertNull(meterRegistry.find("datasmart_agent_runtime_skill_visibility_index_materialization_total")
                .tag("runId", "run-metrics")
                .counter());
    }

    @Test
    void queryMetricsShouldSeparateDedicatedAndFallbackSources() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentSkillVisibilitySnapshotIndexTelemetry telemetry =
                new AgentSkillVisibilitySnapshotIndexTelemetry(meterRegistry);

        telemetry.recordDedicatedQuery("memory", 3);
        telemetry.recordFallbackQuery(2);

        assertEquals(1.0, meterRegistry.counter(
                "datasmart_agent_runtime_skill_visibility_index_query_total",
                "source", "dedicated",
                "result", "success",
                "store", "memory"
        ).count());
        assertEquals(3.0, meterRegistry.counter(
                "datasmart_agent_runtime_skill_visibility_index_query_result_total",
                "source", "dedicated",
                "store", "memory"
        ).count());
        assertEquals(1.0, meterRegistry.counter(
                "datasmart_agent_runtime_skill_visibility_index_query_total",
                "source", "fallback",
                "result", "success",
                "store", "none"
        ).count());
        assertEquals(2.0, meterRegistry.counter(
                "datasmart_agent_runtime_skill_visibility_index_query_result_total",
                "source", "fallback",
                "store", "none"
        ).count());
    }

    private AgentRuntimeEventProjectionRecord record(String bindingStatus) {
        Instant now = Instant.parse("2026-06-05T00:20:00Z");
        return new AgentRuntimeEventProjectionRecord(
                "identity-metrics",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE,
                "skill_visibility",
                "已记录 Skill 可见性快照。",
                "info",
                "tenant-metrics",
                "project-metrics",
                "actor-metrics",
                "request-metrics",
                "run-metrics",
                "session-metrics",
                1L,
                1L,
                now,
                now,
                now,
                Map.of("manifestBindingStatus", bindingStatus)
        );
    }
}
