/**
 * @Author : Cui
 * @Date: 2026/06/02 00:26
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerMetricsServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Agent 异步工具 worker 聚合指标测试。
 *
 * <p>测试重点不是 Micrometer 框架本身，而是平台指标的标签边界：
 * 已知保护原因必须保留，未知动态值必须归并为 OTHER，避免未来把异常文本或业务主键误放入标签后制造高基数时序。</p>
 */
class AgentAsyncToolWorkerMetricsServiceTest {

    @Test
    void shouldRecordBoundedDispatchAndGuardrailCounters() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentAsyncToolWorkerMetricsService service = new AgentAsyncToolWorkerMetricsService(meterRegistry);

        service.recordDispatchOutcome("CAPACITY_LIMITED");
        service.recordAdmissionRejected("LOCAL_CONCURRENCY_LIMIT");
        service.recordPreCheckRejected(AgentAsyncToolGuardrailEventSupport.CODE_PERMISSION_DENIED);
        service.recordPreCheckUnavailable(AgentAsyncToolGuardrailEventSupport.CODE_CONFIRMATION_UNAVAILABLE);

        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_worker_dispatch_total",
                "outcome", "CAPACITY_LIMITED").count());
        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_worker_guardrail_total",
                "scope", "LOCAL_JVM",
                "decision", "BLOCKED",
                "reasonCode", "LOCAL_CONCURRENCY_LIMIT").count());
        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_worker_guardrail_total",
                "scope", "EXECUTION_PRECHECK",
                "decision", "BLOCKED",
                "reasonCode", "AGENT_ASYNC_TOOL_PERMISSION_DENIED").count());
        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_worker_guardrail_total",
                "scope", "PRECHECK_DEPENDENCY",
                "decision", "DEFERRED",
                "reasonCode", "AGENT_ASYNC_TOOL_CONFIRMATION_UNAVAILABLE").count());
    }

    @Test
    void unknownDynamicValuesShouldCollapseIntoOtherTag() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentAsyncToolWorkerMetricsService service = new AgentAsyncToolWorkerMetricsService(meterRegistry);

        service.recordDispatchOutcome("task-9001-runtime-message");
        service.recordAdmissionRejected("tenant-10-custom-rate-limit");

        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_worker_dispatch_total",
                "outcome", "OTHER").count());
        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_worker_guardrail_total",
                "scope", "LOCAL_JVM",
                "decision", "BLOCKED",
                "reasonCode", "OTHER").count());
    }
}
