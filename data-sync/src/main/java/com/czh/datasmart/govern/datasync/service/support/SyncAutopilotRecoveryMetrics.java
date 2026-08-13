/**
 * @Author : Cui
 * @Date: 2026/08/11 22:50
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * data-sync 侧 Autopilot trigger 与最终恢复状态的低基数指标组件。
 *
 * <p>Agent Runtime 的指标描述规划、证据和启动过程，本类描述 data-sync 拥有的持久业务边界：失败事实是否
 * 产生 trigger，以及已启动 case 最终是恢复成功还是恢复失败。两边组合后，运维人员可以区分“没有触发”、
 * “规划或执行未启动”和“worker 启动后仍失败”。</p>
 *
 * <p>所有标签值都写死在方法中，不接受 taskId、executionId、tenantId、errorCode 或 reasonCode。精确对象
 * 追踪继续依赖 recovery case、receipt 和 outbox，Prometheus 只负责低成本趋势与告警。</p>
 */
@Component
public class SyncAutopilotRecoveryMetrics {

    private static final String METRIC_PREFIX = "datasmart_data_sync_autopilot_recovery";

    private final MeterRegistry meterRegistry;

    /**
     * 创建指标组件并使用当前 data-sync 应用的 Micrometer 注册表。
     *
     * @param meterRegistry Spring Boot 提供的指标注册表
     */
    public SyncAutopilotRecoveryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 记录一个失败 execution 已通过授权、范围和循环预算检查并写入 durable trigger outbox。 */
    public void recordTriggerAccepted() {
        increment("trigger_total", "ACCEPTED", "data-sync Autopilot recovery trigger outcomes");
    }

    /** 记录一个失败 execution 因缺少可信授权、范围不符、过期或循环预算耗尽而未自动触发。 */
    public void recordTriggerRejected() {
        increment("trigger_total", "REJECTED", "data-sync Autopilot recovery trigger outcomes");
    }

    /** 记录一个 RECOVERY_STARTED case 被 worker 成功结果收敛为 RECOVERED。 */
    public void recordRecoverySucceeded() {
        increment("terminal_total", "RECOVERED", "data-sync Autopilot terminal recovery outcomes");
    }

    /** 记录一个已启动恢复轮次再次失败并写入 RECOVERY_FAILED receipt。 */
    public void recordRecoveryFailed() {
        increment("terminal_total", "FAILED", "data-sync Autopilot terminal recovery outcomes");
    }

    /**
     * Records a failed-execution sidecar transaction that must be persisted into the V23 compensation journal.
     *
     * <p>The counter deliberately describes the sidecar boundary rather than a task, execution, tenant, or
     * error code. Operators can detect a broken trigger-control path without creating high-cardinality time
     * series; the exact low-sensitive replay fact remains available from the compensation table and its audit
     * logs.</p>
     */
    public void recordTriggerSidecarFailure() {
        increment("sidecar_failure_total", "TRIGGER", "data-sync Autopilot sidecar transaction failures");
    }

    /**
     * Records a successful-execution finalization sidecar transaction that must be replayed by V23.
     *
     * <p>This is separate from {@link #recordTriggerSidecarFailure()} because a missing finalization leaves an
     * already-started recovery case open, whereas a missing trigger loses the invitation to plan recovery. Both
     * values are fixed enum-like outcomes so Prometheus cardinality remains bounded.</p>
     */
    public void recordFinalizationSidecarFailure() {
        increment("sidecar_failure_total", "FINALIZATION", "data-sync Autopilot sidecar transaction failures");
    }

    /**
     * Records a V23 compensation row whose bounded replay budget has been exhausted.
     *
     * <p>A dead letter is intentionally a separate metric from a transient sidecar failure: the latter may
     * recover on the next scheduler pass, while the former requires operator investigation. The metric carries
     * no task, execution, exception, or operation label; those values stay in durable low-sensitive storage.</p>
     */
    public void recordSidecarCompensationDeadLetter() {
        increment("sidecar_compensation_dead_letter_total", "DEAD_LETTER",
                "data-sync Autopilot sidecar compensations exhausted their replay budget");
    }

    /**
     * 增加一个仅带固定 {@code outcome} 标签的 Counter。
     *
     * <p>保持该方法 private 可以阻止业务调用方传入任意标签值。Micrometer 会复用相同名称和标签的 meter，
     * 所以重复调用只增加数值，不会按事件创建新的时间序列。</p>
     *
     * @param metricSuffix 固定指标后缀
     * @param outcome 固定结果标签值
     * @param description 指标帮助文本
     */
    private void increment(String metricSuffix, String outcome, String description) {
        Counter.builder(METRIC_PREFIX + "_" + metricSuffix)
                .description(description)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
