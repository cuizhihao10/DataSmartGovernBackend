/**
 * @Author : Cui
 * @Date: 2026/04/29 00:42
 * @Description DataSmart Govern Backend - QualityExecutorMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutorRunResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 质量执行器指标采集组件。
 *
 * <p>这个类把 Micrometer 指标写入逻辑从 scheduler/coordinator 中拆出来。
 * 这样做有两个好处：
 * 1. 执行业务流程代码不需要到处关心 Counter、Timer、Tag 的细节；
 * 2. 后续如果统一切换指标命名、增加 Prometheus 标签、接入 OpenTelemetry 或关闭某些指标，只需要调整这一处。
 *
 * <p>当前指标主要解决“质量执行器是否健康运行”的第一批生产问题：
 * - 调度器有没有触发；
 * - 每轮调度认领了多少任务；
 * - 单条执行最终 outcome 是成功、无任务、禁用、暂不支持还是处理失败；
 * - 单条执行耗时和单轮调度耗时是多少；
 * - 调度器是否发生重入跳过或未捕获异常。
 *
 * <p>指标标签设计必须谨慎。这里刻意不把 taskId、taskRunId、qualityExecutionId、traceId 放入标签，
 * 因为这些值几乎每次都不同，会造成“高基数时序”问题，让 Prometheus、VictoriaMetrics 或其他时序库压力暴涨。
 * 当前只使用 trigger、taskType、outcome、claimed、result 等低基数字段，适合作为第一版商业化观测基础。
 */
@Component
@RequiredArgsConstructor
public class QualityExecutorMetrics {

    /**
     * Micrometer 指标注册表。
     *
     * <p>Spring Boot Actuator 会自动提供 MeterRegistry。
     * 本地开发时可以通过 `/actuator/metrics` 查看指标；接入 Prometheus registry 后可以通过抓取端点进入 Grafana。
     */
    private final MeterRegistry meterRegistry;

    /**
     * 质量执行器配置。
     *
     * <p>这里主要读取 taskType，作为低基数标签写入指标。
     * 不直接使用 executorId 作为默认标签，是因为生产环境 executorId 可能包含 Pod 名或随机实例号，
     * 如果实例频繁扩缩容，会造成指标基数快速增长。
     */
    private final TaskManagementIntegrationProperties properties;

