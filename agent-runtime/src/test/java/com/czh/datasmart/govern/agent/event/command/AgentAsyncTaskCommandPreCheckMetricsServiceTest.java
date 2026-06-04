/**
 * @Author : Cui
 * @Date: 2026/06/04 01:09
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandPreCheckMetricsServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.service.execution.AgentAsyncTaskCommandPreCheckVerdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 异步 command pre-check 指标测试。
 *
 * <p>这类测试不只验证 Counter 是否加一，更重要的是固定“低基数标签契约”：
 * Prometheus 可以承受 decision、issueCode、targetService 这类有限枚举；
 * 不能承受 commandId、runId、traceId、tenantId 这类随业务请求无限增长的标签。</p>
 */
class AgentAsyncTaskCommandPreCheckMetricsServiceTest {

    @Test
    void allowedVerdictShouldRecordOnlyVerdictCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentAsyncTaskCommandPreCheckMetricsService service =
                new AgentAsyncTaskCommandPreCheckMetricsService(meterRegistry);

        service.recordVerdict(record("cmd-allowed", "data-sync"), verdict("cmd-allowed",
                true,
                "ALLOW_EXECUTION",
                List.of()));

        assertEquals(1.0, meterRegistry.counter("datasmart_agent_runtime_async_command_precheck_verdict_total",
                "decision", "ALLOW_EXECUTION",
                "targetService", "data-sync").count());
        assertTrue(meterRegistry.find("datasmart_agent_runtime_async_command_precheck_issue_total")
                .counters()
                .isEmpty());
    }

    @Test
    void blockedVerdictShouldNormalizeUnknownLabelsToOther() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentAsyncTaskCommandPreCheckMetricsService service =
                new AgentAsyncTaskCommandPreCheckMetricsService(meterRegistry);

        service.recordVerdict(record("cmd-blocked", "customer-dynamic-service"), verdict("cmd-blocked",
                false,
                "BLOCKED",
                List.of("CUSTOMER_DYNAMIC_REASON")));

        assertEquals(1.0, meterRegistry.counter("datasmart_agent_runtime_async_command_precheck_verdict_total",
                "decision", "BLOCKED",
                "targetService", "OTHER").count());
        assertEquals(1.0, meterRegistry.counter("datasmart_agent_runtime_async_command_precheck_issue_total",
                "decision", "BLOCKED",
                "issueCode", "OTHER",
                "targetService", "OTHER").count());
        assertNull(meterRegistry.find("datasmart_agent_runtime_async_command_precheck_verdict_total")
                .tag("commandId", "cmd-blocked")
                .counter());
    }

    @Test
    void deferredVerdictShouldRecordStableIssueCodeDistribution() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentAsyncTaskCommandPreCheckMetricsService service =
                new AgentAsyncTaskCommandPreCheckMetricsService(meterRegistry);

        service.recordVerdict(record("cmd-deferred", "task-management"), verdict("cmd-deferred",
                false,
                "DEFERRED",
                List.of("RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER")));

        assertEquals(1.0, meterRegistry.counter("datasmart_agent_runtime_async_command_precheck_verdict_total",
                "decision", "DEFERRED",
                "targetService", "task-management").count());
        assertEquals(1.0, meterRegistry.counter("datasmart_agent_runtime_async_command_precheck_issue_total",
                "decision", "DEFERRED",
                "issueCode", "RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER",
                "targetService", "task-management").count());
    }

    private AgentAsyncTaskCommandPreCheckVerdict verdict(String commandId,
                                                         boolean allowed,
                                                         String decision,
                                                         List<String> issueCodes) {
        return new AgentAsyncTaskCommandPreCheckVerdict(
                commandId,
                "audit-metrics",
                "confirmation-metrics",
                allowed,
                decision,
                "WAITING_ASYNC_EXECUTOR",
                true,
                true,
                "CONFIRMED",
                Instant.now().plusSeconds(300),
                issueCodes,
                List.of("测试原因"),
                List.of("测试建议")
        );
    }

    private AgentAsyncTaskCommandOutboxRecord record(String commandId, String targetService) {
        Instant now = Instant.now();
        return new AgentAsyncTaskCommandOutboxRecord(
                "async-command-outbox:" + commandId,
                commandId,
                "agent-tool-async:session:run:" + commandId,
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ASYNC_TASK_REQUESTED",
                "run-metrics",
                "datasmart.agent.tool.async.commands",
                "task-management",
                "session-metrics",
                "run-metrics",
                "audit-metrics",
                "data-sync.execute",
                targetService,
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-metrics",
                "trace-metrics",
                "agent-tool-audit://session/run/audit/plan-arguments",
                AgentAsyncTaskCommandOutboxStatus.PENDING,
                0,
                now,
                now,
                null,
                null,
                "",
                128,
                false,
                "{\"schemaVersion\":\"datasmart.agent.async-task-command.v1\"}"
        );
    }
}
