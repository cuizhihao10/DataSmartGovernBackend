/**
 * @Author : Cui
 * @Date: 2026/04/27 22:23
 * @Description DataSmart Govern Backend - QualityExecutionSuccessRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质量检测执行器成功完成回调请求。
 *
 * <p>这里的“成功”指的是扫描动作成功跑完，并不等于质量结果一定通过。
 * 例如执行器顺利扫描了 100 万行数据，发现 2 万行异常：
 * 1. executionState 应该是 SUCCESS，因为执行动作完成；
 * 2. report.checkStatus 可能是 FAILED，因为规则判定未通过。
 *
 * <p>把执行成功/失败与质量通过/失败分开，是数据治理系统非常关键的建模点。
 * 否则运维人员很难区分“系统没跑起来”和“系统跑起来后发现业务数据确实有问题”。
 */
@Data
public class QualityExecutionSuccessRequest {

    /**
     * 实际观测值。
     *
     * <p>执行器应根据规则类型计算这个值，例如空值率、重复率、有效记录数、格式通过率等。
     * data-quality 会用规则中保存的 comparisonOperator 和 expectedValue 做最终通过/失败判定。
     */
    @NotNull(message = "实际观测值不能为空")
    @DecimalMin(value = "0", inclusive = true, message = "实际观测值必须大于等于 0")
    private BigDecimal measuredValue;

    /**
     * 样本量。
     *
     * <p>样本量用于解释本次结果的覆盖范围。对于 SAMPLE_SCAN 是采样行数，
     * 对于 FULL_SCAN 是扫描行数，对于 Kafka/文件/API 场景可映射为消息数、行数或响应记录数。
     */
    @NotNull(message = "样本量不能为空")
    @Min(value = 0, message = "样本量必须大于等于 0")
    private Integer sampleSize;

    /**
     * 异常数量。
     *
     * <p>异常数量不能大于样本量。服务层会再次校验，避免外部执行器传入矛盾指标。
     */
    @NotNull(message = "异常数量不能为空")
    @Min(value = 0, message = "异常数量必须大于等于 0")
    private Integer exceptionCount;

    /**
     * 执行结果补充说明。
     *
     * <p>可记录扫描耗时摘要、过滤条件、分区范围、执行器版本或数据源连接器信息。
     */
    @Size(max = 1024, message = "备注长度不能超过 1024 个字符")
    private String notes;

    /**
     * 异常样本明细。
     *
     * <p>当前允许一次性传入少量代表性样本，适合产品早期闭环和小规模检测。
     * 面向真实大数据量生产场景时，应进一步扩展为分页上报、批量写入、对象存储归档或 Kafka 异步落库。
     */
    @Valid
    private List<QualityAnomalyDetailRequest> anomalies;
}
