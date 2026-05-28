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
     * 租户 ID。
     *
     * <p>报告是质量检测结果的长期审计证据，需要与规则保持相同租户边界。
     * 后续如果做租户级质量评分、报表导出或保留周期清理，可以直接按 tenantId 处理。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>报告列表、质量大盘和失败报告检索都属于高频查询，因此把 projectId 冗余在报告表。
     * 这样 PROJECT 数据范围可以直接落到 `quality_check_report.project_id`，不必每次 join 规则表。</p>
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>用于项目内空间级质量结果筛选，例如按研发空间、测试空间、生产空间分别查看质量趋势。</p>
     */
    private Long workspaceId;

    /**
     * 关联规则 ID。
     */
    private Long ruleId;

    /**
     * 关联检测执行记录 ID。
     */
    private Long executionId;

    /**
     * 规则版本快照。
     */
    private Integer ruleVersion;

    /**
     * 规则名称快照。
     */
    private String ruleName;

    /**
     * 规则类型快照。
     *
     * <p>这个字段看似可以从 ruleId 回查，但在报告表中保留快照有两个现实价值：
     * 1. 报告列表可以直接按完整性、唯一性、有效性等类型过滤，不必每次 join 规则表；
     * 2. 即使未来规则类型被调整，历史报告也能保留当时的分类语义。
     */
    private String ruleType;

    /**
     * 检测目标快照。
     */
    private String targetObject;

    /**
     * 严重级别快照。
     */
    private String severity;

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
     * 通过率。
     *
     * <p>当前根据 sampleSize 和 exceptionCount 计算，方便管理后台直接展示。
     */
    private BigDecimal passRate;

    /**
     * 触发类型，例如 MANUAL、SCHEDULED、TASK_TRIGGERED。
     */
    private String triggerType;

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
