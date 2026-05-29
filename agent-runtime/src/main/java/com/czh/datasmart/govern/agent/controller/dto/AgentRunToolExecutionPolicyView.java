/**
 * @Author : Cui
 * @Date: 2026/05/29 18:44
 * @Description DataSmart Govern Backend - AgentRunToolExecutionPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Run 级工具执行策略预检视图。
 *
 * <p>它回答的是“本次 Agent Run 的全部工具计划整体处于什么推进状态”。真实商业化 Agent 不能只看单个工具：
 * 一个工具等待审批会让整次 Run 进入等待；一个非幂等失败可能阻断后续工具；多个同步只读工具可以合并进入自动执行批次；
 * 异步工具则应由任务队列或调度中心接管。因此需要一个 Run 级汇总视图，而不只是返回工具列表。
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param runState 当前 Run 状态。
 * @param runTerminal Run 是否已经是终态；终态 Run 不允许继续自动执行工具。
 * @param totalTools 本次 Run 中的工具计划数量。
 * @param autoExecutableCount 当前可以进入同步自动执行候选的工具数量。
 * @param humanActionCount 需要人工审批、补参数或人工复核的工具数量。
 * @param blockingCount 会阻塞 Run 自动继续推进的工具数量。
 * @param hasExecutableTools 是否存在至少一个可自动执行候选。
 * @param hasHumanActions 是否存在人工动作。
 * @param blocksRun 当前策略是否认为 Run 不能继续自动推进。
 * @param summaryReasons Run 级汇总原因，方便前端直接展示“为什么停住/为什么可执行”。
 * @param recommendedActions Run 级推荐动作，例如先审批、先补参数、触发异步执行器或进入二轮推理。
 * @param items 每个工具的策略预检明细。
 */
public record AgentRunToolExecutionPolicyView(String sessionId,
                                              String runId,
                                              String runState,
                                              Boolean runTerminal,
                                              Integer totalTools,
                                              Integer autoExecutableCount,
                                              Integer humanActionCount,
                                              Integer blockingCount,
                                              Boolean hasExecutableTools,
                                              Boolean hasHumanActions,
                                              Boolean blocksRun,
                                              List<String> summaryReasons,
                                              List<String> recommendedActions,
                                              List<AgentRunToolExecutionPolicyItemView> items) {
}
