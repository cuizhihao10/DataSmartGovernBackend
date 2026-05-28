/**
 * @Author : Cui
 * @Date: 2026/05/05 23:18
 * @Description DataSmart Govern Backend - QualityRelationalScanExecutor.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyDetailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionSuccessRequest;
import com.czh.datasmart.govern.quality.controller.dto.RelationalQualityScanSqlPlan;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.executor.relational.RelationalQualitySqlTemplateBuilder;
import com.czh.datasmart.govern.quality.integration.datasource.DatasourceReadOnlySqlExecutionClient;
import com.czh.datasmart.govern.quality.integration.datasource.DatasourceReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.quality.integration.task.QualityTaskPayload;
import com.czh.datasmart.govern.quality.integration.task.TaskExecutionRunResponse;
import com.czh.datasmart.govern.quality.integration.task.TaskManagementClient;
import com.czh.datasmart.govern.quality.integration.task.TaskResponse;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 关系型质量扫描执行器。
 *
 * <p>这个类专门承载“已经被确认可执行的关系型质量规则如何真正跑完”的细节。
 * 它把 coordinator 中最容易膨胀的部分拆出来：SQL 计划生成、受控只读 SQL 执行、指标解析、异常样本转换和质量报告回写。
 *
 * <p>职责边界说明：
 * 1. 它不认领任务，认领仍由 {@link QualityTaskExecutorCoordinator} 编排；
 * 2. 它不直接连接客户源库，而是通过 datasource-management 的只读 SQL 代理执行；
 * 3. 它不决定任务最终 FAILED 还是 DEFERRED，异常收口由 {@link QualityExecutionFailureSupport} 处理；
 * 4. 它只在扫描动作真实完成后调用 data-quality 的 success 回调生成 report。
 *
 * <p>当前仅支持 COMPLETENESS / UNIQUENESS 的关系型 SQL 扫描。
 * 这不是能力边界的终点，而是商业化演进的安全起点：先把最容易验证和审计的两类规则跑通，再扩展有效性、
 * 准确性、一致性、跨表校验、Kafka 窗口校验、文件质量校验和 API 响应质量校验。
 */
@Component
@RequiredArgsConstructor
public class QualityRelationalScanExecutor {

    private final TaskManagementIntegrationProperties properties;
    private final TaskManagementClient taskManagementClient;
    private final DataQualityService dataQualityService;
    private final RelationalQualitySqlTemplateBuilder relationalQualitySqlTemplateBuilder;
    private final DatasourceReadOnlySqlExecutionClient datasourceReadOnlySqlExecutionClient;
    private final ObjectMapper objectMapper;

    /**
     * 执行一次受支持的关系型质量扫描。
     *
     * @param task task-management 任务快照，用于写入完成结果。
     * @param run task-management 单次运行快照，用于上报阶段性心跳。
     * @param payload 质量任务 payload，包含规则快照和扫描计划快照。
     * @param qualityExecutionId data-quality execution ID，用于成功回写 report。
     * @return 扫描完成快照，coordinator 会把它用于 task-management complete。
     */
    public RelationalScanCompletion execute(TaskResponse task,
                                            TaskExecutionRunResponse run,
                                            QualityTaskPayload payload,
                                            Long qualityExecutionId) throws JsonProcessingException {
        QualityRule rule = getRequiredRule(payload.getRuleId());
        RelationalQualityScanSqlPlan sqlPlan = relationalQualitySqlTemplateBuilder.build(rule, payload.getScanPlan());
        if (!Boolean.TRUE.equals(sqlPlan.getSupported())) {
            throw new UnsupportedOperationException(buildUnsupportedScanMessage(sqlPlan));
        }

        taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 35, "phase=RELATIONAL_SQL_PLAN_READY");

