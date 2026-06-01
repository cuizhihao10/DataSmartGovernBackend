/**
 * @Author : Cui
 * @Date: 2026/05/31 20:20
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerSchedulerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 异步工具后台调度器测试。
 *
 * <p>调度器测试重点不是验证 Spring 的定时机制，而是验证业务开关：默认关闭时不能触发真实任务认领；
 * 只有 enabled=true、dryRunOnly=false、schedulerEnabled=true 三个条件同时满足时，才允许进入批处理服务。</p>
 */
class AgentAsyncToolWorkerSchedulerTest {

    @Test
    void shouldNotRunWhenSchedulerDisabled() {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setEnabled(true);
        properties.setDryRunOnly(false);
        properties.setSchedulerEnabled(false);
        AgentAsyncToolWorkerBatchService batchService = mock(AgentAsyncToolWorkerBatchService.class);
        AgentAsyncToolWorkerScheduler scheduler = new AgentAsyncToolWorkerScheduler(properties, batchService);

        scheduler.dispatchScheduledBatch();

        verify(batchService, never()).dispatchBatch(any(TaskActorContext.class));
    }

    @Test
    void shouldRunBatchWhenAllSafetySwitchesAllow() {
        AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
        properties.setEnabled(true);
        properties.setDryRunOnly(false);
        properties.setSchedulerEnabled(true);
        AgentAsyncToolWorkerBatchService batchService = mock(AgentAsyncToolWorkerBatchService.class);
        when(batchService.dispatchBatch(any(TaskActorContext.class))).thenReturn(new AgentAsyncToolWorkerBatchResult(
                1,
                1,
                1,
                0,
                0,
                0,
                0,
                false,
                false,
                List.of(),
                LocalDateTime.now(),
                LocalDateTime.now()
        ));
        AgentAsyncToolWorkerScheduler scheduler = new AgentAsyncToolWorkerScheduler(properties, batchService);

        scheduler.dispatchScheduledBatch();

        verify(batchService).dispatchBatch(any(TaskActorContext.class));
    }
}
