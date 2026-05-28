/**
 * @Author : Cui
 * @Date: 2026/04/28 19:52
 * @Description DataSmart Govern Backend - QualityTaskExecutorCoordinator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionStartRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutorRunResult;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量任务执行器协调器。
 *
 * <p>本类只保留质量任务执行闭环的“主流程编排”，不再直接承载关系型 SQL 执行细节和失败补偿细节。
 * 这样拆分后的阅读顺序非常接近真实业务流程：
 * 1. 判断执行器能力是否开启；
 * 2. 从 task-management 认领 DATA_QUALITY_SCAN 任务；
 * 3. 解析并校验质量任务 payload；
 * 4. 在 data-quality 创建 RUNNING execution；
 * 5. 调用具体扫描器完成真实扫描；
 * 6. 回写质量报告并把 task-management 推进到终态。
 *
 * <p>为什么 coordinator 不继续直接执行 SQL：
 * coordinator 是“流程导演”，不应该同时变成“SQL 执行员”“异常补偿员”“指标解析员”。
 * 这些细节拆到 {@link QualityRelationalScanExecutor} 与 {@link QualityExecutionFailureSupport} 后，
 * 后续新增 Kafka 扫描器、文件扫描器、API 响应扫描器或 Python AI 执行器时，可以新增专门组件，
 * 而不是继续扩大本类，避免重新形成超过 500 行的胖服务。
 */
@Component
@RequiredArgsConstructor
public class QualityTaskExecutorCoordinator {

    private final TaskManagementIntegrationProperties properties;
    private final TaskManagementClient taskManagementClient;
    private final QualityTaskPayloadParser qualityTaskPayloadParser;
    private final DataQualityService dataQualityService;
    private final ObjectMapper objectMapper;
    private final QualityExecutorMetrics qualityExecutorMetrics;
    private final QualityExecutorConcurrencyGuard qualityExecutorConcurrencyGuard;
    private final QualityRelationalScanExecutor qualityRelationalScanExecutor;
    private final QualityExecutionFailureSupport qualityExecutionFailureSupport;

    /**
     * 手动执行一次质量任务协调流程。
     *
     * <p>该入口通常由 `/quality-rules/executor/coordinator/run-once` 调用。
     * 它每次最多处理一条任务，适合本地联调、故障复盘和观察单条任务完整状态流转。
     */
    public QualityExecutorRunResult runOnce() {
        return runOnce("MANUAL");
    }

    /**
     * 按指定触发来源执行一次质量任务协调流程。
     *
     * @param trigger 触发来源，例如 MANUAL、MANUAL_BATCH、SCHEDULER。
     * @return 单条任务执行摘要，供接口、日志和指标消费。
     */
    private QualityExecutorRunResult runOnce(String trigger) {
        long startNanos = System.nanoTime();
        QualityExecutorRunResult result = baseResult();
        if (!properties.isEnabled()) {
            result.setOutcome(QualityExecutorOutcome.DISABLED);
            result.setMessage("task-management 集成未启用，coordinator 跳过执行");
            return recordAndReturn(trigger, result, startNanos);
        }
        if (!properties.isExecutorCoordinatorEnabled()) {
            result.setOutcome(QualityExecutorOutcome.DISABLED);
            result.setMessage("质量执行器 coordinator 当前未启用，请开启 datasmart.quality.task-management.executor-coordinator-enabled");
            return recordAndReturn(trigger, result, startNanos);
        }

        TaskExecutionClaimResult claimResult = taskManagementClient.claimNextQualityTask(properties.getExecutorId());
        if (claimResult == null || !claimResult.claimed()) {
            result.setClaimed(false);
            result.setOutcome(QualityExecutorOutcome.NO_TASK);
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
                 * 并发许可获取成功后才进入真实执行阶段。
                 * 如果护栏拒绝，异常会被失败收口组件识别为“容量背压”，并通过 defer 回队列，而不是标记业务失败。
                 */
                taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 10, "phase=PAYLOAD_VALIDATED");
                result.setHeartbeatSent(true);

                qualityExecutionId = startQualityExecution(task, run, payload);
                result.setQualityExecutionId(qualityExecutionId);
                taskManagementClient.heartbeatExecution(run.getId(), properties.getExecutorId(), 20, "phase=QUALITY_EXECUTION_STARTED");

                QualityRelationalScanExecutor.RelationalScanCompletion completion =
                        qualityRelationalScanExecutor.execute(task, run, payload, qualityExecutionId);
                applyCompletionSnapshot(result, completion);

                taskManagementClient.completeTask(
                        task.getId(),
                        run.getId(),
                        properties.getExecutorId(),
                        qualityRelationalScanExecutor.buildTaskCompletionResult(completion));
                result.setTaskFinalized(true);
            }

            result.setOutcome(QualityExecutorOutcome.RELATIONAL_SCAN_SUCCEEDED);
            result.setMessage("质量任务已完成关系型受控扫描，并成功生成质量报告");
            return recordAndReturn(trigger, result, startNanos);
        } catch (Exception ex) {
            qualityExecutionFailureSupport.handleFailure(result, task, qualityExecutionId, ex);
            return recordAndReturn(trigger, result, startNanos);
        }
    }

    /**
     * 连续执行一小批质量任务。
     *
     * <p>批量入口仍然复用单条 runOnce，避免手动入口、批量入口和 scheduler 出现三套不同执行逻辑。
     * 当前最大批量会压到 20，后续如需更高吞吐，应引入线程池、租户公平调度和数据源配额，而不是简单拉高循环次数。
     */
    public List<QualityExecutorRunResult> runBatch(int maxRuns) {
        return runBatch(maxRuns, "MANUAL_BATCH");
    }

    /**
     * 按指定触发来源连续执行一小批质量任务。
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
     * <p>统一在这里记录指标，可以保证 DISABLED、NO_TASK、SUCCESS、FAILED 等所有 return 路径都被计数。
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
     * 把 payload 关键上下文写入返回摘要。
     */
    private void applyPayloadSnapshot(QualityExecutorRunResult result, QualityTaskPayload payload) {
        result.setPayloadSchemaVersion(payload.getSchemaVersion());
        if (payload.getScanPlan() != null) {
            result.setTargetType(payload.getScanPlan().getTargetType());
            result.setScanStrategy(payload.getScanPlan().getScanStrategy());
        }
    }

    /**
     * 把真实扫描完成后的指标写入返回摘要。
     */
    private void applyCompletionSnapshot(QualityExecutorRunResult result,
                                         QualityRelationalScanExecutor.RelationalScanCompletion completion) {
        result.setMeasuredValue(completion.measuredValue());
        result.setSampleSize(completion.sampleSize());
        result.setExceptionCount(completion.exceptionCount());
        result.setReportId(completion.report().getId());
        result.setQualityExecutionFinalized(true);
    }

    /**
     * 在 data-quality 创建 RUNNING execution。
     *
     * <p>scanPlanSnapshot 使用 JSON 保存，而不是保存 Java 对象引用。
     * 这是为了对齐 `quality_check_execution.scan_plan_snapshot` 的审计语义，保证历史执行记录能解释当时按什么计划运行。
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
     * 从 payload 中解析数据源 ID。
     *
     * <p>数据源 ID 用于本实例的数据源级并发护栏。
     * 如果未来 Kafka、文件或 API 扫描计划没有 dataSourceId，会归入 UNKNOWN_DATASOURCE 兜底维度。
     */
    private Long resolveDatasourceId(QualityTaskPayload payload) {
        if (payload == null || payload.getScanPlan() == null) {
            return null;
        }
        return payload.getScanPlan().getDataSourceId();
    }
}
