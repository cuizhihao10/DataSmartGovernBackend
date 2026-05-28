/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - IngestedAgentPlanView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Python AgentPlan 接入后的 Java 控制面视图。
 *
 * <p>该响应把一次接入动作拆成三个层次返回：
 * 1. `session`：Agent 在哪个租户、项目、工作空间、操作者边界内工作；
 * 2. `run`：Java 控制面为这次 Python 计划创建的受控运行记录；
 * 3. `toolAudits`：每个 ToolPlan 对应的工具审计计划，后续审批和执行都围绕这些 auditId 展开。
 *
 * <p>`controlPlaneNotes` 是学习和排障说明，不是机器执行指令。
 * 它帮助开发者和后续前端理解：为什么接入后不会自动执行工具、哪些边界仍由 Java 控制面把关。
 *
 * @param session 会话视图。
 * @param run 运行视图。
 * @param toolAudits 工具计划审计列表。
 * @param controlPlaneNotes 控制面说明与后续动作提示。
 */
public record IngestedAgentPlanView(AgentSessionView session,
                                    AgentRunView run,
                                    List<AgentToolExecutionAuditView> toolAudits,
                                    List<String> controlPlaneNotes) {
}
