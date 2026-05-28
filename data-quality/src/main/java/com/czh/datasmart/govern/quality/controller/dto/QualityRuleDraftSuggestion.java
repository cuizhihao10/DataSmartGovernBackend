/**
 * @Author : Cui
 * @Date: 2026/05/24 22:06
 * @Description DataSmart Govern Backend - QualityRuleDraftSuggestion.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单条质量规则草案建议。
 *
 * <p>字段尽量贴近 `CreateQualityRuleRequest`，这样前端或 Agent 后续如果要把草案转成真实规则，
 * 可以直接把这些字段带入创建表单。但它仍然只是“建议”，不是数据库实体，不包含 id、status、createTime。</p>
 */
@Data
public class QualityRuleDraftSuggestion {

    /**
     * 规则名称建议。
     * 名称会包含表名/字段名和规则类型，便于用户在确认页快速判断建议含义。
     */
    private String name;

    /**
     * 规则类型，例如 COMPLETENESS、UNIQUENESS、VALIDITY。
     */
    private String ruleType;

    /**
     * 检测目标字符串，通常是 schema.table 或 schema.table.field。
     */
    private String targetObject;

    /**
     * 目标类型，当前第一版主要生成 RELATIONAL_TABLE 或 RELATIONAL_FIELD。
     */
    private String targetType;

    /**
     * 数据源 ID。
     */
    private Long dataSourceId;

    /**
     * 数据库名称。
     */
    private String databaseName;

    /**
     * Schema 名称。
     */
    private String schemaName;

    /**
     * 表名。
     */
    private String tableName;

    /**
     * 字段名。
     */
    private String fieldName;

    /**
     * 比较运算符。
     * 当前质量执行引擎支持 GT/GTE/LT/LTE/EQ/NEQ，因此草案也必须落在这些合法值内。
     */
    private String comparisonOperator;

    /**
     * 期望值或阈值。
     * 例如完整性通过率建议为 1.00，唯一性通过率建议为 1.00。
     */
    private BigDecimal expectedValue;

    /**
     * 严重级别建议。
     */
    private String severity;

    /**
     * 人类可读说明。
     */
    private String description;

    /**
     * 生成原因。
     * 用于告诉用户为什么 Agent/系统建议这条规则，例如“字段不可为空”“字段是主键”“字段名疑似金额”。
     */
    private String suggestionReason;

    /**
     * 置信度，0-1。
     * 第一版是规则引擎估算，不代表模型概率，后续接入模型或历史质量画像后可以升级。
     */
    private BigDecimal confidence;

    /**
     * 风险和治理提示。
     * 例如“建议人工确认阈值”“未来可接入异常值检测”“当前未读取样本数据”。
     */
    private List<String> governanceNotes;
}
