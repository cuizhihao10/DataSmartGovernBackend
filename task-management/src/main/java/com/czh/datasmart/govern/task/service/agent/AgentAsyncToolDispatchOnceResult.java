/**
 * @Author : Cui
 * @Date: 2026/05/31 23:42
 * @Description DataSmart Govern Backend - AgentAsyncToolDispatchOnceResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 异步工具 worker 单次调度结果。
 *
 * @param claimed 本次是否成功认领到任务。
 * @param taskId 被认领的 task-management 任务 ID。
 * @param runId 本次任务执行 run ID。
 * @param toolCode 实际执行的工具编码。
 * @param outcome 调度结果类型，例如 NO_TASK、COMPLETED、DEFERRED、FAILED。
 * @param message 面向调用方和运维人员的说明。
 * @param output 下游工具返回的结构化摘要，不能包含密钥、样本数据或完整大结果。
 */
public record AgentAsyncToolDispatchOnceResult(
        boolean claimed,
        Long taskId,
        Long runId,
        String toolCode,
        String outcome,
        String message,
        Map<String, Object> output
) {

    public AgentAsyncToolDispatchOnceResult {
        output = output == null ? Map.of() : new LinkedHashMap<>(output);
    }
}
