/**
 * @Author : Cui
 * @Date: 2026/05/13 22:43
 * @Description DataSmart Govern Backend - AgentToolBindingView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 工具绑定视图。
 *
 * <p>该视图面向前端、审计和排障人员，用于确认当前会话到底开放了哪些能力。
 * 后续真正执行工具时，应将 bindingId 写入工具调用审计，形成“会话 -> 运行 -> 工具绑定 -> 下游调用”的证据链。
 */
public record AgentToolBindingView(String bindingId,
                                   String toolCode,
                                   String toolType,
                                   String displayName,
                                   String targetService,
                                   String targetEndpoint,
                                   Long targetResourceId,
                                   Boolean readOnly,
                                   String riskLevel,
                                   String executionMode,
                                   Boolean requiresApproval,
                                   Boolean idempotent,
                                   String status,
                                   List<String> allowedActions,
                                   LocalDateTime createTime) {
}
