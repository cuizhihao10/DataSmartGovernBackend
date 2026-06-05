/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentA2aPublicSkillView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card 中公开的 Skill 条目。
 *
 * <p>A2A Skill 是“外部 Agent 可以理解的能力描述”，不是 DataSmart 内部 Skill 的完整治理事实。
 * 因此它只包含 id、name、description、tags、examples、输入输出模态和安全要求。
 * 风险、审批、工具依赖、记忆依赖等内部治理细节不会直接散落到公开卡片里，而是通过安全要求和受控 task
 * endpoint 在真实调用时重新判定。</p>
 *
 * @param id Skill 唯一标识，使用 DataSmart skillCode 便于跨系统追踪
 * @param name Skill 展示名称
 * @param description 低敏能力说明
 * @param tags 低敏标签，通常来自治理域和触发关键词
 * @param examples 低敏示例意图，不包含客户数据、prompt 正文或工具参数值
 * @param inputModes 该 Skill 支持的输入 MIME 类型
 * @param outputModes 该 Skill 支持的输出 MIME 类型
 * @param securityRequirements 该 Skill 需要的安全要求，权限 scope 由 permission-admin 后续解释
 */
public record AgentA2aPublicSkillView(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes,
        List<Map<String, List<String>>> securityRequirements
) {
}
