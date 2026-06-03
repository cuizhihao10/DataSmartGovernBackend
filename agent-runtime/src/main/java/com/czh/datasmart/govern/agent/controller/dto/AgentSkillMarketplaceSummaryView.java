/**
 * @Author : Cui
 * @Date: 2026/06/03 19:56
 * @Description DataSmart Govern Backend - AgentSkillMarketplaceSummaryView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Skill 市场治理摘要视图。
 *
 * <p>该视图面向“产品化 Skill Marketplace”的第一阶段读侧能力：
 * 1. 前端可以直接用它渲染市场首页的能力概览、筛选器和风险提示；
 * 2. Python Runtime 可以在启动诊断或远程注册表健康检查中判断 Skill 目录是否过于危险或过于稀疏；
 * 3. 运营/管理员可以看到高风险、需审批、需审计、租户/项目隔离等治理维度是否覆盖充分。
 *
 * <p>为什么不直接让调用方自行统计 descriptor 列表？
 * descriptor 列表是“单个 Skill 的事实”，市场摘要是“整个平台能力面的治理事实”。商业化产品中，
 * 这类统计口径应该由 Java 控制面统一输出，才能保证前端、Python Runtime、审计报表和后续租户策略
 * 使用同一套定义。后续当 Skill 注册表迁移到数据库、支持版本发布和租户启停时，该 API 契约仍可保持稳定。
 *
 * @param schemaVersion 市场摘要 schema 版本，便于后续新增字段时做兼容判断
 * @param registrySkillCount 注册表中的 Skill 总数，始终包含禁用 Skill，用于判断目录规模
 * @param visibleSkillCount 本次摘要实际纳入统计的 Skill 数量，受 includeDisabled 参数影响
 * @param enabledSkillCount 本次摘要中已启用 Skill 数量
 * @param disabledSkillCount 本次摘要中已禁用 Skill 数量
 * @param highRiskSkillCount 本次摘要中 HIGH 风险 Skill 数量
 * @param approvalRequiredSkillCount 本次摘要中需要审批或复核的 Skill 数量
 * @param auditRequiredSkillCount 本次摘要中声明必须审计的 Skill 数量
 * @param tenantScopedSkillCount 本次摘要中声明租户隔离的 Skill 数量
 * @param projectScopedSkillCount 本次摘要中声明项目隔离的 Skill 数量
 * @param domainFacets 按治理领域聚合的筛选维度
 * @param riskLevelFacets 按风险等级聚合的筛选维度
 * @param approvalPolicyFacets 按审批策略聚合的筛选维度
 * @param memoryDependencyFacets 按记忆依赖类型聚合的筛选维度
 * @param operationalWarnings 面向管理员的治理风险提示，属于低敏运营信息
 * @param recommendedActions 面向后续产品建设的推荐动作，用于持续补齐商业化能力
 */
public record AgentSkillMarketplaceSummaryView(String schemaVersion,
                                               long registrySkillCount,
                                               long visibleSkillCount,
                                               long enabledSkillCount,
                                               long disabledSkillCount,
                                               long highRiskSkillCount,
                                               long approvalRequiredSkillCount,
                                               long auditRequiredSkillCount,
                                               long tenantScopedSkillCount,
                                               long projectScopedSkillCount,
                                               List<AgentSkillMarketplaceFacetView> domainFacets,
                                               List<AgentSkillMarketplaceFacetView> riskLevelFacets,
                                               List<AgentSkillMarketplaceFacetView> approvalPolicyFacets,
                                               List<AgentSkillMarketplaceFacetView> memoryDependencyFacets,
                                               List<String> operationalWarnings,
                                               List<String> recommendedActions) {
}
