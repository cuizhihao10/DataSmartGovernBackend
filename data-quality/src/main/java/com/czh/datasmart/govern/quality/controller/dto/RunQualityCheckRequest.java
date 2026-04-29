/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - RunQualityCheckRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

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

    /**
     * 异常明细列表。
     *
     * <p>sampleSize 和 exceptionCount 只能告诉我们“异常有多少”，但不能告诉我们“异常在哪里”。
     * 因此这里允许调用方把代表性异常样本一起传入，服务层会在生成报告后把它们落到
     * quality_anomaly_detail 表中。
     *
     * <p>当前接口仍然允许 anomalies 为空，这是一个有意保留的兼容设计：
     * 1. 早期人工录入或外部系统只知道异常总数时，也能先生成报告；
     * 2. 真实扫描器完成后，可以逐步把异常样本补进来；
     * 3. 对于超大异常量场景，未来应改为异步分页写入或对象存储导出，而不是一次请求塞入海量明细。
     */
    @Valid
    private List<QualityAnomalyDetailRequest> anomalies;
}
