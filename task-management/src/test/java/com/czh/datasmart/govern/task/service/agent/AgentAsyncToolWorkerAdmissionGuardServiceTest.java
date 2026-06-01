/**
 * @Author : Cui
 * @Date: 2026/06/01 22:13
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerAdmissionGuardServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 异步工具 worker 入场保护测试。
 *
 * <p>这里不验证任务业务执行，而是验证 worker 在 claim 任务之前的本地保护阀：
 * 并发满载时必须拒绝新的 dispatch，最小调度间隔未到时也必须拒绝，从而保护数据库 claim、
 * agent-runtime confirmation 回查、permission-admin evaluate 和下游真实工具不被过快打爆。</p>
 */
class AgentAsyncToolWorkerAdmissionGuardServiceTest {

    @Test
    void shouldRejectWhenLocalConcurrencyLimitIsReached() {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setMaxLocalConcurrentExecutions(1);
        AgentAsyncToolWorkerAdmissionGuardService guardService = new AgentAsyncToolWorkerAdmissionGuardService(properties);
        TaskActorContext actorContext = actorContext();
        AgentAsyncToolWorkerAdmissionLease first = guardService.tryAcquire(actorContext);
        try {
            assertTrue(first.acquired());

            AgentAsyncToolWorkerAdmissionLease second = guardService.tryAcquire(actorContext);

            assertFalse(second.acquired());
            assertEquals("LOCAL_CONCURRENCY_LIMIT", second.reasonCode());
        } finally {
            first.close();
        }

        AgentAsyncToolWorkerAdmissionLease third = guardService.tryAcquire(actorContext);
        try {
            assertTrue(third.acquired());
        } finally {
            third.close();
        }
    }

    @Test
    void shouldRejectWhenMinimumDispatchIntervalHasNotElapsed() {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setMaxLocalConcurrentExecutions(1);
        properties.setMinDispatchIntervalMs(60_000L);
        AgentAsyncToolWorkerAdmissionGuardService guardService = new AgentAsyncToolWorkerAdmissionGuardService(properties);
        TaskActorContext actorContext = actorContext();
        AgentAsyncToolWorkerAdmissionLease first = guardService.tryAcquire(actorContext);
        first.close();

        AgentAsyncToolWorkerAdmissionLease second = guardService.tryAcquire(actorContext);

        assertFalse(second.acquired());
        assertEquals("LOCAL_RATE_LIMIT", second.reasonCode());
        assertTrue(((Number) second.diagnostics().get("waitMillis")).longValue() > 0L);
    }

    private TaskActorContext actorContext() {
        return new TaskActorContext(10L, null, "SERVICE_ACCOUNT", "trace-admission", "PLATFORM", List.of());
    }
}
