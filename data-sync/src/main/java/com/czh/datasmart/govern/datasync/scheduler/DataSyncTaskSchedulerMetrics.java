/**
 * @Author : Cui
 * @Date: 2026/07/07 23:12
 * @Description DataSmart Govern Backend - DataSyncTaskSchedulerMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * data-sync 任务级调度器指标。
 *
 * <p>task scheduler 位于“任务配置”和“执行队列”之间，是定期全量、定期批量能否自动运行的关键部件。
 * 生产环境至少需要知道：</p>
 * <p>1. 调度器是否还在周期性成功 tick；</p>
 * <p>2. 最近一轮扫描到多少到期任务；</p>
 * <p>3. 创建了多少 execution；</p>
 * <p>4. 有多少任务因为并发冲突、misfire 策略或多实例抢占失败而跳过。</p>
 *
 * <p>标签必须低基数：这里不把 tenantId、taskId、executionId、traceId 放入标签，避免 Prometheus 时序爆炸。</p>
 */
@Component
public class DataSyncTaskSchedulerMetrics {

    private final MeterRegistry meterRegistry;

    private final AtomicLong lastScannedTaskCount = new AtomicLong(0L);
    private final AtomicLong lastCreatedExecutionCount = new AtomicLong(0L);
    private final AtomicLong lastSkippedByConcurrencyCount = new AtomicLong(0L);
    private final AtomicLong lastSkippedByMisfirePolicyCount = new AtomicLong(0L);
    private final AtomicLong lastSkippedByRaceCount = new AtomicLong(0L);
    private final AtomicLong lastSuccessEpochSecond = new AtomicLong(0L);
    private final AtomicLong lastFailureEpochSecond = new AtomicLong(0L);

    public DataSyncTaskSchedulerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * 记录一轮任务调度成功完成。
     */
    public void recordRunSuccess(SyncTaskScheduleDispatchResult result, Duration duration) {
        long scanned = result == null ? 0L : result.scannedTaskCount();
        long created = result == null ? 0L : result.createdExecutionCount();
        long concurrency = result == null ? 0L : result.skippedByConcurrencyCount();
        long misfire = result == null ? 0L : result.skippedByMisfirePolicyCount();
        long race = result == null ? 0L : result.skippedByRaceCount();
        lastScannedTaskCount.set(scanned);
        lastCreatedExecutionCount.set(created);
        lastSkippedByConcurrencyCount.set(concurrency);
        lastSkippedByMisfirePolicyCount.set(misfire);
        lastSkippedByRaceCount.set(race);
        lastSuccessEpochSecond.set(Instant.now().getEpochSecond());

        Counter.builder("datasmart_data_sync_task_scheduler_tick_total")
                .description("data-sync task scheduler 调度轮次")
                .tag("result", resolveResult(result))
                .register(meterRegistry)
                .increment();
        Counter.builder("datasmart_data_sync_task_scheduler_execution_total")
                .description("data-sync task scheduler 创建 execution 数量")
                .tag("outcome", "CREATED")
                .register(meterRegistry)
                .increment(created);
        Counter.builder("datasmart_data_sync_task_scheduler_skip_total")
                .description("data-sync task scheduler 跳过任务数量")
                .tag("reason", "CONCURRENCY")
                .register(meterRegistry)
                .increment(concurrency);
        Counter.builder("datasmart_data_sync_task_scheduler_skip_total")
                .description("data-sync task scheduler 跳过任务数量")
                .tag("reason", "MISFIRE_POLICY")
                .register(meterRegistry)
                .increment(misfire);
        Counter.builder("datasmart_data_sync_task_scheduler_skip_total")
                .description("data-sync task scheduler 跳过任务数量")
                .tag("reason", "RACE_LOST")
                .register(meterRegistry)
                .increment(race);
        Timer.builder("datasmart_data_sync_task_scheduler_duration")
                .description("data-sync task scheduler 单轮耗时")
                .tag("result", resolveResult(result))
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录因上一轮仍在运行而跳过本轮。
     */
    public void recordSkippedByReentry() {
        Counter.builder("datasmart_data_sync_task_scheduler_skip_total")
                .description("data-sync task scheduler 跳过任务数量")
                .tag("reason", "REENTRY")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录调度器外层异常。
     */
    public void recordRunFailure(Duration duration) {
        lastFailureEpochSecond.set(Instant.now().getEpochSecond());
        Counter.builder("datasmart_data_sync_task_scheduler_failure_total")
                .description("data-sync task scheduler 外层异常次数")
                .tag("phase", "SCHEDULER")
                .register(meterRegistry)
                .increment();
        Timer.builder("datasmart_data_sync_task_scheduler_duration")
                .description("data-sync task scheduler 单轮耗时")
                .tag("result", "FAILED")
                .register(meterRegistry)
                .record(duration);
    }

    private void registerGauges() {
        Gauge.builder("datasmart_data_sync_task_scheduler_last_scanned_tasks", lastScannedTaskCount, AtomicLong::get)
                .description("data-sync task scheduler 最近一轮扫描任务数")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_scheduler_last_created_executions", lastCreatedExecutionCount, AtomicLong::get)
                .description("data-sync task scheduler 最近一轮创建 execution 数")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_scheduler_last_skipped_by_concurrency", lastSkippedByConcurrencyCount, AtomicLong::get)
                .description("data-sync task scheduler 最近一轮并发冲突跳过数")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_scheduler_last_skipped_by_misfire_policy", lastSkippedByMisfirePolicyCount, AtomicLong::get)
                .description("data-sync task scheduler 最近一轮 misfire 策略跳过数")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_scheduler_last_skipped_by_race", lastSkippedByRaceCount, AtomicLong::get)
                .description("data-sync task scheduler 最近一轮多实例抢占失败数")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_scheduler_last_success_epoch_seconds", lastSuccessEpochSecond, AtomicLong::get)
                .description("data-sync task scheduler 最近一次成功 Unix 秒时间戳")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_task_scheduler_last_failure_epoch_seconds", lastFailureEpochSecond, AtomicLong::get)
                .description("data-sync task scheduler 最近一次失败 Unix 秒时间戳")
                .register(meterRegistry);
    }

    private String resolveResult(SyncTaskScheduleDispatchResult result) {
        if (result == null) {
            return "EMPTY";
        }
        if (result.createdExecutionCount() > 0) {
            return result.skippedByConcurrencyCount() > 0 || result.skippedByMisfirePolicyCount() > 0
                    ? "MIXED"
                    : "DISPATCHED";
        }
        if (result.skippedByConcurrencyCount() > 0) {
            return "CONCURRENCY_SKIPPED";
        }
        if (result.skippedByMisfirePolicyCount() > 0) {
            return "MISFIRE_SKIPPED";
        }
        if (result.scannedTaskCount() == 0) {
            return "NO_DUE_TASK";
        }
        return "SCANNED_NO_EXECUTION";
    }
}
