/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionReadinessDecisionSummaryView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 工具执行准备度中的单个工具决策摘要。
 *
 * <p>Python Runtime 5.36 会把每个 ToolPlan 的执行前状态压缩为 decision summary。Java 控制面只消费
 * 这些低敏字段，用于展示“某个工具为什么可执行、等待审批、等待澄清、仅展示草案或被限流”。</p>
 *
 * <p>安全边界：该 DTO 只包含工具名、决策、布尔标记、issue/reason code、敏感字段名和 retry hint。
 * 它不包含工具参数值、SQL、prompt、样本数据、任务 payload 明细、模型输出、凭证或内部 endpoint。</p>
 */
public record AgentToolExecutionReadinessDecisionSummaryView(
        String toolName,
        String decision,
        Boolean executable,
        Boolean queueRequired,
        Boolean requiresHumanApproval,
        List<String> reasonCodes,
        List<String> issueCodes,
        Integer parameterIssueCount,
        List<String> sensitiveArgumentNames,
        String retryHint
) {
}
