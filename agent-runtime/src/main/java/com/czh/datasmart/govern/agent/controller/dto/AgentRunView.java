/**
 * @Author : Cui
 * @Date: 2026/05/13 22:43
 * @Description DataSmart Govern Backend - AgentRunView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行视图。
 *
 * <p>运行视图会返回状态、输入摘要、工作负载、下一步建议和结构化变量。
 * 当前版本还没有真实编排器，因此 nextActions 用来告诉调用方“下一步应接入什么能力”，
 * 等后续接入 LangGraph/OpenClaw Runtime 后，nextActions 可以替换为真实节点计划或工具调用计划。
 */
public record AgentRunView(String runId,
                           String sessionId,
                           String state,
                           String workloadType,
                           String userInputPreview,
                           Boolean dryRun,
                           Boolean requireHumanApproval,
                           List<String> nextActions,
                           Map<String, Object> variables,
                           LocalDateTime createTime,
                           LocalDateTime updateTime,
                           LocalDateTime finishTime,
                           String message) {
}
