/**
 * @Author : Cui
 * @Date: 2026/06/04 00:16
 * @Description DataSmart Govern Backend - AgentToolRuntimeProtectionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.protection;

import com.czh.datasmart.govern.agent.config.AgentToolRuntimeProtectionProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具运行时保护服务测试。
 *
 * <p>这些测试覆盖的是“工具已经通过 sandbox 后，是否因为运行时容量或下游健康而暂缓执行”。
 * 它不是测试某个具体工具适配器，而是保护 Agent 平台在高并发、下游故障和模型自动重试场景下不会放大事故。</p>
 */
class AgentToolRuntimeProtectionServiceTest {

    @Test
    void shouldAcquireAndReleaseInFlightLease() {
        AgentToolRuntimeProtectionService service = new AgentToolRuntimeProtectionService(baseProperties());
        AgentToolExecutionAuditRecord audit = audit("audit-001", "datasource-management", 10L);

        AgentToolRuntimeProtectionLease lease = service.beginExecution(session(), run(), audit);
        AgentToolRuntimeProtectionVerdict duringExecution = service.inspect(session(), run(), audit);

        assertTrue(duringExecution.allowed());
        assertEquals(1, duringExecution.globalInFlight());
        assertEquals(1, duringExecution.tenantInFlight());
        assertEquals(1, duringExecution.targetServiceInFlight());

        lease.recordSuccess();
        lease.close();
        AgentToolRuntimeProtectionVerdict afterRelease = service.inspect(session(), run(), audit);

        assertEquals(0, afterRelease.globalInFlight());
        assertEquals(0, afterRelease.tenantInFlight());
        assertEquals(0, afterRelease.targetServiceInFlight());
        assertEquals(0, afterRelease.consecutiveFailures());
    }

    @Test
    void shouldRejectWhenTargetServiceInFlightLimitExceeded() {
        AgentToolRuntimeProtectionProperties properties = baseProperties();
        properties.setMaxTargetServiceInFlight(1);
        AgentToolRuntimeProtectionService service = new AgentToolRuntimeProtectionService(properties);
        AgentToolExecutionAuditRecord firstAudit = audit("audit-001", "datasource-management", 10L);
        AgentToolExecutionAuditRecord secondAudit = audit("audit-002", "datasource-management", 10L);

        AgentToolRuntimeProtectionLease firstLease = service.beginExecution(session(), run(), firstAudit);
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> service.beginExecution(session(), run(), secondAudit));

        assertTrue(exception.getMessage().contains("TARGET_SERVICE_IN_FLIGHT_LIMIT_EXCEEDED"));
        firstLease.close();
        assertTrue(service.inspect(session(), run(), firstAudit).allowed());
    }

    @Test
    void shouldOpenCircuitAfterConsecutiveTargetServiceFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-04T00:00:00Z"));
        AgentToolRuntimeProtectionProperties properties = baseProperties();
        properties.setConsecutiveFailureThreshold(2);
        properties.setCircuitOpenSeconds(30L);
        AgentToolRuntimeProtectionService service = new AgentToolRuntimeProtectionService(properties, clock);
        AgentToolExecutionAuditRecord audit = audit("audit-001", "data-quality", 10L);

        AgentToolRuntimeProtectionLease firstLease = service.beginExecution(session(), run(), audit);
        firstLease.recordFailure("DEPENDENCY_TIMEOUT", "data-quality timeout");
        firstLease.close();
        assertTrue(service.inspect(session(), run(), audit).allowed());

        AgentToolRuntimeProtectionLease secondLease = service.beginExecution(session(), run(), audit);
        secondLease.recordFailure("TOOL_ADAPTER_EXCEPTION", "data-quality unavailable");
        secondLease.close();
        AgentToolRuntimeProtectionVerdict openCircuit = service.inspect(session(), run(), audit);

        assertFalse(openCircuit.allowed());
        assertTrue(openCircuit.circuitOpen());
        assertTrue(openCircuit.issueCodes().contains("TARGET_SERVICE_CIRCUIT_OPEN"));

        clock.advanceSeconds(31);
        AgentToolRuntimeProtectionVerdict afterCooldown = service.inspect(session(), run(), audit);
        assertTrue(afterCooldown.allowed());
        assertFalse(afterCooldown.circuitOpen());
    }

    @Test
    void shouldIgnoreBusinessFailuresForCircuitBreaker() {
        AgentToolRuntimeProtectionProperties properties = baseProperties();
        properties.setConsecutiveFailureThreshold(1);
        AgentToolRuntimeProtectionService service = new AgentToolRuntimeProtectionService(properties);
        AgentToolExecutionAuditRecord audit = audit("audit-001", "task-management", 10L);

        AgentToolRuntimeProtectionLease lease = service.beginExecution(session(), run(), audit);
        lease.recordFailure("BUSINESS_STATE_CONFLICT", "audit state changed");
        lease.close();
        AgentToolRuntimeProtectionVerdict verdict = service.inspect(session(), run(), audit);

        assertTrue(verdict.allowed());
        assertFalse(verdict.circuitOpen());
        assertEquals(0, verdict.consecutiveFailures());
    }

    private AgentToolRuntimeProtectionProperties baseProperties() {
        AgentToolRuntimeProtectionProperties properties = new AgentToolRuntimeProtectionProperties();
        properties.setEnabled(true);
        properties.setMaxGlobalInFlight(10);
        properties.setMaxTenantInFlight(5);
        properties.setMaxTargetServiceInFlight(5);
        properties.setCircuitBreakerEnabled(true);
        properties.setConsecutiveFailureThreshold(3);
        properties.setCircuitOpenSeconds(60L);
        return properties;
    }

    private AgentSessionRecord session() {
        return new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                null,
                "u-001",
                "WEB",
                "运行时保护测试",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
    }

    private AgentRunRecord run() {
        return new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "运行时保护测试",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "测试运行"
        );
    }

    private AgentToolExecutionAuditRecord audit(String auditId, String targetService, Long tenantId) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-001",
                "run-001",
                "binding-001",
                "datasource.metadata.read",
                "DATASOURCE_METADATA",
                targetService,
                "/datasources/{datasourceId}/metadata/discover",
                1001L,
                tenantId,
                20L,
                null,
                "u-001",
                "LOW",
                "SYNC",
                false,
                true,
                true,
                List.of("VIEW"),
                "运行时保护测试工具计划",
                Map.of("datasourceId", 1001L),
                Map.of(),
                Map.of(),
                AgentToolExecutionState.PLANNED,
                "trace-runtime-protection",
                "运行时保护测试",
                LocalDateTime.now()
        );
    }

    /**
     * 可手动推进的测试时钟。
     *
     * <p>熔断冷却测试不能依赖 Thread.sleep，否则测试会慢且不稳定。
     * 自定义 Clock 让我们可以在毫秒内验证“冷却期内拒绝、冷却期后恢复”的时间语义。</p>
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