    /**
     * 记录单条 coordinator 执行结果。
     *
     * @param trigger 触发来源，例如 MANUAL、MANUAL_BATCH、SCHEDULER。
     * @param result 单条执行摘要。
     * @param duration 本次执行耗时。
     */
    public void recordCoordinatorRun(String trigger, QualityExecutorRunResult result, Duration duration) {
        String outcome = safeOutcome(result);
        String claimed = String.valueOf(result != null && Boolean.TRUE.equals(result.getClaimed()));
        Counter.builder("datasmart_quality_executor_run_total")
                .description("data-quality 质量执行器单条任务执行次数")
                .tag("trigger", normalizeTag(trigger))
                .tag("taskType", normalizeTag(properties.getTaskType()))
                .tag("outcome", normalizeTag(outcome))
                .tag("claimed", claimed)
                .register(meterRegistry)
                .increment();

        Timer.builder("datasmart_quality_executor_run_duration")
                .description("data-quality 质量执行器单条任务执行耗时")
                .tag("trigger", normalizeTag(trigger))
                .tag("taskType", normalizeTag(properties.getTaskType()))
                .tag("outcome", normalizeTag(outcome))
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录后台 scheduler 一轮调度结果。
     *
     * @param results 本轮 runBatch 返回的单条执行结果列表。
     * @param duration 本轮调度总耗时。
     */
    public void recordSchedulerTick(List<QualityExecutorRunResult> results, Duration duration) {
        String result = resolveSchedulerResult(results);
        Counter.builder("datasmart_quality_executor_scheduler_tick_total")
                .description("data-quality 质量执行器后台调度轮次")
                .tag("taskType", normalizeTag(properties.getTaskType()))
                .tag("result", result)
                .register(meterRegistry)
                .increment();

        Timer.builder("datasmart_quality_executor_scheduler_tick_duration")
                .description("data-quality 质量执行器后台调度单轮耗时")
                .tag("taskType", normalizeTag(properties.getTaskType()))
                .tag("result", result)
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录 scheduler 因为上一轮未结束而跳过。
     *
     * <p>如果这个指标持续增长，通常说明单轮质量扫描耗时过长、fixedDelay 配置过短，
     * 或未来引入多线程调度后出现了执行重叠风险。
     */
    public void recordSchedulerSkippedByReentry() {
        Counter.builder("datasmart_quality_executor_scheduler_skip_total")
                .description("data-quality 质量执行器后台调度跳过次数")
                .tag("taskType", normalizeTag(properties.getTaskType()))
                .tag("reason", "REENTRY")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录 scheduler 轮次级异常。
     *
     * <p>单条任务失败通常会被 coordinator 转成 FAILED_TO_PROCESS 或 UNSUPPORTED_SCAN；
     * 这里记录的是 scheduler 外层仍然捕获到的异常，通常代表配置、框架、序列化或未知运行时问题。
     */
    public void recordSchedulerFailure() {
        Counter.builder("datasmart_quality_executor_scheduler_failure_total")
                .description("data-quality 质量执行器后台调度外层异常次数")
                .tag("taskType", normalizeTag(properties.getTaskType()))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录执行器并发护栏拒绝次数。
     *
     * @param scope 触发拒绝的维度，例如 GLOBAL、TENANT、DATASOURCE。
     *
     * <p>这个指标用于回答：“任务失败是不是因为扫描逻辑错误，还是因为并发配额保护触发？”
     * 后续可以基于该指标决定是扩容执行器、调大配额，还是优化任务提交节奏。
     */
    public void recordConcurrencyRejected(String scope) {
        Counter.builder("datasmart_quality_executor_concurrency_rejected_total")
                .description("data-quality 质量执行器并发护栏拒绝次数")
                .tag("taskType", normalizeTag(properties.getTaskType()))
                .tag("scope", normalizeTag(scope))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 解析 scheduler 轮次结果。
     */
    private String resolveSchedulerResult(List<QualityExecutorRunResult> results) {
        if (results == null || results.isEmpty()) {
            return "EMPTY";
        }
        boolean hasClaimedTask = results.stream().anyMatch(result -> Boolean.TRUE.equals(result.getClaimed()));
        boolean hasFailure = results.stream().anyMatch(result ->
                "UNSUPPORTED_SCAN".equals(result.getOutcome()) || "FAILED_TO_PROCESS".equals(result.getOutcome()));
        boolean hasDeadLetter = results.stream().anyMatch(result -> "THROTTLED_DEAD_LETTER".equals(result.getOutcome()));
        boolean hasDeferred = results.stream().anyMatch(result -> "THROTTLED_DEFERRED".equals(result.getOutcome()));
        boolean hasSuccess = results.stream().anyMatch(result -> "RELATIONAL_SCAN_SUCCEEDED".equals(result.getOutcome()));
        if (hasFailure) {
            return "FAILED";
        }
        if (hasDeadLetter) {
            return "DEAD_LETTER";
        }
        if (hasDeferred) {
            return "DEFERRED";
        }
        if (hasSuccess) {
            return "SUCCEEDED";
        }
        if (!hasClaimedTask) {
            return "NO_TASK";
        }
        return "COMPLETED";
    }

    /**
     * 安全读取单条执行 outcome。
     */
    private String safeOutcome(QualityExecutorRunResult result) {
        if (result == null || result.getOutcome() == null || result.getOutcome().isBlank()) {
            return "UNKNOWN";
        }
        return result.getOutcome();
    }

    /**
     * 指标标签归一化。
     *
     * <p>Micrometer 标签值不应该为 null；空值统一写成 UNKNOWN，便于 Grafana 查询和告警规则编写。
     */
    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }
}
