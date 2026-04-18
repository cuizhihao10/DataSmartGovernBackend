/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityRule.java
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
 * 质量规则实体。
 * 这张表保存的不是检测结果，而是“判断标准”。
 * 它回答的是：系统要依据什么规则，来判断数据是否合格。
 *
 * 学习这个实体时，可以重点理解五个维度：
 * 1. 规则属于哪种质量维度。
 * 2. 规则作用于哪个检测目标。
 * 3. 使用哪种比较运算符。
 * 4. 阈值或期望值是什么。
 * 5. 当前规则是否允许被执行。
 */
@Data
@TableName("quality_rule")
public class QualityRule {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 规则名称。
     * 用于管理界面的主要识别信息。
     */
    private String name;

    /**
     * 规则类型。
     * 例如完整性、唯一性、有效性等。
     */
    private String ruleType;

    /**
     * 检测目标。
     * 当前先用字符串表示，例如某张表、某个字段、某个业务对象。
     */
    private String targetObject;

    /**
     * 比较运算符。
     * 例如 GT、GTE、EQ 等。
     */
    private String comparisonOperator;

    /**
     * 期望值或阈值。
     */
    private BigDecimal expectedValue;

    /**
     * 严重级别。
     * 它不是决定规则能否执行的字段，而是表达失败后业务影响程度。
     */
    private String severity;

    /**
     * 规则说明。
     */
    private String description;

    /**
     * 规则状态。
     * 当前使用 ACTIVE、INACTIVE、DELETED 三种状态管理生命周期。
     */
    private String status;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
