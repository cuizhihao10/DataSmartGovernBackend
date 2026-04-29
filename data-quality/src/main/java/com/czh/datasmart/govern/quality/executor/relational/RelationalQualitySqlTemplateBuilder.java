/**
 * @Author : Cui
 * @Date: 2026/04/28 20:02
 * @Description DataSmart Govern Backend - RelationalQualitySqlTemplateBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor.relational;

import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.RelationalQualityScanSqlPlan;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import com.czh.datasmart.govern.quality.support.QualityRuleType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 关系型质量扫描 SQL 模板构建器。
 *
 * <p>该类的职责是把“质量规则 + 扫描计划”转换成只读 SQL 模板。
 * 它不负责拿数据库连接，也不直接执行 SQL。这样做是为了保持模块边界：
 * datasource-management 负责连接、密钥、权限和元数据；
 * data-quality 负责规则、扫描语义、报告和异常证据；
 * 未来真正的执行器负责在授权连接上执行这里生成的安全模板。
 *
 * <p>当前只支持两个最容易产品化闭环的规则类型：
 * 1. COMPLETENESS：字段空值检测，measured_value 表示非空率；
 * 2. UNIQUENESS：字段唯一性检测，measured_value 表示唯一率。
 *
 * <p>VALIDITY、CONSISTENCY、ACCURACY 暂不生成 SQL。
 * 因为它们往往需要正则、枚举、跨表 Join、参考数据集或业务基准值，
 * 如果没有更细的规则参数，强行生成 SQL 很容易变成误导性的 demo。
 */
@Slf4j
@Component
public class RelationalQualitySqlTemplateBuilder {

    /**
     * 标识符白名单。
     *
     * <p>表名、schema 名、字段名只能由字母、数字、下划线组成，并且必须以字母或下划线开头。
     * 当前不支持带空格、引号、函数、表达式的对象名。
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 构建关系型 SQL 计划。
     */
    public RelationalQualityScanSqlPlan build(QualityRule rule, QualityScanPlan scanPlan) {
        RelationalQualityScanSqlPlan sqlPlan = basePlan(rule, scanPlan);
        if (!isRelationalTarget(rule)) {
            return unsupported(sqlPlan, "当前规则不是关系型目标，不能生成关系型 SQL 模板");
        }
        if (!Boolean.TRUE.equals(scanPlan.getSchedulable())) {
            return unsupported(sqlPlan, "扫描计划不可调度，不能生成 SQL 模板");
        }
        if (!hasText(rule.getTableName())) {
            return unsupported(sqlPlan, "关系型 SQL 模板缺少 tableName");
        }
        if (!isSafeIdentifier(rule.getTableName())) {
            return unsupported(sqlPlan, "tableName 未通过安全标识符校验");
        }
        if (hasText(rule.getFieldName()) && !isSafeIdentifier(rule.getFieldName())) {
            return unsupported(sqlPlan, "fieldName 未通过安全标识符校验");
        }
        if (hasDangerousWhereClause(scanPlan.getWhereClause())) {
            return unsupported(sqlPlan, "whereClause 包含危险 SQL 片段，禁止生成 SQL 模板");
        }

        String tableExpression = buildTableExpression(rule);
        sqlPlan.setTableExpression(tableExpression);
        sqlPlan.setFieldName(rule.getFieldName());

        QualityRuleType ruleType = QualityRuleType.fromValue(rule.getRuleType());
        if (QualityRuleType.COMPLETENESS.equals(ruleType)) {
            return buildCompletenessPlan(sqlPlan, tableExpression, rule, scanPlan);
        }
        if (QualityRuleType.UNIQUENESS.equals(ruleType)) {
            return buildUniquenessPlan(sqlPlan, tableExpression, rule, scanPlan);
        }
        return unsupported(sqlPlan, "当前规则类型暂未支持自动生成关系型 SQL: " + ruleType);
    }

