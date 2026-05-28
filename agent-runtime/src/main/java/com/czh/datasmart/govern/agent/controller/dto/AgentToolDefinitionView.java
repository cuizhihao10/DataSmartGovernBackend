/**
 * @Author : Cui
 * @Date: 2026/05/13 23:24
 * @Description DataSmart Govern Backend - AgentToolDefinitionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具定义视图。
 *
 * <p>工具定义是 Agent Runtime 的“能力目录”。
 * 它不会直接执行下游动作，而是告诉 Agent 编排器、前端和审计系统：
 * 这个工具是什么、面向哪个微服务、风险多高、是否只读、是否需要审批、需要哪些输入。
 *
 * <p>为什么这很重要：
 * 如果没有工具目录，Agent 很容易退化为“模型随意拼 URL 调接口”，这在商业化数据治理产品里不可接受。
 * 工具目录让平台先治理工具能力，再让模型在受控边界内选择工具。
 */
public record AgentToolDefinitionView(String toolCode,
                                      Boolean enabled,
                                      String toolType,
                                      String displayName,
                                      String description,
                                      String targetService,
                                      String targetEndpoint,
                                      Boolean readOnly,
                                      String riskLevel,
                                      String executionMode,
                                      Boolean requiresApproval,
                                      Boolean idempotent,
                                      Long timeoutMs,
                                      Integer maxRetries,
                                      List<String> allowedActions,
                                      List<AgentToolInputFieldView> inputSchema) {
}
