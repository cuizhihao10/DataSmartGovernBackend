/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentA2aSkillPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Agent Card 中的 Skill 映射预览。
 *
 * <p>A2A 的 Skill 用来描述某个 Agent 能完成什么能力。DataSmart 内部 Skill 是“能力包”：
 * 它通常组合工具、权限、记忆、审批和审计策略。对外映射时，我们只选择 publicationState=READY
 * 的 Skill 进入 Agent Card 预览，避免外部 Agent 发现禁用、缺审计、缺隔离或缺审批的能力。</p>
 *
 * @param skillId A2A skill id，使用 DataSmart skillCode，方便跨协议回溯
 * @param name Skill 展示名
 * @param description 能力说明，来自内部 Skill descriptor 的低敏描述
 * @param tags 低敏标签，通常来自治理域和触发关键词，用于外部 Agent 发现能力
 * @param examples 低敏示例目标，只能表达任务意图，不能包含真实客户数据或 prompt 正文
 * @param requiredToolCount 该 Skill 依赖的内部工具数量。只暴露数量，不暴露工具 endpoint 或执行参数
 * @param requiredPermissions 该 Skill 需要的权限标识，后续可映射到 A2A auth scope 或 permission-admin
 * @param riskLevel Skill 风险等级，用于决定外部协作时是否允许自动委派
 * @param approvalPolicy Skill 审批策略，用于决定 A2A task 是否需要人类确认或草稿复核
 * @param tenantScoped 是否要求租户边界
 * @param projectScoped 是否要求项目边界
 * @param supportedTaskStates 该 Skill 在 A2A 任务生命周期中预期支持的状态集合
 * @param payloadPolicy 载荷策略，说明 Agent Card 只暴露 capability，不暴露内部记忆正文、工具实参或模型输出
 */
public record AgentA2aSkillPreviewView(
        String skillId,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        int requiredToolCount,
        List<String> requiredPermissions,
        String riskLevel,
        String approvalPolicy,
        Boolean tenantScoped,
        Boolean projectScoped,
        List<String> supportedTaskStates,
        String payloadPolicy
) {
}
