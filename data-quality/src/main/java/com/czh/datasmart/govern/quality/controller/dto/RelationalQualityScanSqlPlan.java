/**
 * @Author : Cui
 * @Date: 2026/04/28 20:02
 * @Description DataSmart Govern Backend - RelationalQualityScanSqlPlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 关系型质量扫描 SQL 计划。
 *
 * <p>这个 DTO 表示“执行器未来真正访问关系型数据源时可以使用的 SQL 模板”，
 * 它是 `QualityScanPlan` 之后、真实 JDBC 执行之前的中间产物。
 *
 * <p>为什么要单独生成 SQL 计划，而不是在执行器里随手拼 SQL：
 * 1. SQL 会直接触达客户源库，必须可审查、可解释、可限流；
 * 2. 生成阶段可以统一做表名/字段名白名单校验、where 条件危险片段识别、limit 限制；
 * 3. 执行器只消费已经审查过的 SQL 模板，避免每个执行器实现一套不同规则；
 * 4. 后续接入 PostgreSQL、MySQL、Hive、ClickHouse 时，可以按方言扩展而不改质量报告模型。
 *
 * <p>当前计划只生成 SQL，不直接执行 SQL。
 * datasource-management 仍然是数据源连接、密钥、权限和元数据治理边界。
 */
@Data
public class RelationalQualityScanSqlPlan {

    /**
     * 是否支持当前规则生成 SQL。
     *
     * <p>当前第一版只支持 COMPLETENESS 和 UNIQUENESS 的关系型表/字段规则。
     */
    private Boolean supported;

    /**
     * 规则 ID。
     */
    private Long ruleId;

    /**
     * 规则名称。
     */
    private String ruleName;

    /**
     * 规则类型。
     */
    private String ruleType;

    /**
     * 目标类型。
     */
    private String targetType;

    /**
     * 数据源 ID。
     */
    private Long dataSourceId;

    /**
     * 参与 SQL 的表名表达式。
     *
     * <p>例如 `schema.table` 或 `database.table`。
     * 当前仅允许由字母、数字、下划线和点组成的安全标识符，不允许自由文本表名。
     */
    private String tableExpression;

    /**
     * 字段名。
     */
    private String fieldName;

    /**
     * 执行模式。
     */
    private String executionMode;

    /**
     * 最大扫描行数。
     */
    private Long maxScannedRows;

    /**
     * 异常样本上限。
     */
    private Integer anomalySampleLimit;

    /**
     * 超时时间，单位秒。
     */
    private Integer timeoutSeconds;

    /**
     * 汇总指标 SQL。
     *
     * <p>该 SQL 预期返回 sample_size、exception_count、measured_value 三类核心字段：
     * sample_size 表示本次参与检测的数据量；
     * exception_count 表示异常数量；
     * measured_value 表示用于和规则 expectedValue 比较的实际观测值。
     */
    private String metricSql;

    /**
     * 异常样本 SQL。
     *
     * <p>该 SQL 用于采集少量代表性异常样本，后续可以转换为 quality_anomaly_detail。
     * 它必须带有 limit，不能无界返回。
     */
    private String anomalySampleSql;

    /**
     * SQL 计划说明。
     */
    private String message;

    /**
     * 风险提示。
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 执行建议。
     */
    private List<String> suggestions = new ArrayList<>();

    /**
     * 计划生成时间。
     */
    private LocalDateTime generatedTime;
}
