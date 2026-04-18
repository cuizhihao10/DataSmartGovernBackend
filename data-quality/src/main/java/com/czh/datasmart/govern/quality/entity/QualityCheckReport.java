/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityCheckReport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量检测报告实体。
 * 如果说 QualityRule 保存的是“判断标准”，
 * 那么 QualityCheckReport 保存的就是“一次具体判断的结果快照”。
 *
 * 这里有一个很重要的建模思想：
 * 报告里会冗余保存规则名称、比较运算符、期望值等字段，
 * 而不是只存一个 ruleId。
 * 这样即使规则后来被修改，历史报告仍然能独立解释自己当时为什么通过或失败。
 */
@Data
@TableName("quality_check_report")
public class QualityCheckReport {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联规则 ID。
     */
    private Long ruleId;

    /**
     * 规则名称快照。
     */
    private String ruleName;

    /**
     * 检测目标快照。
     */
    private String targetObject;

    /**
     * 实际观测值。
     */
    private BigDecimal measuredValue;

    /**
     * 期望值快照。
     */
    private BigDecimal expectedValue;

    /**
     * 比较运算符快照。
     */
    private String comparisonOperator;

    /**
     * 检测结果状态。
     * 当前只区分 PASSED 和 FAILED。
     */
    private String checkStatus;

    /**
     * 样本量。
     * 用于描述本次判断覆盖的数据范围。
     */
    private Integer sampleSize;

    /**
     * 异常数量。
     */
    private Integer exceptionCount;

    /**
     * 给人阅读的摘要说明。
     * 便于在列表页快速浏览，不必每次展开所有明细。
     */
    private String summary;

    /**
     * 补充说明。
     */
    private String notes;

    /**
     * 报告创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
