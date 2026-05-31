/**
 * @Author : Cui
 * @Date: 2026/05/31 14:21
 * @Description DataSmart Govern Backend - AgentRunAsyncTaskCommandPlanView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 某次 Agent Run 的异步工具命令规划视图。
 *
 * <p>它不是 Kafka 投递结果，也不是 task-management 任务创建结果，而是异步任务化之前的只读 preflight。
 * 调用方可以用它回答：本次 Run 有多少个 ASYNC_TASK、哪些可以进入 dispatcher、哪些因审批、参数、
 * 终态或非幂等风险被阻断，以及未来应向哪个 topic/消费者发送命令。</p>
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param totalAsyncTools 当前 Run 中声明 executionMode=ASYNC_TASK 的工具数量。
 * @param dispatchableCount 可以进入后续 dispatcher 的命令草案数量。
 * @param blockedCount 被策略阻断的异步命令数量。
 * @param ignoredNonAsyncToolCount 因不是 ASYNC_TASK 而被忽略的工具数量。
 * @param hasDispatchableCommands 是否至少存在一个可下发草案。
 * @param summaryReasons Run 级汇总说明。
 * @param recommendedActions Run 级后续建议。
 * @param items 每个异步工具的命令草案安全视图。
 */
public record AgentRunAsyncTaskCommandPlanView(String sessionId,
                                               String runId,
                                               Integer totalAsyncTools,
                                               Integer dispatchableCount,
                                               Integer blockedCount,
                                               Integer ignoredNonAsyncToolCount,
                                               Boolean hasDispatchableCommands,
                                               List<String> summaryReasons,
                                               List<String> recommendedActions,
                                               List<AgentAsyncTaskCommandPlanItemView> items) {
}
