/**
 * @Author : Cui
 * @Date: 2026/05/23 21:37
 * @Description DataSmart Govern Backend - AgentSkillDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Skill 标准化描述符。
 *
 * <p>该描述符面向 Python AI Runtime、智能网关、前端能力选择器和未来 Skill 市场。
 * 它借鉴 A2A Agent Card 和 MCP prompt/resource 的思路，但保持 DataSmart 内部可控：
 * 先描述 Skill 的能力、工具依赖、记忆依赖、审批和审计边界，再决定是否对外暴露完整协议。
 *
 * @param schemaVersion DataSmart Skill 描述 schema 版本
 * @param descriptorType 描述类型，当前固定为 DATASMART_AGENT_SKILL
 * @param protocolHint 协议提示，当前为 AGENT_CARD_STYLE，表示接近 Agent Card 能力描述但不是完整 A2A 协议
 * @param skillCode 稳定 Skill 编码
 * @param displayName 展示名称
 * @param description Skill 说明
 * @param domain 归属治理域
 * @param requiredTools 依赖工具编码
 * @param requiredPermissions 需要的平台权限
 * @param triggerKeywords 规则式选择关键词
 * @param examples 示例目标
 * @param governance 治理策略
 * @param memory 记忆策略
 */
public record AgentSkillDescriptorView(String schemaVersion,
                                       String descriptorType,
                                       String protocolHint,
                                       String skillCode,
                                       String displayName,
                                       String description,
                                       String domain,
                                       List<String> requiredTools,
                                       List<String> requiredPermissions,
                                       List<String> triggerKeywords,
                                       List<String> examples,
                                       AgentSkillGovernanceDescriptorView governance,
                                       AgentSkillMemoryDescriptorView memory) {
}
