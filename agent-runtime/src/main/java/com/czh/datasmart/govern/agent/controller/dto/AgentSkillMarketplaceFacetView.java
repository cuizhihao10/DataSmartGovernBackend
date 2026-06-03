/**
 * @Author : Cui
 * @Date: 2026/06/03 19:56
 * @Description DataSmart Govern Backend - AgentSkillMarketplaceFacetView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent Skill 市场筛选维度统计视图。
 *
 * <p>Skill Marketplace 不只是一个“能力包列表”，它还需要给前端、运营人员和 Python Runtime
 * 提供可解释的筛选维度。例如按治理领域查看数据质量 Skill、按风险等级查看高风险 Skill、按审批策略
 * 查看哪些能力需要人工确认。Facet 的作用就是把这些维度提前聚合好，避免前端和 Python Runtime
 * 各自重复实现一套统计逻辑，导致口径漂移。
 *
 * @param facetType 维度类型，例如 DOMAIN、RISK_LEVEL、APPROVAL_POLICY、MEMORY_DEPENDENCY
 * @param value 维度值，例如 DATA_QUALITY、HIGH、HUMAN_APPROVAL_REQUIRED、EPISODIC
 * @param totalCount 当前维度值下的 Skill 总数；是否包含禁用 Skill 取决于摘要接口的 includeDisabled 参数
 * @param enabledCount 当前维度值下已启用的 Skill 数量，用于市场页展示“可立即使用”的能力规模
 * @param disabledCount 当前维度值下已禁用的 Skill 数量，用于灰度、故障下线、租户裁剪等运营排查
 */
public record AgentSkillMarketplaceFacetView(String facetType,
                                             String value,
                                             long totalCount,
                                             long enabledCount,
                                             long disabledCount) {
}
