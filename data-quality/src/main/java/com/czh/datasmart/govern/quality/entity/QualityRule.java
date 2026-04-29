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
     * 检测目标类型。
     *
     * <p>例如 GENERIC、RELATIONAL_TABLE、RELATIONAL_FIELD、KAFKA_TOPIC、FILE_OBJECT、API_ENDPOINT。
     * 它决定后续应该使用哪类扫描策略，而不是让所有规则都共享同一种检测逻辑。
     */
    private String targetType;

    /**
     * 数据源 ID。
     *
     * <p>关系型表或字段规则通常需要绑定 datasource-management 中登记的数据源。
     * 当前只保存引用 ID，不在质量模块内保存连接密钥，避免破坏数据源管理和密钥管理边界。
     */
    private Long dataSourceId;

    /**
     * 数据库名称。
     *
     * <p>部分连接器使用 databaseName 表示物理库，例如 MySQL；有些系统可能只使用 schemaName。
     */
    private String databaseName;

    /**
     * Schema 名称。
     *
     * <p>PostgreSQL、Oracle、Hive 等多 schema 场景会用到该字段。
     */
    private String schemaName;

    /**
     * 表名。
     *
     * <p>关系型表和字段规则会使用该字段定位具体表。
     */
    private String tableName;

    /**
     * 字段名。
     *
     * <p>字段级规则使用该字段定位具体列，例如 email、phone、amount。
     */
    private String fieldName;

    /**
     * 扫描策略编码。
     *
     * <p>由目标校验策略写入，表示后续执行器应该按哪类策略扫描该目标。
     */
    private String scanStrategy;

    /**
     * 目标校验状态。
     *
     * <p>用于判断规则目标是否已经满足当前平台扫描前提，例如 VALIDATED、INVALID、UNSUPPORTED。
     */
    private String targetValidationStatus;

    /**
     * 目标校验说明。
     *
     * <p>保存最近一次校验的人类可读说明，便于管理后台直接展示失败原因。
     */
    private String targetValidationMessage;

    /**
     * 目标最近校验时间。
     */
    private LocalDateTime targetValidatedTime;

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
     * 当前使用 DRAFT、ACTIVE、INACTIVE、ARCHIVED、DELETED 管理生命周期。
     */
    private String status;

    /**
     * 规则版本号。
     *
     * <p>每次更新规则定义时递增。
     * 报告会保存 ruleVersion 快照，这样即使规则后来被修改，历史报告仍能解释它基于哪一版规则生成。
     */
    private Integer ruleVersion;

    /**
     * 最近一次检测时间。
     */
    private LocalDateTime lastCheckTime;

    /**
     * 最近一次检测结果：PASSED 或 FAILED。
     */
    private String lastCheckStatus;

    /**
     * 最近一次检测报告 ID。
     */
    private Long lastReportId;

    /**
     * 归档时间。
     *
     * <p>归档不是删除，而是表达“这条规则已经退出当前治理流程，但历史仍保留”。
     */
    private LocalDateTime archivedTime;

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
