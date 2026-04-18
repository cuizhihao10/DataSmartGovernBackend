package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 执行质量检测请求。
 * <p>
 * 当前阶段这里不直接去扫真实数据，而是接受外部传入的观测值，
 * 用来先打通规则执行与报告生成链路。
 */
@Data
public class RunQualityCheckRequest {

    @NotNull(message = "measured value must not be null")
    @DecimalMin(value = "0", inclusive = true, message = "measured value must be greater than or equal to 0")
    private BigDecimal measuredValue;

    @NotNull(message = "sample size must not be null")
    @Min(value = 0, message = "sample size must be greater than or equal to 0")
    private Integer sampleSize;

    @NotNull(message = "exception count must not be null")
    @Min(value = 0, message = "exception count must be greater than or equal to 0")
    private Integer exceptionCount;

    private String notes;
}
