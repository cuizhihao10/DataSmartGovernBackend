/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - RunQualityCheckRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 执行质量检测请求体。
 * 当前阶段先不直接扫描真实数据源，而是由外部传入观测值，
 * 用来打通“规则执行 -> 报告生成”这条主链路。
 * 后续当数据源模块和任务模块进一步联动后，再把观测值来源替换为真实计算结果。
 */
@Data
public class RunQualityCheckRequest {

    /**
     * 实际观测值。
     */
    @NotNull(message = "实际观测值不能为空")
    @DecimalMin(value = "0", inclusive = true, message = "实际观测值必须大于等于 0")
    private BigDecimal measuredValue;

    /**
     * 样本量。
     * 用于表达本次检测基于多大范围的数据。
     */
    @NotNull(message = "样本量不能为空")
    @Min(value = 0, message = "样本量必须大于等于 0")
    private Integer sampleSize;

    /**
     * 异常数量。
     */
    @NotNull(message = "异常数量不能为空")
    @Min(value = 0, message = "异常数量必须大于等于 0")
    private Integer exceptionCount;

    /**
     * 备注信息。
     * 可用于补充检测上下文、来源说明或人工说明。
     */
    private String notes;
}
