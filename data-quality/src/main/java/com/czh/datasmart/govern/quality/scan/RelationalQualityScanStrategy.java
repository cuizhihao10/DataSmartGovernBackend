/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - RelationalQualityScanStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.scan;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleTargetValidationResult;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlan;
import com.czh.datasmart.govern.quality.controller.dto.QualityScanPlanRequest;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.integration.datasource.DatasourceMetadataValidationClient;
import com.czh.datasmart.govern.quality.integration.datasource.RelationalMetadataValidationOutcome;
import com.czh.datasmart.govern.quality.support.QualityScanExecutionMode;
import com.czh.datasmart.govern.quality.support.QualityRuleTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 关系型数据质量扫描策略。
 *
 * <p>该策略面向 MySQL、PostgreSQL、Hive 等表结构数据。
 * 当前阶段只做结构性校验：检查是否填写 datasourceId、tableName，
 * 字段级规则额外检查 fieldName。
 *
 * <p>为什么暂时不直接查库？
 * datasource-management 已经负责数据源连接、权限、元数据发现。
 * data-quality 不应该绕过它直接保存连接信息或私自访问源库，否则会破坏模块边界。
 * 后续更成熟的做法是通过网关或服务契约调用 datasource-management 的元数据接口，
 * 校验数据源是否存在、表字段是否存在、是否允许采样。
 */
@Component
@RequiredArgsConstructor
public class RelationalQualityScanStrategy extends AbstractQualityScanStrategy {

    /**
     * datasource-management 元数据校验客户端。
     *
     * <p>关系型策略不直接访问源库，而是通过数据源模块确认表字段是否存在。
     * 这样可以保留数据源连接、密钥、权限和审计边界。
     */
    private final DatasourceMetadataValidationClient datasourceMetadataValidationClient;

    @Override
    public String strategyCode() {
        return "RELATIONAL_METADATA_SCAN";
    }

    @Override
    public boolean supports(QualityRuleTargetType targetType) {
        return QualityRuleTargetType.RELATIONAL_TABLE.equals(targetType)
                || QualityRuleTargetType.RELATIONAL_FIELD.equals(targetType);
    }

    @Override
    public QualityRuleTargetValidationResult validate(QualityRule rule) {
        if (rule.getDataSourceId() == null) {
            return invalidResult(rule, "关系型质量规则缺少 dataSourceId，无法定位数据源",
                    "请选择已登记的数据源，后续系统才能通过 datasource-management 做表和字段元数据校验。");
        }
        if (!hasText(rule.getTableName())) {
            return invalidResult(rule, "关系型质量规则缺少 tableName，无法定位被检测表",
                    "请填写表名；如果存在多 schema 场景，也建议同时填写 databaseName 或 schemaName。");
        }
        QualityRuleTargetType targetType = QualityRuleTargetType.fromValue(rule.getTargetType());
        if (QualityRuleTargetType.RELATIONAL_FIELD.equals(targetType) && !hasText(rule.getFieldName())) {
            return invalidResult(rule, "字段级质量规则缺少 fieldName，无法定位被检测字段",
                    "请填写字段名，例如 email、phone、amount。");
        }
        RelationalMetadataValidationOutcome outcome = datasourceMetadataValidationClient.validateRelationalTarget(rule);
        if (!outcome.isValid()) {
            QualityRuleTargetValidationResult result = invalidResult(rule, outcome.getMessage(),
                    "请检查数据源、库/schema、表名、字段名以及 datasource-management 元数据发现能力。");
            result.getSuggestions().addAll(outcome.getSuggestions());
            return result;
        }

        QualityRuleTargetValidationResult result = validResult(rule, outcome.getMessage());
        if (!outcome.isExecuted()) {
            result.getSuggestions().add("当前只完成结构性校验，尚未确认远程数据源中表和字段真实存在。");
        }
        if (outcome.isFailOpen()) {
            result.getSuggestions().add("当前远程校验处于 fail-open 放行状态，生产环境建议改为 fail-close 以降低误启用风险。");
        }
        result.getSuggestions().addAll(outcome.getSuggestions());
        result.getSuggestions().add("大表扫描需要配置采样策略、分区条件、超时时间和并发限制，避免质量任务影响源库。");
        return result;
    }

