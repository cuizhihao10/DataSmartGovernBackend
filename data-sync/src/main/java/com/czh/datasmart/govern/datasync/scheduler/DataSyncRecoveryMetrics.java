/**
 * @Author : Cui
 * @Date: 2026/05/08 23:12
 * @Description DataSmart Govern Backend - DataSyncRecoveryMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * data-sync 自动恢复指标组件。
 *
 * <p>这个类把 Micrometer 指标集中封装起来，避免 scheduler 里散落 Counter、Gauge、Timer 细节。
 * 对商用数据同步平台来说，自动恢复是否健康非常关键：
 * 1. 如果扫描失败持续增长，说明恢复任务本身不可靠；
 * 2. 如果进入人工介入数量持续增长，说明 worker、连接器、目标端容量或配置存在系统性问题；
 * 3. 如果最近成功时间长期不更新，说明调度器可能被关闭、卡死或配置错误；
 * 4. 如果扫描量长期很大但恢复量很低，可能存在重复扫描、锁竞争或租约时间配置不合理。
 *
 * <p>标签设计保持低基数。这里不把 executionId、taskId、tenantId、traceId 放到标签，
 * 因为这些值会造成高基数时序，给 Prometheus/Grafana 带来存储和查询压力。
 */
@Component
public class DataSyncRecoveryMetrics {

    private final MeterRegistry meterRegistry;

    /** 最近一轮扫描到的过期租约数量。 */
    private final AtomicLong lastScanned = new AtomicLong(0L);

    /** 最近一轮恢复回队列数量。 */
    private final AtomicLong lastRecovered = new AtomicLong(0L);

    /** 最近一轮进入人工介入数量。 */
    private final AtomicLong lastAttentionRequired = new AtomicLong(0L);

    /** 最近一次自动恢复成功的 Unix 秒时间戳。 */
    private final AtomicLong lastSuccessEpochSecond = new AtomicLong(0L);

    /** 最近一次自动恢复失败的 Unix 秒时间戳。 */
    private final AtomicLong lastFailureEpochSecond = new AtomicLong(0L);

    public DataSyncRecoveryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * 记录自动恢复单轮成功执行结果。
     *
     * @param result 恢复服务返回的扫描、恢复和人工介入统计
     * @param duration 本轮调度耗时
     */
    public void recordRecoverySuccess(SyncExpiredLeaseRecoveryResult result, Duration duration) {
        long scanned = result == null ? 0L : result.scanned();
        long recovered = result == null ? 0L : result.recovered();
        long attention = result == null ? 0L : result.attentionRequired();
        lastScanned.set(scanned);
        lastRecovered.set(recovered);
        lastAttentionRequired.set(attention);
        lastSuccessEpochSecond.set(Instant.now().getEpochSecond());

        Counter.builder("datasmart_data_sync_recovery_tick_total")
                .description("data-sync 过期租约自动恢复调度轮次")
                .tag("result", resolveResult(scanned, recovered, attention))
                .register(meterRegistry)
                .increment();

        Counter.builder("datasmart_data_sync_recovery_execution_total")
                .description("data-sync 自动恢复处理的 execution 数量")
                .tag("outcome", "SCANNED")
                .register(meterRegistry)
                .increment(scanned);
        Counter.builder("datasmart_data_sync_recovery_execution_total")
                .description("data-sync 自动恢复处理的 execution 数量")
                .tag("outcome", "REQUEUED")
                .register(meterRegistry)
                .increment(recovered);
        Counter.builder("datasmart_data_sync_recovery_execution_total")
                .description("data-sync 自动恢复处理的 execution 数量")
                .tag("outcome", "ATTENTION_REQUIRED")
                .register(meterRegistry)
                .increment(attention);

        Timer.builder("datasmart_data_sync_recovery_tick_duration")
                .description("data-sync 过期租约自动恢复单轮耗时")
                .tag("result", resolveResult(scanned, recovered, attention))
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录因上一轮尚未结束而跳过的调度。
     */
    public void recordSkippedByReentry() {
        Counter.builder("datasmart_data_sync_recovery_skip_total")
                .description("data-sync 过期租约自动恢复跳过次数")
                .tag("reason", "REENTRY")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录自动恢复调度外层异常。
     */
    public void recordRecoveryFailure(Duration duration) {
        lastFailureEpochSecond.set(Instant.now().getEpochSecond());
        Counter.builder("datasmart_data_sync_recovery_failure_total")
                .description("data-sync 过期租约自动恢复外层异常次数")
                .tag("phase", "SCHEDULER")
                .register(meterRegistry)
                .increment();
        Timer.builder("datasmart_data_sync_recovery_tick_duration")
                .description("data-sync 过期租约自动恢复单轮耗时")
                .tag("result", "FAILED")
                .register(meterRegistry)
                .record(duration);
    }

    private void registerGauges() {
        Gauge.builder("datasmart_data_sync_recovery_last_scanned", lastScanned, AtomicLong::get)
                .description("data-sync 最近一轮自动恢复扫描到的过期执行记录数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_recovery_last_recovered", lastRecovered, AtomicLong::get)
                .description("data-sync 最近一轮自动恢复回队列数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_recovery_last_attention_required", lastAttentionRequired, AtomicLong::get)
                .description("data-sync 最近一轮自动恢复转人工介入数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_recovery_last_success_epoch_seconds", lastSuccessEpochSecond, AtomicLong::get)
                .description("data-sync 最近一次自动恢复成功的 Unix 秒时间戳")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_recovery_last_failure_epoch_seconds", lastFailureEpochSecond, AtomicLong::get)
                .description("data-sync 最近一次自动恢复失败的 Unix 秒时间戳")
                .register(meterRegistry);
    }

    private String resolveResult(long scanned, long recovered, long attention) {
        if (attention > 0) {
            return "ATTENTION_REQUIRED";
        }
        if (recovered > 0) {
            return "RECOVERED";
        }
        if (scanned > 0) {
            return "SCANNED_ONLY";
        }
        return "EMPTY";
    }
}
