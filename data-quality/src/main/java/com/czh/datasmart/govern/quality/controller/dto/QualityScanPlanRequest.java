/**
 * @Author : Cui
 * @Date: 2026/04/27 22:05
 * @Description DataSmart Govern Backend - QualityScanPlanRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 质量扫描计划生成请求。
 *
 * <p>这个请求不是“执行质量检测”，而是“把规则转换成执行器可以理解的扫描计划”。
 * 在真实平台里，计划生成通常发生在：
 * 1. 用户保存规则后，预览这条规则未来会怎么扫；
 * 2. 调度器创建任务前，固化本次执行参数；
 * 3. 运维人员评估某条规则是否可能造成源库压力；
 * 4. AI 智能体生成规则后，先让人类审查扫描范围。
 */
@Data
public class QualityScanPlanRequest {

    /**
     * 执行模式。
     *
     * <p>支持 SAMPLE_SCAN、FULL_SCAN、PARTITION_SCAN、INCREMENTAL_WINDOW。
     * 如果为空，服务层默认使用 SAMPLE_SCAN，避免无意识全表扫描。
     */
    private String executionMode;

    /**
     * 抽样行数上限。
     *
     * <p>抽样扫描时使用。过大的抽样值会变成“伪全量扫描”，因此入口层先做最大值限制。
     */
    @Min(value = 1, message = "抽样行数不能小于 1")
    @Max(value = 100000, message = "抽样行数不能大于 100000")
    private Integer sampleLimit;

    /**
     * 最大扫描行数。
     *
     * <p>这是源库保护阈值。即使生成了全量或分区扫描计划，也应该有最大扫描行数保护。
     */
    @Min(value = 1, message = "最大扫描行数不能小于 1")
    @Max(value = 10000000, message = "最大扫描行数不能大于 10000000")
    private Long maxScannedRows;

    /**
     * 分区字段。
     *
     * <p>PARTITION_SCAN 和 INCREMENTAL_WINDOW 模式建议填写，例如 dt、biz_date、updated_at。
     */
    @Size(max = 128, message = "分区字段长度不能超过 128 个字符")
    private String partitionField;

    /**
     * 过滤条件。
     *
     * <p>当前只作为计划说明保存，不直接执行。后续如果要生成真实 SQL，必须引入参数化模板、
     * SQL 解析、黑白名单和权限审计，不能简单拼接。
     */
    @Size(max = 1000, message = "过滤条件长度不能超过 1000 个字符")
    private String whereClause;

    /**
     * 扫描超时时间，单位秒。
     */
    @Min(value = 1, message = "超时时间不能小于 1 秒")
    @Max(value = 86400, message = "超时时间不能大于 86400 秒")
    private Integer timeoutSeconds;

    /**
     * 是否采集异常样本。
     *
     * <p>异常样本对排查很重要，但也可能带来敏感数据风险，因此需要显式控制。
     */
    private Boolean collectAnomalySamples;

    /**
     * 异常样本数量上限。
     */
    @Min(value = 1, message = "异常样本数量不能小于 1")
    @Max(value = 10000, message = "异常样本数量不能大于 10000")
    private Integer anomalySampleLimit;
}
