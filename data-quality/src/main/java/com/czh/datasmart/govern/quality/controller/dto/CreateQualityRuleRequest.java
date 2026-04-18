/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - CreateQualityRuleRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建质量规则请求体。
 * DTO 的职责是描述接口允许外部提交哪些字段，以及这些字段在入口层的基本合法性。
 * 它不直接表达数据库结构，也不承载复杂业务逻辑。
 */
@Data
public class CreateQualityRuleRequest {

    /**
     * 规则名称。
     * 用于列表展示、搜索和管理识别，因此要求非空。
     */
    @NotBlank(message = "规则名称不能为空")
    private String name;

    /**
     * 规则类型。
     * 例如完整性、唯一性、有效性等，用于说明规则属于哪一类质量维度。
     */
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    /**
     * 检测目标。
     * 当前先用字符串表达，后续可以扩展成更结构化的字段定位模型。
     */
    @NotBlank(message = "检测目标不能为空")
    private String targetObject;

    /**
     * 比较运算符。
     * 例如 GT、GTE、EQ 等，用于定义“如何判断是否合格”。
     */
    @NotBlank(message = "比较运算符不能为空")
    private String comparisonOperator;

    /**
     * 期望值或阈值。
     * 当前要求不能小于 0，主要面向数值类检测场景。
     */
    @NotNull(message = "期望值不能为空")
    @DecimalMin(value = "0", inclusive = true, message = "期望值必须大于等于 0")
    private BigDecimal expectedValue;

    /**
     * 严重级别。
     * 允许为空，服务层会补默认值。
     */
    private String severity;

    /**
     * 规则描述。
     */
    private String description;
}
