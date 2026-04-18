package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新质量规则请求。
 */
@Data
public class UpdateQualityRuleRequest {

    @NotBlank(message = "rule name must not be blank")
    private String name;

    @NotBlank(message = "target object must not be blank")
    private String targetObject;

    @NotBlank(message = "comparison operator must not be blank")
    private String comparisonOperator;

    @NotNull(message = "expected value must not be null")
    @DecimalMin(value = "0", inclusive = true, message = "expected value must be greater than or equal to 0")
    private BigDecimal expectedValue;

    private String severity;

    private String description;
}
