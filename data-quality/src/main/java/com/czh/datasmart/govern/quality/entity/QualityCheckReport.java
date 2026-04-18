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
 * <p>
 * 当前阶段先做“单规则、单次执行”的结果记录，这样最适合先把闭环跑通。
 * 后续如果要做批量检测任务，再在这个基础上增加批次或任务维度即可。
 */
@Data
@TableName("quality_check_report")
public class QualityCheckReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;

    private String ruleName;

    private String targetObject;

    private BigDecimal measuredValue;

    private BigDecimal expectedValue;

    private String comparisonOperator;

    private String checkStatus;

    private Integer sampleSize;

    private Integer exceptionCount;

    private String summary;

    private String notes;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
