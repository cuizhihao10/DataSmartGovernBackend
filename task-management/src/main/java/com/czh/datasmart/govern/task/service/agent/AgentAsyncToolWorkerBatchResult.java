/**
 * @Author : Cui
 * @Date: 2026/05/31 20:18
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerBatchResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 异步工具后台 worker 单轮批处理结果。
 *
 * <p>后台调度器不是只要“执行一下”就结束，它需要向日志、指标、运维面板或后续告警系统暴露本轮处理了多少任务、
 * 是否因为无任务提前停止、是否发生异常。这个 record 先固定最小诊断契约，后续可以自然接入 Micrometer 指标、
 * task-management 管理接口或 observability 告警。</p>
 *
 * @param attempted 本轮实际尝试 dispatch 的次数，包含 NO_TASK 这类没有领取到任务的尝试。
 * @param claimed 本轮成功领取到任务的次数。
 * @param completed 本轮成功完成的任务数。
 * @param deferred 本轮退避回队列的任务数。
 * @param failed 本轮标记失败的任务数。
 * @param noTask 本轮遇到没有可领取任务的次数。
 * @param stoppedByNoTask 是否因为无任务而提前停止本轮。
 * @param results 每次 dispatch-once 的明细结果，便于测试和后续诊断。
 * @param startedAt 本轮开始时间。
 * @param finishedAt 本轮结束时间。
 */
public record AgentAsyncToolWorkerBatchResult(
        int attempted,
        int claimed,
        int completed,
        int deferred,
        int failed,
        int noTask,
        boolean stoppedByNoTask,
        List<AgentAsyncToolDispatchOnceResult> results,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
