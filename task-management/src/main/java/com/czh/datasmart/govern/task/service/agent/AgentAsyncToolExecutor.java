/**
 * @Author : Cui
 * @Date: 2026/05/31 23:35
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutor.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * Agent 异步工具白名单适配器。
 *
 * <p>该接口是 4.52 阶段的关键安全边界：worker 只能把解析后的 payload 交给明确支持某个 toolCode 的适配器，
 * 不能直接读取 payload.targetEndpoint 并发起 HTTP 调用。这样可以把每个工具的参数校验、权限复核、幂等键、
 * 下游内部 API、错误分类和结果摘要都固定在可审计的 Java 代码中。</p>
 */
public interface AgentAsyncToolExecutor {

    /**
     * 判断当前适配器是否支持指定工具编码。
     *
     * @param toolCode Agent Runtime 规划出的工具编码。
     * @return true 表示本适配器可以执行该工具。
     */
    boolean supports(String toolCode);

    /**
     * 执行已通过 payloadReference 解析和一致性预检的工具。
     *
     * @param payload 经过 task-management resolver 校验的工具参数快照。
     * @return 统一执行结果，供任务生命周期回写使用。
     */
    AgentAsyncToolExecutionResult execute(AgentAsyncToolResolvedPayload payload);
}
