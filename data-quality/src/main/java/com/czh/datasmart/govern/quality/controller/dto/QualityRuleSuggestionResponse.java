/**
 * @Author : Cui
 * @Date: 2026/05/24 22:07
 * @Description DataSmart Govern Backend - QualityRuleSuggestionResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 质量规则草案建议响应。
 *
 * <p>响应不仅返回草案列表，还返回生成策略、告警和下一步动作。
 * 这对商业化产品很重要：用户不仅要看到“系统建议了什么”，还要知道“这些建议为什么还不能直接启用”。</p>
 */
@Data
public class QualityRuleSuggestionResponse {

    /**
     * 数据源 ID。
     */
    private Long datasourceId;

    /**
     * 本次建议围绕的表名；为空表示按元数据中的多张表生成。
     */
    private String tableName;

    /**
     * 用户或 Agent 提供的治理目标。
     */
    private String businessGoal;

    /**
     * 实际生成的草案数量。
     */
    private Integer suggestionCount;

    /**
     * 草案列表。
     */
    private List<QualityRuleDraftSuggestion> suggestions;

    /**
     * 生成策略说明。
     * 例如当前是 deterministic-rule-engine，未来可以是 model-assisted、historical-profile-assisted。
     */
    private String generationStrategy;

    /**
     * 警告信息。
     * 例如元数据为空、字段被截断、未读取样本、只生成基础规则等。
     */
    private List<String> warnings;

    /**
     * 下一步建议动作。
     * 例如人工确认、补充业务阈值、转入审批、保存为 DRAFT。
     */
    private List<String> recommendedActions;
}