        DatasourceReadOnlySqlExecutionResult metricResult =
                datasourceReadOnlySqlExecutionClient.executeMetricSql(
                        sqlPlan.getDataSourceId(),
                        sqlPlan.getMetricSql(),
                        sqlPlan.getTimeoutSeconds());
        MetricSnapshot metricSnapshot = parseMetricSnapshot(metricResult);
        taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 65, "phase=QUALITY_METRIC_SQL_EXECUTED");

        List<QualityAnomalyDetailRequest> anomalies =
                executeAndConvertAnomalySamples(rule, payload, sqlPlan, metricSnapshot);
        taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 85, "phase=QUALITY_ANOMALY_SAMPLE_COLLECTED");

        QualityExecutionSuccessRequest successRequest = new QualityExecutionSuccessRequest();
        successRequest.setMeasuredValue(metricSnapshot.measuredValue());
        successRequest.setSampleSize(metricSnapshot.sampleSize());
        successRequest.setExceptionCount(metricSnapshot.exceptionCount());
        successRequest.setAnomalies(anomalies);
        successRequest.setNotes(buildSuccessNotes(sqlPlan, metricResult, anomalies));

        QualityCheckReport report = dataQualityService.completeTaskExecution(qualityExecutionId, successRequest);
        taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 100, "phase=QUALITY_REPORT_CREATED");
        return new RelationalScanCompletion(report,
                metricSnapshot.measuredValue(),
                metricSnapshot.sampleSize(),
                metricSnapshot.exceptionCount(),
                anomalies.size());
    }

    /**
     * 构造 task-management complete 接口需要保存的结果摘要。
     *
     * <p>task-management 当前的 result 是文本字段，因此这里写入 JSON 摘要。
     * 这样任务中心即使不跨服务查询 data-quality，也能展示 reportId、质量判定状态和核心指标。
     */
    public String buildTaskCompletionResult(RelationalScanCompletion completion) throws JsonProcessingException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", completion.report().getId());
        result.put("checkStatus", completion.report().getCheckStatus());
        result.put("measuredValue", completion.measuredValue());
        result.put("sampleSize", completion.sampleSize());
        result.put("exceptionCount", completion.exceptionCount());
        result.put("anomalySampleCount", completion.anomalySampleCount());
        return objectMapper.writeValueAsString(result);
    }

    /**
     * 查询质量规则当前快照。
     *
     * <p>payload 已保存规则关键字段，但 SQL 构建仍需要 tableName、fieldName、targetType 等结构字段。
     * 后续如果要完全按任务创建时快照执行，应把这些字段也固化进 payload，降低规则变更对历史任务的影响。
     */
    private QualityRule getRequiredRule(Long ruleId) {
        QualityRule rule = dataQualityService.getById(ruleId);
        if (rule == null) {
            throw new NoSuchElementException("质量规则不存在: " + ruleId);
        }
        return rule;
    }

    /**
     * 解析 metric SQL 返回的核心指标。
     *
     * <p>关系型 SQL 模板约定返回 sample_size、exception_count、measured_value。
     * 任一字段缺失都代表 SQL 模板、远程执行器或数据库方言存在问题，必须失败，而不能用默认值伪造报告。
     */
    private MetricSnapshot parseMetricSnapshot(DatasourceReadOnlySqlExecutionResult metricResult) {
        if (metricResult == null || metricResult.getRows() == null || metricResult.getRows().isEmpty()) {
            throw new IllegalStateException("metric SQL 未返回任何指标行");
        }
        Map<String, Object> row = metricResult.getRows().get(0);
        BigDecimal measuredValue = readBigDecimalMetric(row, "measured_value");
        Integer sampleSize = readIntegerMetric(row, "sample_size");
        Integer exceptionCount = readIntegerMetric(row, "exception_count");
        return new MetricSnapshot(measuredValue, sampleSize, exceptionCount);
    }

    /**
     * 按需执行异常样本 SQL，并转换为 data-quality 的异常明细请求。
     *
     * <p>只有 exception_count 大于 0 时才执行样本 SQL，避免“没有异常”时仍然二次访问源库。
     * 样本只作为定位证据，不是全量异常清单；真实大规模场景后续应支持分页上报、对象存储归档和脱敏策略。
     */
    private List<QualityAnomalyDetailRequest> executeAndConvertAnomalySamples(QualityRule rule,
                                                                              QualityTaskPayload payload,
                                                                              RelationalQualityScanSqlPlan sqlPlan,
                                                                              MetricSnapshot metricSnapshot) throws JsonProcessingException {
        if (metricSnapshot.exceptionCount() == null
                || metricSnapshot.exceptionCount() <= 0
                || !hasText(sqlPlan.getAnomalySampleSql())) {
            return List.of();
        }

        DatasourceReadOnlySqlExecutionResult sampleResult =
                datasourceReadOnlySqlExecutionClient.executeAnomalySampleSql(
                        sqlPlan.getDataSourceId(),
                        sqlPlan.getAnomalySampleSql(),
                        sqlPlan.getAnomalySampleLimit(),
                        sqlPlan.getTimeoutSeconds());
        if (sampleResult == null || sampleResult.getRows() == null || sampleResult.getRows().isEmpty()) {
            return List.of();
        }

        List<QualityAnomalyDetailRequest> anomalies = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> row : sampleResult.getRows()) {
            QualityAnomalyDetailRequest anomaly = new QualityAnomalyDetailRequest();
            anomaly.setAnomalyType(resolveAnomalyType(payload));
            anomaly.setFieldName(rule.getFieldName());
            anomaly.setRecordIdentifier(buildRecordIdentifier(index++, row));
            anomaly.setObservedValue(truncate(valueAsString(findValueIgnoreCase(row, rule.getFieldName())), 1024));
            anomaly.setExpectedValue(truncate(buildExpectedValueDescription(payload), 1024));
            anomaly.setSeverity(payload.getSeverity());
            anomaly.setRecommendation(resolveRecommendation(payload));
            anomaly.setSamplePayload(truncate(objectMapper.writeValueAsString(row), 4000));
            anomalies.add(anomaly);
        }
        return anomalies;
    }

    /**
     * 构造成功回写说明，保存扫描执行的关键上下文。
     */
    private String buildSuccessNotes(RelationalQualityScanSqlPlan sqlPlan,
                                     DatasourceReadOnlySqlExecutionResult metricResult,
                                     List<QualityAnomalyDetailRequest> anomalies) throws JsonProcessingException {
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("message", "关系型质量扫描通过 datasource-management 受控只读 SQL 执行完成");
        notes.put("datasourceId", sqlPlan.getDataSourceId());
        notes.put("ruleType", sqlPlan.getRuleType());
        notes.put("targetType", sqlPlan.getTargetType());
        notes.put("tableExpression", sqlPlan.getTableExpression());
        notes.put("fieldName", sqlPlan.getFieldName());
        notes.put("metricDurationMs", metricResult == null ? null : metricResult.getDurationMs());
        notes.put("anomalySampleCount", anomalies == null ? 0 : anomalies.size());
        notes.put("warnings", sqlPlan.getWarnings());
        return objectMapper.writeValueAsString(notes);
    }

    /**
     * 构造暂不支持扫描的安全失败原因。
     */
    private String buildUnsupportedScanMessage(RelationalQualityScanSqlPlan sqlPlan) {
        return "当前质量执行器仅支持关系型 COMPLETENESS/UNIQUENESS SQL 扫描，当前任务暂不支持: "
                + "ruleType=" + sqlPlan.getRuleType()
                + ", targetType=" + sqlPlan.getTargetType()
                + ", reason=" + sqlPlan.getMessage();
    }

    private BigDecimal readBigDecimalMetric(Map<String, Object> row, String columnName) {
        Object value = findValueIgnoreCase(row, columnName);
        if (value == null) {
            throw new IllegalStateException("metric SQL 缺少指标列: " + columnName);
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    private Integer readIntegerMetric(Map<String, Object> row, String columnName) {
        Object value = findValueIgnoreCase(row, columnName);
        if (value == null) {
            throw new IllegalStateException("metric SQL 缺少指标列: " + columnName);
        }
        return new BigDecimal(String.valueOf(value).trim()).intValue();
    }

    private Object findValueIgnoreCase(Map<String, Object> row, String columnName) {
        if (row == null || columnName == null) {
            return null;
        }
        return row.entrySet().stream()
                .filter(entry -> columnName.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String resolveAnomalyType(QualityTaskPayload payload) {
        if ("COMPLETENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "NULL_VALUE";
        }
        if ("UNIQUENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "DUPLICATE_VALUE";
        }
        return "QUALITY_RULE_VIOLATION";
    }

    private String buildExpectedValueDescription(QualityTaskPayload payload) {
        return "operator=" + payload.getComparisonOperator() + ", expectedValue=" + payload.getExpectedValue();
    }

    private String resolveRecommendation(QualityTaskPayload payload) {
        if ("COMPLETENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "建议回源补齐字段值，或在清洗任务中补默认值/人工复核。";
        }
        if ("UNIQUENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "建议按业务主键或唯一约束定位重复记录，确认保留策略后执行去重或合并。";
        }
        return "建议进入质量运营台人工复核，并补充更具体的规则参数。";
    }

    private String buildRecordIdentifier(int index, Map<String, Object> row) {
        Object id = findValueIgnoreCase(row, "id");
        if (id != null) {
            return "id=" + id;
        }
        return "sample_index=" + index;
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * metric SQL 解析后的指标快照。
     */
    private record MetricSnapshot(BigDecimal measuredValue, Integer sampleSize, Integer exceptionCount) {
    }

    /**
     * 一次关系型扫描成功后的结果快照。
     */
    public record RelationalScanCompletion(QualityCheckReport report,
                                           BigDecimal measuredValue,
                                           Integer sampleSize,
                                           Integer exceptionCount,
                                           Integer anomalySampleCount) {
    }
}