    /**
     * 生成关系型质量扫描计划。
     *
     * <p>这里仍然不执行 SQL，只把规则和运行参数转换成执行器能理解的计划。
     * 后续 task-management 接入后，可以把这个计划作为任务 payload 交给质量执行器。
     */
    @Override
    public QualityScanPlan buildScanPlan(QualityRule rule, QualityScanPlanRequest request) {
        QualityScanExecutionMode executionMode = QualityScanExecutionMode.fromValue(request == null ? null : request.getExecutionMode());
        QualityScanPlan plan = new QualityScanPlan();
        plan.setRuleId(rule.getId());
        plan.setRuleName(rule.getName());
        plan.setRuleVersion(rule.getRuleVersion());
        plan.setTargetType(rule.getTargetType());
        plan.setScanStrategy(strategyCode());
        plan.setExecutionMode(executionMode.name());
        plan.setDataSourceId(rule.getDataSourceId());
        plan.setDatabaseName(rule.getDatabaseName());
        plan.setSchemaName(rule.getSchemaName());
        plan.setTableName(rule.getTableName());
        plan.setFieldName(rule.getFieldName());
        plan.setSampleLimit(safeSampleLimit(request == null ? null : request.getSampleLimit(), executionMode));
        plan.setMaxScannedRows(safeMaxScannedRows(request == null ? null : request.getMaxScannedRows(), executionMode));
        plan.setPartitionField(trimToNull(request == null ? null : request.getPartitionField()));
        plan.setWhereClause(trimToNull(request == null ? null : request.getWhereClause()));
        plan.setTimeoutSeconds(safeTimeoutSeconds(request == null ? null : request.getTimeoutSeconds(), executionMode));
        plan.setCollectAnomalySamples(request == null || request.getCollectAnomalySamples() == null
                ? Boolean.TRUE
                : request.getCollectAnomalySamples());
        plan.setAnomalySampleLimit(safeAnomalySampleLimit(request == null ? null : request.getAnomalySampleLimit()));
        plan.setPlannedTime(LocalDateTime.now());

        evaluateRelationalPlanSafety(plan, executionMode);
        return plan;
    }

    /**
     * 为不同执行模式设置抽样上限。
     */
    private Integer safeSampleLimit(Integer requested, QualityScanExecutionMode executionMode) {
        if (requested != null) {
            return requested;
        }
        return QualityScanExecutionMode.FULL_SCAN.equals(executionMode) ? null : 10000;
    }

    /**
     * 为不同执行模式设置最大扫描行数。
     */
    private Long safeMaxScannedRows(Long requested, QualityScanExecutionMode executionMode) {
        if (requested != null) {
            return requested;
        }
        if (QualityScanExecutionMode.FULL_SCAN.equals(executionMode)) {
            return 1000000L;
        }
        return 100000L;
    }

    /**
     * 设置超时时间。
     */
    private Integer safeTimeoutSeconds(Integer requested, QualityScanExecutionMode executionMode) {
        if (requested != null) {
            return requested;
        }
        return QualityScanExecutionMode.FULL_SCAN.equals(executionMode) ? 1800 : 300;
    }

    /**
     * 控制异常样本数量。
     */
    private Integer safeAnomalySampleLimit(Integer requested) {
        return requested == null ? 100 : requested;
    }

    /**
     * 评估计划风险并补充提示。
     */
    private void evaluateRelationalPlanSafety(QualityScanPlan plan, QualityScanExecutionMode executionMode) {
        plan.setSchedulable(true);
        plan.setRiskLevel("LOW");
        plan.setMessage("关系型质量扫描计划已生成，等待调度器或执行器消费");

        if (QualityScanExecutionMode.FULL_SCAN.equals(executionMode)) {
            plan.setRiskLevel("HIGH");
            plan.getWarnings().add("当前计划为全量扫描，可能对源库造成较高压力。");
            plan.getSuggestions().add("生产环境建议优先使用 SAMPLE_SCAN、PARTITION_SCAN 或 INCREMENTAL_WINDOW。");
        }
        if (QualityScanExecutionMode.PARTITION_SCAN.equals(executionMode)
                || QualityScanExecutionMode.INCREMENTAL_WINDOW.equals(executionMode)) {
            if (!hasText(plan.getPartitionField())) {
                plan.setSchedulable(false);
                plan.setRiskLevel("HIGH");
                plan.getWarnings().add("分区或增量窗口扫描缺少 partitionField，执行器无法确定扫描边界。");
            }
        }
        if (hasDangerousWhereClause(plan.getWhereClause())) {
            plan.setSchedulable(false);
            plan.setRiskLevel("HIGH");
            plan.getWarnings().add("whereClause 包含疑似危险 SQL 片段，当前计划禁止进入调度。");
            plan.getSuggestions().add("后续应改用参数化过滤模板，而不是直接提交自由文本 SQL 条件。");
        }
        if (plan.getMaxScannedRows() != null && plan.getMaxScannedRows() > 1000000L) {
            plan.setRiskLevel("HIGH");
            plan.getWarnings().add("最大扫描行数超过 100 万，建议拆分分区或改为抽样扫描。");
        }
        if (Boolean.TRUE.equals(plan.getCollectAnomalySamples())) {
            plan.getSuggestions().add("采集异常样本前应接入脱敏策略，避免 observedValue 或 samplePayload 泄露敏感数据。");
        }
    }

    /**
     * 对自由文本 where 条件做最基础的危险片段识别。
     *
     * <p>这不是完整 SQL 安全方案，只是扫描计划阶段的保护网。
     * 真正执行 SQL 前仍应使用参数化模板、SQL AST 解析和权限审计。
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
     * 去除空白字符串。
     */
    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
