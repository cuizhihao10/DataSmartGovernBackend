/**
 * @Author : Cui
 * @Date: 2026/05/23 20:31
 * @Description DataSmart Govern Backend - AgentToolGovernanceDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具治理描述。
 *
 * <p>该视图把商业化 Agent 工具最关键的治理字段放在一起：
 * 是否启用、是否只读、风险等级、是否审批、租户/项目边界、允许动作和敏感字段。
 * 这些字段会被智能网关、Python Runtime、审批系统和审计系统共同消费。
 *
 * @param enabled 工具是否启用；禁用工具不能被新会话绑定或执行
 * @param readOnly 是否只读；只读不代表无风险，还要结合 riskLevel 和 sensitiveFields 判断
 * @param riskLevel 风险等级，决定是否需要审批、是否可自动执行、是否需要更严格审计
 * @param requiresApproval 是否必须审批
 * @param tenantScoped 是否必须绑定租户上下文
 * @param projectScoped 是否必须绑定项目上下文或授权项目集合
 * @param allowedActions 工具允许动作，例如 VIEW、GENERATE、CREATE
 * @param sensitiveFields 输入参数中的敏感字段名，用于脱敏、审批提示和审计压缩
 */
public record AgentToolGovernanceDescriptorView(Boolean enabled,
                                                Boolean readOnly,
                                                String riskLevel,
                                                Boolean requiresApproval,
                                                Boolean tenantScoped,
                                                Boolean projectScoped,
                                                List<String> allowedActions,
                                                List<String> sensitiveFields) {
}