    /**
     * 构造基础计划对象。
     */
    private RelationalQualityScanSqlPlan basePlan(QualityRule rule, QualityScanPlan scanPlan) {
        RelationalQualityScanSqlPlan sqlPlan = new RelationalQualityScanSqlPlan();
        sqlPlan.setSupported(false);
        sqlPlan.setRuleId(rule.getId());
        sqlPlan.setRuleName(rule.getName());
        sqlPlan.setRuleType(rule.getRuleType());
        sqlPlan.setTargetType(rule.getTargetType());
        sqlPlan.setDataSourceId(rule.getDataSourceId());
        sqlPlan.setExecutionMode(scanPlan.getExecutionMode());
        sqlPlan.setMaxScannedRows(scanPlan.getMaxScannedRows());
        sqlPlan.setAnomalySampleLimit(scanPlan.getAnomalySampleLimit());
        sqlPlan.setTimeoutSeconds(scanPlan.getTimeoutSeconds());
        sqlPlan.setGeneratedTime(LocalDateTime.now());
        return sqlPlan;
    }

    /**
     * 构建完整性 SQL。
     *
     * <p>measured_value 使用“非空率”，范围 0 到 1。
     * 因此规则 expectedValue 应该配置成类似 0.95、0.99 这样的阈值，并用 GTE/LT 等比较运算符判定。
     */
    private RelationalQualityScanSqlPlan buildCompletenessPlan(RelationalQualityScanSqlPlan sqlPlan,
                                                               String tableExpression,
                                                               QualityRule rule,
                                                               QualityScanPlan scanPlan) {
        if (!hasText(rule.getFieldName())) {
            return unsupported(sqlPlan, "COMPLETENESS 当前需要字段级规则，请填写 fieldName");
        }
        String limitedSource = limitedSourceSql(tableExpression, scanPlan, rule.getFieldName());
        String field = rule.getFieldName();
        sqlPlan.setMetricSql("""
                SELECT
                  COUNT(1) AS sample_size,
                  SUM(CASE WHEN %s IS NULL THEN 1 ELSE 0 END) AS exception_count,
                  CASE
                    WHEN COUNT(1) = 0 THEN 1
                    ELSE (COUNT(1) - SUM(CASE WHEN %s IS NULL THEN 1 ELSE 0 END)) * 1.0 / COUNT(1)
                  END AS measured_value
                FROM (
                  %s
                ) quality_source
                """.formatted(field, field, limitedSource));
        sqlPlan.setAnomalySampleSql("""
                SELECT *
                FROM (
                  %s
                ) quality_source
                WHERE %s IS NULL
                LIMIT %d
                """.formatted(limitedSource, field, safeAnomalyLimit(scanPlan)));
        markSupported(sqlPlan, "已生成完整性检测 SQL，measured_value 表示字段非空率");
        sqlPlan.getSuggestions().add("执行前应确认 expectedValue 使用 0-1 小数表达非空率阈值，例如 0.99。");
        return sqlPlan;
    }

    /**
     * 构建唯一性 SQL。
     *
     * <p>measured_value 使用“唯一率”，范围 0 到 1。
     * exception_count 使用 sample_size - distinct_count 表示重复贡献数量。
     */
    private RelationalQualityScanSqlPlan buildUniquenessPlan(RelationalQualityScanSqlPlan sqlPlan,
                                                             String tableExpression,
                                                             QualityRule rule,
                                                             QualityScanPlan scanPlan) {
        if (!hasText(rule.getFieldName())) {
            return unsupported(sqlPlan, "UNIQUENESS 当前需要字段级规则，请填写 fieldName");
        }
        String limitedSource = limitedSourceSql(tableExpression, scanPlan, rule.getFieldName());
        String field = rule.getFieldName();
        sqlPlan.setMetricSql("""
                SELECT
                  COUNT(1) AS sample_size,
                  COUNT(1) - COUNT(DISTINCT %s) AS exception_count,
                  CASE
                    WHEN COUNT(1) = 0 THEN 1
                    ELSE COUNT(DISTINCT %s) * 1.0 / COUNT(1)
                  END AS measured_value
                FROM (
                  %s
                ) quality_source
                """.formatted(field, field, limitedSource));
        sqlPlan.setAnomalySampleSql("""
                SELECT *
                FROM (
                  %s
                ) quality_source
                WHERE %s IN (
                  SELECT %s
                  FROM (
                    %s
                  ) duplicate_source
                  GROUP BY %s
                  HAVING COUNT(1) > 1
                )
                LIMIT %d
                """.formatted(limitedSource, field, field, limitedSource, field, safeAnomalyLimit(scanPlan)));
        markSupported(sqlPlan, "已生成唯一性检测 SQL，measured_value 表示字段唯一率");
        sqlPlan.getWarnings().add("唯一性检测使用 COUNT(DISTINCT)，大表上可能较重，建议优先配合 SAMPLE_SCAN 或分区扫描。");
        return sqlPlan;
    }

