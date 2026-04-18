/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - UpdateQualityRuleRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新质量规则请求体。
 * 当前更新接口刻意不允许修改 ruleType，
 * 因为规则类型更接近“规则建模时的基础分类”，不适合作为日常随意漂移的字段。
 */
@Data
public class UpdateQualityRuleRequest {

    /**
     * 规则名称。
     */
    @NotBlank(message = "规则名称不能为空")
    private String name;

    /**
     * 检测目标。
     */
    @NotBlank(message = "检测目标不能为空")
    private String targetObject;

    /**
     * 比较运算符。
     */
    @NotBlank(message = "比较运算符不能为空")
    private String comparisonOperator;

    /**
     * 期望值。
     */
    @NotNull(message = "期望值不能为空")
    @DecimalMin(value = "0", inclusive = true, message = "期望值必须大于等于 0")
    private BigDecimal expectedValue;

    /**
     * 严重级别。
     */
    private String severity;

    /**
     * 规则描述。
     */
    private String description;
}
