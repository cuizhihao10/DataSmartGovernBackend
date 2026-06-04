/**
 * @Author : Cui
 * @Date: 2026/06/04 18:34
 * @Description DataSmart Govern Backend - AgentSkillPublicationItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Skill 发布 Manifest 中的单个 Skill 条目。
 *
 * <p>Descriptor 是“单个 Skill 的完整事实”，Publication Item 是“把这个 Skill 下发给运行时前需要关注的发布摘要”。
 * 它刻意不复制 descriptor 的所有字段，而是抽取 Python Runtime、智能网关、前端市场页和后续 MCP 适配层最关心的
 * 发布态信息：内容指纹、发布状态、风险、审批、审计、隔离、工具依赖和记忆依赖。</p>
 *
 * <p>为什么要有 contentFingerprint？
 * 运行时不应该每次启动都盲目认为 Skill 目录变化。内容指纹可以让 Python Runtime 做轻量缓存、启动诊断、
 * 灰度对比和“远端目录是否更新”的判断。当前指纹由 Java 控制面按稳定字段计算，不包含 generatedAt，
 * 因此同一份 Skill 内容多次请求会得到相同指纹。</p>
 *
 * @param skillCode 稳定 Skill 编码，也是 Python Runtime 选择能力包时的主键
 * @param displayName 面向用户和运营台展示的名称
 * @param domain 治理域，例如 DATA_QUALITY、TASK_MANAGEMENT
 * @param publicationState 发布状态，例如 READY、DISABLED、NEEDS_APPROVAL_POLICY
 * @param contentFingerprint 单个 Skill 内容指纹，用于运行时缓存和变更检测
 * @param descriptorEndpoints 可回查完整 descriptor 的内部端点，区分 agent-runtime 原始路径和 gateway/API 路径
 * @param enabled Skill 是否启用，禁用 Skill 不应进入默认模型规划
 * @param riskLevel 风险等级，运行时和市场页可据此决定是否强提示或二次确认
 * @param approvalPolicy 审批策略，表达模型能否直接使用该 Skill
 * @param auditRequired 是否必须审计，商业化生产中高风险 Skill 应保持 true
 * @param tenantScoped 是否声明租户隔离
 * @param projectScoped 是否声明项目隔离
 * @param requiredTools 该 Skill 依赖的工具编码集合
 * @param requiredPermissions 该 Skill 需要的平台权限集合
 * @param memoryDependencies 该 Skill 需要优先检索或写入的记忆类型
 * @param publicationWarnings 发布前后需要关注的低敏风险提示
 */
public record AgentSkillPublicationItemView(
        String skillCode,
        String displayName,
        String domain,
        String publicationState,
        String contentFingerprint,
        List<String> descriptorEndpoints,
        Boolean enabled,
        String riskLevel,
        String approvalPolicy,
        Boolean auditRequired,
        Boolean tenantScoped,
        Boolean projectScoped,
        List<String> requiredTools,
        List<String> requiredPermissions,
        List<String> memoryDependencies,
        List<String> publicationWarnings
) {
}
