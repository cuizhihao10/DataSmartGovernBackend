/**
 * @Author : Cui
 * @Date: 2026/04/27 21:20
 * @Description DataSmart Govern Backend - QualityAnomalyDetail.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量异常明细实体。
 *
 * <p>质量报告回答的是“这次检测整体是否通过”，而异常明细回答的是“到底是哪几条数据、哪个字段、
 * 因为什么原因不符合规则”。在真实商业化数据治理产品里，只有总数和通过率通常是不够的，
 * 运营人员还需要看到可定位、可复盘、可进入清洗流程的样本级证据。
 *
 * <p>当前阶段先把异常明细设计成通用结构，不强绑定某一种数据源或某一张业务表：
 * 1. MySQL/PostgreSQL 等关系型数据可以用 recordIdentifier 保存主键或联合主键摘要；
 * 2. Kafka、文件、对象存储等非关系型来源可以把消息 key、行号、对象路径保存为 recordIdentifier；
 * 3. samplePayload 保存被截断后的样本上下文，避免为了定位问题必须重新扫描全量数据；
 * 4. recommendation 保存清洗建议，后续可由数据质量智能体或清洗智能体自动生成。
 */
@Data
@TableName("quality_anomaly_detail")
public class QualityAnomalyDetail {

    /**
     * 异常明细主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联质量报告 ID。
     *
     * <p>同一份报告可以对应多条异常明细。列表页通常先看报告摘要，进入详情页后再按 reportId
     * 查询异常样本，这样可以避免报告列表一次性加载大量明细造成性能压力。
     */
    private Long reportId;

    /**
     * 关联质量规则 ID。
     *
     * <p>虽然 reportId 已经可以间接找到 ruleId，但这里冗余 ruleId 是为了支持后续按规则直接统计
     * 高频异常字段、异常类型分布和治理趋势，减少跨表查询成本。
     */
    private Long ruleId;

    /**
     * 检测目标快照。
     *
     * <p>通常是库表、字段、指标或业务对象路径，例如 ods.customer.email。
     * 作为快照保存可以保证即使规则目标后续调整，历史异常仍能解释当时的问题来源。
     */
    private String targetObject;

    /**
     * 异常类型。
     *
     * <p>建议值包括 NULL_VALUE、DUPLICATE_VALUE、OUT_OF_RANGE、FORMAT_INVALID、
     * REFERENTIAL_MISSING、BUSINESS_RULE_VIOLATION 等。这里先使用字符串而非枚举，
     * 是为了给未来不同行业、不同连接器和 AI 检测策略预留扩展空间。
     */
    private String anomalyType;

    /**
     * 异常字段名称。
     *
     * <p>表级规则可以为空；字段级规则应保存字段名，方便后续做字段画像、字段质量评分和清洗脚本生成。
     */
    private String fieldName;

    /**
     * 记录定位信息。
     *
     * <p>关系型数据常放主键值或联合主键摘要；文件数据可放文件名与行号；消息数据可放 topic、
     * partition、offset 或 message key。这个字段是后续“从报告定位到原始数据”的关键。
     */
    private String recordIdentifier;

    /**
     * 实际观测值。
     *
     * <p>保存触发异常的值，方便人工排查。例如手机号格式错误时保存原始手机号，
     * 范围校验失败时保存超出阈值的实际值。
     */
    private String observedValue;

    /**
     * 期望值或规则说明。
     *
     * <p>这里不强制保存数字，因为有些规则的期望值可能是正则表达式、枚举集合、引用表或业务描述。
     */
    private String expectedValue;

    /**
     * 异常严重级别快照。
     *
     * <p>默认可继承规则严重级别，也允许单条异常覆盖。例如同一规则下，核心客户记录异常可能比普通记录更严重。
     */
    private String severity;

    /**
     * 清洗或处理建议。
     *
     * <p>现阶段由调用方传入；未来可以接入质量智能体生成“补默认值、标准化格式、回源修复、人工复核”等建议。
     */
    private String recommendation;

    /**
     * 样本载荷。
     *
     * <p>用于保存被截断后的 JSON、CSV 行、消息摘要或字段上下文。真实生产环境要注意脱敏和长度控制，
     * 避免把敏感数据或超大原始记录无限制写入业务库。
     */
    private String samplePayload;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
