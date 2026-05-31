/**
 * @Author : Cui
 * @Date: 2026/05/31 20:18
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerBatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 异步工具 worker 批处理服务。
 *
 * <p>该服务把“单次 dispatch-once”包装成“单轮最多处理 N 条任务”的批处理能力。
 * 之所以单独拆出来，而不是把循环直接写在 scheduler 里，是为了保持职责清晰：</p>
 * <p>1. `AgentAsyncToolDispatchOnceService` 只负责一条任务的认领、预检、执行和状态回写；</p>
 * <p>2. `AgentAsyncToolWorkerBatchService` 负责一轮最多执行几次、遇到 NO_TASK 是否停止、如何汇总结果；</p>
 * <p>3. `AgentAsyncToolWorkerScheduler` 只负责定时触发和并发防重入。</p>
 *
 * <p>这种拆分能避免未来把并发池、租户配额、工具限流、心跳续租和指标统计全部堆进一个巨大 Impl 文件。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncToolWorkerBatchService {

    private final AgentAsyncToolDispatchOnceService dispatchOnceService;
    private final AgentAsyncToolWorkerProperties properties;

    /**
     * 执行一轮后台 worker 批处理。
     *
     * @param actorContext 后台 worker 使用的服务账号上下文。
     * @return 本轮执行摘要。
     */
    public AgentAsyncToolWorkerBatchResult dispatchBatch(TaskActorContext actorContext) {
        LocalDateTime startedAt = LocalDateTime.now();
        int maxDispatches = Math.max(1, properties.getMaxDispatchesPerTick());
        List<AgentAsyncToolDispatchOnceResult> results = new ArrayList<>();
        int claimed = 0;
        int completed = 0;
        int deferred = 0;
        int failed = 0;
        int noTask = 0;
        boolean stoppedByNoTask = false;

        for (int index = 0; index < maxDispatches; index++) {
            AgentAsyncToolDispatchOnceResult result = dispatchOnceService.dispatchOnce(actorContext);
            results.add(result);
            if (!result.claimed()) {
                noTask++;
                if (properties.isStopBatchOnNoTask()) {
                    stoppedByNoTask = true;
                    break;
                }
                continue;
            }
            claimed++;
            if ("COMPLETED".equals(result.outcome())) {
                completed++;
            } else if ("DEFERRED".equals(result.outcome())) {
                deferred++;
            } else if ("FAILED".equals(result.outcome())) {
                failed++;
            }
        }

        return new AgentAsyncToolWorkerBatchResult(
                results.size(),
                claimed,
                completed,
                deferred,
                failed,
                noTask,
                stoppedByNoTask,
                List.copyOf(results),
                startedAt,
                LocalDateTime.now()
        );
    }
}
