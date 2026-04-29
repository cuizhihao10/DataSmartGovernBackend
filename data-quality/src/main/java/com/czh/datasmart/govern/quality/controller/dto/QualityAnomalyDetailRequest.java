/**
 * @Author : Cui
 * @Date: 2026/04/27 21:20
 * @Description DataSmart Govern Backend - QualityAnomalyDetailRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 质量异常明细请求对象。
 *
 * <p>这个 DTO 用在“执行质量检测”接口中，表示调用方已经发现的异常样本。
 * 当前项目还没有真正接入数据源扫描器，所以异常明细由接口调用方传入；
 * 后续当 task-management、datasource-management、Python 质量智能体联动后，
 * 这些字段会由真实扫描任务、规则执行器或 AI 异常检测器自动生成。
 *
 * <p>设计上没有要求每个字段都必填，是因为不同数据源能提供的信息差异很大：
 * 1. 关系型数据库通常能提供字段名、主键、观测值；
 * 2. 文件类数据可能只能提供文件路径、行号和一段原始行内容；
 * 3. Kafka/消息流可能提供 topic、partition、offset 和消息 key；
 * 4. AI 检测类异常可能只提供自然语言解释和清洗建议。
 */
@Data
public class QualityAnomalyDetailRequest {

    /**
     * 异常类型。
     *
     * <p>建议使用稳定的大写编码，例如 NULL_VALUE、DUPLICATE_VALUE、OUT_OF_RANGE。
     * 当前不强制枚举，是为了避免在产品早期就限制不同行业的规则扩展。
     */
    @Size(max = 64, message = "异常类型长度不能超过 64 个字符")
    private String anomalyType;

    /**
     * 异常字段名称。
     *
     * <p>字段级规则建议填写；表级、指标级或跨表规则可以为空。
     */
    @Size(max = 128, message = "异常字段名称长度不能超过 128 个字符")
    private String fieldName;

    /**
     * 记录定位信息。
     *
     * <p>这是后续从报告跳转到原始数据、清洗任务或人工复核界面的关键字段。
     */
    @Size(max = 256, message = "记录定位信息长度不能超过 256 个字符")
    private String recordIdentifier;

    /**
     * 实际观测值。
     *
     * <p>注意这里用字符串而不是 BigDecimal，因为异常值可能是手机号、邮箱、枚举、日期或 JSON 片段。
     */
    @Size(max = 1024, message = "实际观测值长度不能超过 1024 个字符")
    private String observedValue;

    /**
     * 期望值或规则描述。
     *
     * <p>可以保存阈值、枚举范围、正则表达式、引用表说明或自然语言规则。
     */
    @Size(max = 1024, message = "期望值描述长度不能超过 1024 个字符")
    private String expectedValue;

    /**
     * 单条异常严重级别。
     *
     * <p>如果不填写，服务层会默认继承当前质量规则的 severity。
     */
    @Size(max = 16, message = "严重级别长度不能超过 16 个字符")
    private String severity;

    /**
     * 清洗或处理建议。
     *
     * <p>例如“补充默认值”“统一日期格式”“回源修复客户主数据”“进入人工复核队列”等。
     */
    @Size(max = 1024, message = "处理建议长度不能超过 1024 个字符")
    private String recommendation;

    /**
     * 样本载荷。
     *
     * <p>建议调用方传入脱敏且截断后的 JSON、CSV 行或消息摘要，避免写入完整敏感数据。
     */
    @Size(max = 4000, message = "样本载荷长度不能超过 4000 个字符")
    private String samplePayload;
}