    /**
     * 构造带扫描边界的源查询。
     *
     * <p>这里统一追加 LIMIT，避免模板生成无界全表扫描。
     * 即使执行模式是 FULL_SCAN，也会受 maxScannedRows 保护。
     */
    private String limitedSourceSql(String tableExpression, QualityScanPlan scanPlan, String fieldName) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(tableExpression);
        if (hasText(scanPlan.getWhereClause())) {
            sql.append("\nWHERE ").append(scanPlan.getWhereClause().trim());
        }
        sql.append("\nLIMIT ").append(safeMaxRows(scanPlan));
        return sql.toString();
    }

    /**
     * 构造表表达式。
     *
     * <p>当前先使用 schemaName 优先，其次 databaseName。
     * PostgreSQL 通常使用 schema.table；MySQL 通常使用 database.table。
     * 真正多方言支持时，应由 datasource-management 返回数据源类型，再按方言构造引用方式。
     */
    private String buildTableExpression(QualityRule rule) {
        String qualifier = hasText(rule.getSchemaName()) ? rule.getSchemaName() : rule.getDatabaseName();
        if (hasText(qualifier)) {
            if (!isSafeIdentifier(qualifier)) {
                throw new IllegalArgumentException("databaseName/schemaName 未通过安全标识符校验");
            }
            return qualifier.trim() + "." + rule.getTableName().trim();
        }
        return rule.getTableName().trim();
    }

    /**
     * 标记计划支持执行。
     */
    private void markSupported(RelationalQualityScanSqlPlan sqlPlan, String message) {
        sqlPlan.setSupported(true);
        sqlPlan.setMessage(message);
        sqlPlan.getSuggestions().add("SQL 执行必须使用只读连接、statement timeout、行数上限和审计日志。");
        sqlPlan.getSuggestions().add("异常样本写入 quality_anomaly_detail 前应做字段脱敏和长度截断。");
    }

    /**
     * 标记不支持并写入原因。
     */
    private RelationalQualityScanSqlPlan unsupported(RelationalQualityScanSqlPlan sqlPlan, String reason) {
        sqlPlan.setSupported(false);
        sqlPlan.setMessage(reason);
        sqlPlan.getWarnings().add(reason);
        return sqlPlan;
    }

    /**
     * 判断是否为关系型目标。
     */
    private boolean isRelationalTarget(QualityRule rule) {
        QualityRuleTargetType targetType = QualityRuleTargetType.fromValue(rule.getTargetType());
        return QualityRuleTargetType.RELATIONAL_TABLE.equals(targetType)
                || QualityRuleTargetType.RELATIONAL_FIELD.equals(targetType);
    }

    /**
     * 最大扫描行数保护。
     */
    private long safeMaxRows(QualityScanPlan scanPlan) {
        if (scanPlan.getMaxScannedRows() == null || scanPlan.getMaxScannedRows() <= 0) {
            return 100000L;
        }
        return Math.min(scanPlan.getMaxScannedRows(), 1000000L);
    }

    /**
     * 异常样本上限保护。
     */
    private int safeAnomalyLimit(QualityScanPlan scanPlan) {
        if (scanPlan.getAnomalySampleLimit() == null || scanPlan.getAnomalySampleLimit() <= 0) {
            return 100;
        }
        return Math.min(scanPlan.getAnomalySampleLimit(), 1000);
    }

    /**
     * 标识符安全校验。
     */
    private boolean isSafeIdentifier(String value) {
        return hasText(value) && IDENTIFIER.matcher(value.trim()).matches();
    }

    /**
     * 危险 where 条件识别。
     */
    private boolean hasDangerousWhereClause(String whereClause) {
        if (!hasText(whereClause)) {
            return false;
        }
        String lower = whereClause.toLowerCase(Locale.ROOT);
        return lower.contains(";")
                || lower.contains("--")
                || lower.contains("/*")
                || lower.contains(" drop ")
                || lower.contains(" delete ")
                || lower.contains(" update ")
                || lower.contains(" insert ")
                || lower.contains(" truncate ");
    }

    /**
     * 字符串是否有真实内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
