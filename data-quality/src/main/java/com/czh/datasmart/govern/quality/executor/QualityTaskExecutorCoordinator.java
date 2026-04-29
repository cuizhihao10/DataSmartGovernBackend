/**
 * @Author : Cui
 * @Date: 2026/04/28 19:52
 * @Description DataSmart Govern Backend - QualityTaskExecutorCoordinator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyDetailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionFailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionStartRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionSuccessRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutorRunResult;
import com.czh.datasmart.govern.quality.controller.dto.RelationalQualityScanSqlPlan;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.executor.relational.RelationalQualitySqlTemplateBuilder;
import com.czh.datasmart.govern.quality.integration.datasource.DatasourceReadOnlySqlExecutionClient;
import com.czh.datasmart.govern.quality.integration.datasource.DatasourceReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.quality.integration.task.QualityTaskPayload;
import com.czh.datasmart.govern.quality.integration.task.QualityTaskPayloadParser;
import com.czh.datasmart.govern.quality.integration.task.TaskExecutionClaimResult;
import com.czh.datasmart.govern.quality.integration.task.TaskExecutionRunResponse;
import com.czh.datasmart.govern.quality.integration.task.TaskManagementClient;
import com.czh.datasmart.govern.quality.integration.task.TaskResponse;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 质量任务执行器协调器。
 *
 * <p>它是 data-quality 内部的“执行器编排层”，负责把 task-management 和 data-quality 的执行回调串起来：
 * 1. 从 task-management 认领 `DATA_QUALITY_SCAN` 任务；
 * 2. 解析并校验 `QUALITY_SCAN_TASK_V1` payload；
 * 3. 在 data-quality 创建 RUNNING execution；
 * 4. 向 task-management 上报心跳；
 * 5. 对当前已支持的关系型规则执行真实只读扫描，并把结果回写为 execution、report 和 task 终态；
 * 6. 对暂不支持的规则类型、目标类型或扫描策略走安全失败路径，避免生成伪成功报告。
 *
 * <p>为什么仍然保留“安全失败”：
 * 当前已经支持 COMPLETENESS / UNIQUENESS 的关系型受控扫描，但还没有覆盖所有质量规则和所有连接器。
 * 对 VALIDITY、ACCURACY、CONSISTENCY、Kafka、文件、API 等尚未完整建模的场景，真实商业化产品应该明确失败，
 * 而不是生成一份看似成功的质量报告。假成功会污染质量大盘、规则评分、审计证据和后续清洗流程。
 *
 * <p>当前 coordinator 只提供手动 runOnce，且默认关闭。
 * 后续真实扫描能力成熟后，可以在这个类之上扩展定时轮询、线程池并发、租户配额、公平调度、指标和告警。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityTaskExecutorCoordinator {

    private static final String OUTCOME_DISABLED = "DISABLED";
    private static final String OUTCOME_NO_TASK = "NO_TASK";
    private static final String OUTCOME_RELATIONAL_SCAN_SUCCEEDED = "RELATIONAL_SCAN_SUCCEEDED";
    private static final String OUTCOME_UNSUPPORTED_SCAN = "UNSUPPORTED_SCAN";
    private static final String OUTCOME_FAILED_TO_PROCESS = "FAILED_TO_PROCESS";
    private static final String OUTCOME_THROTTLED_DEFERRED = "THROTTLED_DEFERRED";
    private static final String OUTCOME_THROTTLED_DEAD_LETTER = "THROTTLED_DEAD_LETTER";

    private final TaskManagementIntegrationProperties properties;
    private final TaskManagementClient taskManagementClient;
    private final QualityTaskPayloadParser qualityTaskPayloadParser;
    private final DataQualityService dataQualityService;
    private final RelationalQualitySqlTemplateBuilder relationalQualitySqlTemplateBuilder;
    private final DatasourceReadOnlySqlExecutionClient datasourceReadOnlySqlExecutionClient;
    private final ObjectMapper objectMapper;
    private final QualityExecutorMetrics qualityExecutorMetrics;
    private final QualityExecutorConcurrencyGuard qualityExecutorConcurrencyGuard;

    /**
     * 手动或调度执行一次质量任务协调流程。
     *
     * <p>该方法仍然只处理一条任务。
     * 单条执行是最小、最容易排查的执行单元：无论它被手动接口调用，还是被后台 scheduler 调用，
     * 调用方都能清楚知道这一条任务经历了认领、心跳、扫描、报告回写和任务终态推进的哪一步。
     */
    public QualityExecutorRunResult runOnce() {
        return runOnce("MANUAL");
    }

    /**
     * 按指定触发来源执行一次质量任务协调流程。
     *
     * <p>trigger 用于指标标签，区分手动 run-once、手动 run-batch 和后台 scheduler。
     * 这对生产排障很重要：同样是 FAILED_TO_PROCESS，如果主要来自 SCHEDULER，说明后台自动消费链路需要关注；
     * 如果主要来自 MANUAL，可能只是运维人员正在做联调或复盘。
     */
    private QualityExecutorRunResult runOnce(String trigger) {
        long startNanos = System.nanoTime();
        QualityExecutorRunResult result = baseResult();
        if (!properties.isEnabled()) {
            result.setOutcome(OUTCOME_DISABLED);
            result.setMessage("task-management 集成未启用，coordinator 跳过执行");
            return recordAndReturn(trigger, result, startNanos);
        }
        if (!properties.isExecutorCoordinatorEnabled()) {
            result.setOutcome(OUTCOME_DISABLED);
            result.setMessage("质量执行器 coordinator 当前未启用，请开启 datasmart.quality.task-management.executor-coordinator-enabled");
            return recordAndReturn(trigger, result, startNanos);
        }

        TaskExecutionClaimResult claimResult = taskManagementClient.claimNextQualityTask(properties.getExecutorId());
        if (claimResult == null || !claimResult.claimed()) {
            result.setClaimed(false);
            result.setOutcome(OUTCOME_NO_TASK);
            result.setMessage(claimResult == null ? "未认领到任务" : claimResult.message());
            return recordAndReturn(trigger, result, startNanos);
        }

        result.setClaimed(true);
        TaskResponse task = claimResult.task();
        TaskExecutionRunResponse run = claimResult.executionRun();
        applyClaimSnapshot(result, task, run);

        Long qualityExecutionId = null;
        try {
            QualityTaskPayload payload = qualityTaskPayloadParser.parseAndValidate(task.getParams());
            applyPayloadSnapshot(result, payload);

            try (QualityExecutorConcurrencyGuard.Lease ignored =
                         qualityExecutorConcurrencyGuard.acquire(payload.getTenantId(), resolveDatasourceId(payload))) {
                /*
                 * 并发许可获取成功后才进入真正执行阶段。
                 * 如果护栏拒绝，任务不会再被标记为 FAILED，而是走 defer 回队列路径。
                 * 这样可以把“容量不足/背压保护”和“业务执行失败”区分开，避免失败率指标被容量波动污染。
                 */
                taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 10, "phase=PAYLOAD_VALIDATED");
                result.setHeartbeatSent(true);

                qualityExecutionId = startQualityExecution(task, run, payload);
                result.setQualityExecutionId(qualityExecutionId);
                taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 20, "phase=QUALITY_EXECUTION_STARTED");

                RelationalScanCompletion completion = executeSupportedRelationalScan(task, run, payload, qualityExecutionId);
                result.setMeasuredValue(completion.measuredValue());
                result.setSampleSize(completion.sampleSize());
                result.setExceptionCount(completion.exceptionCount());
                result.setReportId(completion.report().getId());
                result.setQualityExecutionFinalized(true);

                taskManagementClient.completeTask(task.getId(), buildTaskCompletionResult(completion));
                result.setTaskFinalized(true);
            }

            result.setOutcome(OUTCOME_RELATIONAL_SCAN_SUCCEEDED);
            result.setMessage("质量任务已完成关系型受控扫描，并成功生成质量报告");
            return recordAndReturn(trigger, result, startNanos);
        } catch (Exception ex) {
            handleProcessingFailure(result, task, qualityExecutionId, ex);
            return recordAndReturn(trigger, result, startNanos);
        }
    }

    /**
     * 从 payload 中解析数据源 ID。
     *
     * <p>数据源 ID 用于 data-quality 本实例的数据源级并发护栏。
     * 当前关系型扫描计划会携带 dataSourceId；如果未来 Kafka、文件、API 等计划没有该字段，
     * 会归入 UNKNOWN_DATASOURCE 这个兜底维度，仍然可以受到全局和租户维度保护。
     */
    private Long resolveDatasourceId(QualityTaskPayload payload) {
        if (payload == null || payload.getScanPlan() == null) {
            return null;
        }
        return payload.getScanPlan().getDataSourceId();
    }

    /**
     * 连续执行一小批质量任务。
     *
     * <p>后台调度器不直接写 while 循环，而是调用这个方法，是为了把“批量执行一轮”的业务语义收口到 coordinator：
     * - 什么时候停止继续认领：没有任务、coordinator 关闭或达到单轮上限；
     * - 每条任务仍然复用 runOnce，避免手动入口和自动入口出现两套执行逻辑；
     * - 后续如果要加入租户公平调度、连接器配额或执行指标，也可以在这里扩展。
     *
     * <p>当前批量上限由配置类保护，默认 1，最大 20。
     */
    public List<QualityExecutorRunResult> runBatch(int maxRuns) {
        return runBatch(maxRuns, "MANUAL_BATCH");
    }

    /**
     * 按指定触发来源连续执行一小批质量任务。
     *
     * <p>公开的 runBatch(int) 默认表示手动批量触发；后台 scheduler 会调用这个重载并传入 SCHEDULER。
     */
    public List<QualityExecutorRunResult> runBatch(int maxRuns, String trigger) {
        int safeMaxRuns = Math.max(1, Math.min(maxRuns, 20));
        List<QualityExecutorRunResult> results = new ArrayList<>();
        for (int index = 0; index < safeMaxRuns; index++) {
            QualityExecutorRunResult result = runOnce(trigger);
            results.add(result);
            if (!Boolean.TRUE.equals(result.getClaimed())) {
                break;
            }
        }
        return results;
    }

    /**
     * 记录单条执行指标并返回结果。
     *
     * <p>把指标记录收口到这个方法，是为了确保 DISABLED、NO_TASK、SUCCESS、FAILED 等所有 return 路径都会计数。
     * 如果在每个分支手写指标，很容易遗漏某个早返回分支，导致监控数据与真实行为不一致。
     */
    private QualityExecutorRunResult recordAndReturn(String trigger, QualityExecutorRunResult result, long startNanos) {
        qualityExecutorMetrics.recordCoordinatorRun(
                trigger,
                result,
                Duration.ofNanos(System.nanoTime() - startNanos));
        return result;
    }

    /**
     * 构造基础结果对象。
     */
    private QualityExecutorRunResult baseResult() {
        QualityExecutorRunResult result = new QualityExecutorRunResult();
        result.setCoordinatorEnabled(properties.isExecutorCoordinatorEnabled());
        result.setClaimed(false);
        result.setExecutorId(properties.getExecutorId());
        result.setHeartbeatSent(false);
        result.setTaskFinalized(false);
        result.setQualityExecutionFinalized(false);
        result.setRunTime(LocalDateTime.now());
        return result;
    }

    /**
     * 把 task-management 认领结果写入返回摘要。
     */
    private void applyClaimSnapshot(QualityExecutorRunResult result, TaskResponse task,
                                    TaskExecutionRunResponse run) {
        if (task != null) {
            result.setTaskId(task.getId());
        }
        if (run != null) {
            result.setTaskRunId(run.getId());
        }
    }

    /**
     * 把 payload 关键信息写入返回摘要。
     */
    private void applyPayloadSnapshot(QualityExecutorRunResult result, QualityTaskPayload payload) {
        result.setPayloadSchemaVersion(payload.getSchemaVersion());
        if (payload.getScanPlan() != null) {
            result.setTargetType(payload.getScanPlan().getTargetType());
            result.setScanStrategy(payload.getScanPlan().getScanStrategy());
        }
    }

    /**
     * 在 data-quality 创建 RUNNING execution。
     *
     * <p>scanPlanSnapshot 使用 JSON 保存，而不是保存 Java 对象引用。
     * 这是为了与 `quality_check_execution.scan_plan_snapshot` 字段对齐，保证历史执行记录可审计。
     */
    private Long startQualityExecution(TaskResponse task, TaskExecutionRunResponse run,
                                       QualityTaskPayload payload) throws JsonProcessingException {
        QualityExecutionStartRequest request = new QualityExecutionStartRequest();
        request.setRuleId(payload.getRuleId());
        request.setTaskId(task.getId());
        request.setTaskRunId(run.getId());
        request.setExecutorId(properties.getExecutorId());
        request.setScanPlanSnapshot(objectMapper.writeValueAsString(payload.getScanPlan()));
        request.setMessage("质量执行器已认领任务并完成 payload 校验，准备进入真实扫描阶段");
        return dataQualityService.startTaskExecution(request).getId();
    }

    /**
     * 执行当前已支持的关系型质量扫描。
     *
     * <p>这一步是 2.46 和 2.47 两个能力的真正衔接点：
     * 1. 2.46 已经能把规则和扫描计划转换成 metric SQL / anomaly sample SQL；
     * 2. 2.47 在 datasource-management 中提供了受控只读 SQL 执行代理；
     * 3. 当前方法把二者串起来，生成指标、采集少量异常样本、回写质量报告、最后完成任务中心状态。
     *
     * <p>注意：这里仍然只支持 SQL 模板构建器明确 supported=true 的场景。
     * 对尚未建模清楚的 VALIDITY、CONSISTENCY、ACCURACY 等规则，继续走失败路径，
     * 这是为了保护报告可信度，而不是追求“看起来什么都能跑”的演示效果。
     */
    private RelationalScanCompletion executeSupportedRelationalScan(TaskResponse task,
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

        List<QualityAnomalyDetailRequest> anomalies = executeAndConvertAnomalySamples(rule, payload, sqlPlan, metricSnapshot);
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
     * 查询质量规则快照。
     *
     * <p>payload 中保存了规则核心字段快照，但 SQL 构建器仍需要当前规则的结构字段和阈值字段。
     * 当前先从 data-quality 本地规则表读取；后续如果要完全按任务创建时快照执行，
     * 应把 tableName、fieldName、targetType、comparisonOperator 等字段也固化进 payload。
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
     * <p>关系型 SQL 模板约定返回三列：
     * - sample_size：参与检测的数据量；
     * - exception_count：异常数量；
     * - measured_value：用于和规则阈值比较的观测值。
     *
     * <p>如果任意列缺失或无法转换，说明 SQL 模板、远程执行器或数据库方言存在问题，
     * 此时应让任务失败，而不是用默认值生成报告。
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
     * 按需执行异常样本 SQL 并转换成 data-quality 的异常明细请求。
     *
     * <p>只有 exception_count 大于 0 时才执行样本 SQL。
     * 这能减少没有异常时对源库的二次访问，也能避免报告里出现无意义的空样本列表。
     */
    private List<QualityAnomalyDetailRequest> executeAndConvertAnomalySamples(QualityRule rule,
                                                                              QualityTaskPayload payload,
                                                                              RelationalQualityScanSqlPlan sqlPlan,
                                                                              MetricSnapshot metricSnapshot) throws JsonProcessingException {
        if (metricSnapshot.exceptionCount() == null || metricSnapshot.exceptionCount() <= 0 || !hasText(sqlPlan.getAnomalySampleSql())) {
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
     * 构造成功回写说明。
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
     * 构造 task-management 完成结果。
     *
     * <p>task-management 的 result 字段当前是文本，因此这里写入一段 JSON 摘要，
     * 让任务中心即使不直接查询 data-quality，也能看到报告 ID、质量结果和关键指标。
     */
    private String buildTaskCompletionResult(RelationalScanCompletion completion) throws JsonProcessingException {
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
     * 构造暂不支持真实扫描的安全失败原因。
     */
    private String buildUnsupportedScanMessage(RelationalQualityScanSqlPlan sqlPlan) {
        return "当前质量执行器仅支持关系型 COMPLETENESS/UNIQUENESS SQL 扫描，当前任务暂不支持: "
                + "ruleType=" + sqlPlan.getRuleType()
                + ", targetType=" + sqlPlan.getTargetType()
                + ", reason=" + sqlPlan.getMessage();
    }

    /**
     * 如果已经创建质量 execution，则把它标记为 FAILED。
     */
    private void failQualityExecutionIfStarted(Long qualityExecutionId, String errorType,
                                               String message, boolean retryable) {
        if (qualityExecutionId == null) {
            return;
        }
        QualityExecutionFailRequest request = new QualityExecutionFailRequest();
        request.setErrorType(errorType);
        request.setErrorMessage(message);
        request.setRetryable(retryable);
        dataQualityService.failTaskExecution(qualityExecutionId, request);
    }

    /**
     * 处理 coordinator 运行过程中的异常。
     *
     * <p>如果任务已经被认领但处理失败，必须尽量把 task-management 推进到一个明确状态。
     * 对真正业务失败，标记 FAILED；对并发护栏拒绝这种容量背压，标记 DEFERRED 并延迟回队列。
     * 否则任务会停留在 RUNNING，直到租约超时恢复，用户体验和排障效率都会变差。
     */
    private void handleProcessingFailure(QualityExecutorRunResult result, TaskResponse task,
                                         Long qualityExecutionId, Exception ex) {
        if (ex instanceof QualityExecutorConcurrencyGuard.ConcurrencyRejectedException concurrencyRejectedException) {
            handleConcurrencyRejected(result, task, qualityExecutionId, concurrencyRejectedException);
            return;
        }
        boolean unsupportedScan = ex instanceof UnsupportedOperationException;
        String message = unsupportedScan
                ? ex.getMessage()
                : "质量执行器 coordinator 处理任务失败: " + ex.getMessage();
        result.setOutcome(unsupportedScan ? OUTCOME_UNSUPPORTED_SCAN : OUTCOME_FAILED_TO_PROCESS);
        result.setMessage(message);
        result.setErrorMessage(message);
        log.warn("质量执行器 coordinator 处理失败，taskId={}, taskRunId={}",
                result.getTaskId(), result.getTaskRunId(), ex);
        if (qualityExecutionId != null && !Boolean.TRUE.equals(result.getQualityExecutionFinalized())) {
            try {
                failQualityExecutionIfStarted(
                        qualityExecutionId,
                        unsupportedScan ? "SCAN_STRATEGY_UNSUPPORTED" : "COORDINATOR_PROCESSING_FAILED",
                        message,
                        !unsupportedScan);
                result.setQualityExecutionFinalized(true);
            } catch (Exception failQualityEx) {
                log.warn("质量执行器 coordinator 标记 data-quality execution 失败时再次失败，executionId={}",
                        qualityExecutionId, failQualityEx);
            }
        }
        if (task != null && task.getId() != null) {
            try {
                taskManagementClient.failTask(task.getId(), message);
                result.setTaskFinalized(true);
            } catch (Exception failTaskEx) {
                log.warn("质量执行器 coordinator 标记 task-management 任务失败时再次失败，taskId={}",
                        task.getId(), failTaskEx);
            }
        }
    }

    /**
     * 处理执行器并发护栏拒绝。
     *
     * <p>并发护栏拒绝不是扫描失败，而是当前 data-quality 实例为了保护自身、租户和客户源库主动做的背压。
     * 因此这里不调用 taskManagementClient.failTask，而是调用 deferTask：
     * 1. task-management 会把当前 run 标记为 DEFERRED；
     * 2. task 主状态进入 DEFERRED，queued_time 推迟到未来；
     * 3. 到期后 claim 查询会重新认领该任务；
     * 4. 运营指标可以把 THROTTLED_DEFERRED 和 FAILED_TO_PROCESS 分开观察。
     *
     * <p>如果 data-quality execution 已经被创建，说明拒绝发生在更晚阶段。
     * 当前并发护栏理论上发生在 startQualityExecution 之前，但这里仍保留防御式失败回写，
     * 避免未来调整流程后留下 RUNNING execution。
     */
    private void handleConcurrencyRejected(QualityExecutorRunResult result,
                                           TaskResponse task,
                                           Long qualityExecutionId,
                                           QualityExecutorConcurrencyGuard.ConcurrencyRejectedException ex) {
        String message = "质量执行器并发护栏触发，任务已延迟回队列: scope="
                + ex.getScope() + ", reason=" + ex.getMessage();
        result.setOutcome(OUTCOME_THROTTLED_DEFERRED);
        result.setMessage(message);
        result.setErrorMessage(message);
        log.info("质量执行器并发护栏触发，准备延迟 task-management 任务，taskId={}, taskRunId={}, scope={}, deferSeconds={}",
                result.getTaskId(), result.getTaskRunId(), ex.getScope(), properties.getSafeExecutorThrottleDeferSeconds());

        if (qualityExecutionId != null && !Boolean.TRUE.equals(result.getQualityExecutionFinalized())) {
            try {
                failQualityExecutionIfStarted(
                        qualityExecutionId,
                        "CONCURRENCY_GUARD_REJECTED",
                        message,
                        true);
                result.setQualityExecutionFinalized(true);
            } catch (Exception failQualityEx) {
                log.warn("质量执行器并发护栏触发后，标记 data-quality execution 失败时再次失败，executionId={}",
                        qualityExecutionId, failQualityEx);
            }
        }

        if (task != null && task.getId() != null) {
            try {
                TaskResponse deferredTask = taskManagementClient.deferTask(
                        task.getId(),
                        message,
                        properties.getSafeExecutorThrottleDeferSeconds());
                if (deferredTask != null && "DEAD_LETTER".equals(deferredTask.getStatus())) {
                    result.setOutcome(OUTCOME_THROTTLED_DEAD_LETTER);
                    result.setMessage(message + "；任务已超过最大连续退避次数并进入 DEAD_LETTER，需要运营人员处理。");
                }
                result.setTaskFinalized(true);
            } catch (Exception deferTaskEx) {
                log.warn("质量执行器并发护栏触发后，延迟 task-management 任务失败，taskId={}",
                        task.getId(), deferTaskEx);
            }
        }
    }

    /**
     * 从大小写不敏感的行 Map 中读取 BigDecimal 指标。
     */
    private BigDecimal readBigDecimalMetric(Map<String, Object> row, String columnName) {
        Object value = findValueIgnoreCase(row, columnName);
        if (value == null) {
            throw new IllegalStateException("metric SQL 缺少指标列: " + columnName);
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    /**
     * 从大小写不敏感的行 Map 中读取整数指标。
     */
    private Integer readIntegerMetric(Map<String, Object> row, String columnName) {
        Object value = findValueIgnoreCase(row, columnName);
        if (value == null) {
            throw new IllegalStateException("metric SQL 缺少指标列: " + columnName);
        }
        return new BigDecimal(String.valueOf(value).trim()).intValue();
    }

    /**
     * 在远程 SQL 执行结果中按列名大小写不敏感查值。
     */
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

    /**
     * 推导异常类型编码。
     */
    private String resolveAnomalyType(QualityTaskPayload payload) {
        if ("COMPLETENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "NULL_VALUE";
        }
        if ("UNIQUENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "DUPLICATE_VALUE";
        }
        return "QUALITY_RULE_VIOLATION";
    }

    /**
     * 构造期望值描述。
     */
    private String buildExpectedValueDescription(QualityTaskPayload payload) {
        return "operator=" + payload.getComparisonOperator() + ", expectedValue=" + payload.getExpectedValue();
    }

    /**
     * 推导处理建议。
     */
    private String resolveRecommendation(QualityTaskPayload payload) {
        if ("COMPLETENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "建议回源补齐字段值，或在清洗任务中补默认值/人工复核。";
        }
        if ("UNIQUENESS".equalsIgnoreCase(payload.getRuleType())) {
            return "建议按业务主键或唯一约束定位重复记录，确认保留策略后执行去重或合并。";
        }
        return "建议进入质量运营台人工复核，并补充更具体的规则参数。";
    }

    /**
     * 构造样本记录定位信息。
     *
     * <p>当前 SQL 模板还没有把主键字段显式投影出来，因此先用样本序号兜底。
     * 后续应结合 datasource-management 的主键元数据，把主键值写入 recordIdentifier。
     */
    private String buildRecordIdentifier(int index, Map<String, Object> row) {
        Object id = findValueIgnoreCase(row, "id");
        if (id != null) {
            return "id=" + id;
        }
        return "sample_index=" + index;
    }

    /**
     * 把任意值转成字符串。
     */
    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 判断字符串是否有真实内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 截断字符串，避免异常样本过长导致数据库字段或响应体膨胀。
     */
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
    private record RelationalScanCompletion(QualityCheckReport report,
                                            BigDecimal measuredValue,
                                            Integer sampleSize,
                                            Integer exceptionCount,
                                            Integer anomalySampleCount) {
    }
}
