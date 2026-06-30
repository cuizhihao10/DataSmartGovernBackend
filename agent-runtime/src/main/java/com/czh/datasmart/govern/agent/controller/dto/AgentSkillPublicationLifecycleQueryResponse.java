/**
 * @Author : Cui
 * @Date: 2026/06/30 23:18
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Skill 发布生命周期查询响应。
 *
 * <p>该响应不仅返回列表，还聚合状态分布和下一步建议。这样管理台或诊断接口可以快速判断：
 * 草稿是否堆积、审核中是否积压、READY 能力是否足够、DEPRECATED 是否需要清理。</p>
 *
 * @param schemaVersion 响应 schema 版本
 * @param queryType 查询类型
 * @param totalMatched 当前窗口命中的发布单数量
 * @param statusCounts 当前窗口按状态聚合的数量
 * @param publications 发布单视图列表
 * @param recommendedActions 低敏运营建议
 */
public record AgentSkillPublicationLifecycleQueryResponse(
        String schemaVersion,
        String queryType,
        int totalMatched,
        Map<String, Long> statusCounts,
        List<AgentSkillPublicationLifecycleView> publications,
        List<String> recommendedActions
) {
}
