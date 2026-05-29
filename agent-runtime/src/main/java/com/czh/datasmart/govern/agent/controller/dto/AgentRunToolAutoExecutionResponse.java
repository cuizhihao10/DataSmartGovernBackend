/**
 * @Author : Cui
 * @Date: 2026/05/29 22:07
 * @Description DataSmart Govern Backend - AgentRunToolAutoExecutionResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Run 级同步工具自动执行响应。
 *
 * <p>响应中保留执行前的 policy 快照，是为了让调用方看到本批次执行决策的来源。
 * 如果执行过程中某些工具状态发生变化，结果项会记录实际执行结果或失败原因；后续调用方可再次查询 policy 获取最新状态。</p>
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param dryRun 是否为演算模式。
 * @param requestedLimit 调用方请求的最大执行数量。
 * @param effectiveLimit 服务端配置与请求共同计算后的有效上限。
 * @param executedCount 成功执行并进入 SUCCEEDED 的工具数量。
 * @param failedCount 本批次尝试执行但最终 FAILED 或执行入口异常的数量。
 * @param skippedCount 本批次未执行的工具数量。
 * @param policy 执行前 policy 快照。
 * @param items 每个工具的批次处理结果。
 */
public record AgentRunToolAutoExecutionResponse(String sessionId,
                                                String runId,
                                                Boolean dryRun,
                                                Integer requestedLimit,
                                                Integer effectiveLimit,
                                                Integer executedCount,
                                                Integer failedCount,
                                                Integer skippedCount,
                                                AgentRunToolExecutionPolicyView policy,
                                                List<AgentRunToolAutoExecutionItemView> items) {
}
