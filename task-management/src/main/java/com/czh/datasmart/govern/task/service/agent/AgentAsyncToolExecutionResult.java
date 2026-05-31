/**
 * @Author : Cui
 * @Date: 2026/05/31 23:35
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 异步工具适配器的统一执行结果。
 *
 * <p>task-management 不应该把每个业务服务的响应类型直接塞进任务生命周期逻辑里，否则后续接入 data-quality、
 * datasource-management、compliance-masking 或外部审批系统时，worker 会变成一个越来越大的 if/else Impl。
 * 该结果对象把“是否成功、是否建议延迟重试、下游业务引用、可展示摘要”抽象出来，让任务回调层保持稳定。</p>
 */
public record AgentAsyncToolExecutionResult(
        boolean success,
        boolean retryable,
        String message,
        Map<String, Object> output
) {

    public AgentAsyncToolExecutionResult {
        output = output == null ? Map.of() : new LinkedHashMap<>(output);
    }

    public static AgentAsyncToolExecutionResult success(String message, Map<String, Object> output) {
        return new AgentAsyncToolExecutionResult(true, false, message, output);
    }

    public static AgentAsyncToolExecutionResult retryableFailure(String message, Map<String, Object> output) {
        return new AgentAsyncToolExecutionResult(false, true, message, output);
    }

    public static AgentAsyncToolExecutionResult fatalFailure(String message, Map<String, Object> output) {
        return new AgentAsyncToolExecutionResult(false, false, message, output);
    }
}
