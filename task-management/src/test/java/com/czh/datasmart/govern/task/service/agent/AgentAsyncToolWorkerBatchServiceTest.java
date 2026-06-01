/**
 * @Author : Cui
 * @Date: 2026/05/31 20:20
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerBatchServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 异步工具 worker 批处理服务测试。
 *
 * <p>这里不重复验证单条任务如何执行，而是验证批处理层是否遵守“单轮上限”和“无任务提前停止”。
 * 这两个规则是后台 worker 进入生产前的容量保护底线。</p>
 */
class AgentAsyncToolWorkerBatchServiceTest {

    @Test
    void shouldStopBatchWhenNoTaskIsReturned() {
        AgentAsyncToolDispatchOnceService dispatchOnceService = mock(AgentAsyncToolDispatchOnceService.class);
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setMaxDispatchesPerTick(5);
        properties.setStopBatchOnNoTask(true);
        AgentAsyncToolWorkerBatchService batchService = new AgentAsyncToolWorkerBatchService(dispatchOnceService, properties);
        when(dispatchOnceService.dispatchOnce(any(TaskActorContext.class)))
                .thenReturn(new AgentAsyncToolDispatchOnceResult(false, null, null, null, "NO_TASK", "无可领取任务", Map.of()));

        AgentAsyncToolWorkerBatchResult result = batchService.dispatchBatch(actorContext());

        assertEquals(1, result.attempted());
        assertEquals(0, result.claimed());
        assertEquals(1, result.noTask());
        assertTrue(result.stoppedByNoTask());
        verify(dispatchOnceService, times(1)).dispatchOnce(any(TaskActorContext.class));
    }

    @Test
    void shouldRespectMaxDispatchesPerTick() {
        AgentAsyncToolDispatchOnceService dispatchOnceService = mock(AgentAsyncToolDispatchOnceService.class);
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setMaxDispatchesPerTick(2);
        properties.setStopBatchOnNoTask(false);
        AgentAsyncToolWorkerBatchService batchService = new AgentAsyncToolWorkerBatchService(dispatchOnceService, properties);
        when(dispatchOnceService.dispatchOnce(any(TaskActorContext.class)))
                .thenReturn(new AgentAsyncToolDispatchOnceResult(true, 1L, 11L, "data-sync.execute", "COMPLETED", "完成", Map.of()))
                .thenReturn(new AgentAsyncToolDispatchOnceResult(true, 2L, 12L, "data-sync.execute", "DEFERRED", "退避", Map.of()));

        AgentAsyncToolWorkerBatchResult result = batchService.dispatchBatch(actorContext());

        assertEquals(2, result.attempted());
        assertEquals(2, result.claimed());
        assertEquals(1, result.completed());
        assertEquals(1, result.deferred());
        verify(dispatchOnceService, times(2)).dispatchOnce(any(TaskActorContext.class));
    }

    @Test
    void shouldStopBatchWhenCapacityGuardRejectsDispatch() {
        AgentAsyncToolDispatchOnceService dispatchOnceService = mock(AgentAsyncToolDispatchOnceService.class);
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setMaxDispatchesPerTick(5);
        AgentAsyncToolWorkerBatchService batchService = new AgentAsyncToolWorkerBatchService(dispatchOnceService, properties);
        when(dispatchOnceService.dispatchOnce(any(TaskActorContext.class)))
                .thenReturn(new AgentAsyncToolDispatchOnceResult(false, null, null, null,
                        AgentAsyncToolDispatchOnceService.OUTCOME_CAPACITY_LIMITED, "本地容量不足", Map.of()));

        AgentAsyncToolWorkerBatchResult result = batchService.dispatchBatch(actorContext());

        assertEquals(1, result.attempted());
        assertEquals(0, result.claimed());
        assertEquals(0, result.noTask());
        assertEquals(1, result.capacityLimited());
        assertTrue(result.stoppedByCapacityLimit());
        verify(dispatchOnceService, times(1)).dispatchOnce(any(TaskActorContext.class));
    }

    private TaskActorContext actorContext() {
        return new TaskActorContext(null, null, "SERVICE_ACCOUNT", "trace-worker-test", "PLATFORM", List.of());
    }
}
