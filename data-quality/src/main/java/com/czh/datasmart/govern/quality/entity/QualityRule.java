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
 * <p>
 * 这张表保存的是“我们希望如何判断数据是否合格”。
 * 对学习来说，它能很好地把一个质量规则拆成几个关键元素：
 * 1. 规则属于哪种质量维度。
 * 2. 规则要作用于哪个对象，例如某张表、某个字段或某个主题域。
 * 3. 使用什么运算符。
 * 4. 期望阈值是什么。
 */
@Data
@TableName("quality_rule")
public class QualityRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String ruleType;

    private String targetObject;

    private String comparisonOperator;

    private BigDecimal expectedValue;

    private String severity;

    private String description;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
