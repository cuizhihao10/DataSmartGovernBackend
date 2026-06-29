/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - DataSyncWorkerLoopMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * data-sync worker loop 指标组件。
 *
 * <p>worker loop 是真实数据搬运闭环的入口，生产环境必须知道它是否健康：
 * 1. claimed 长期为 0，可能代表队列为空，也可能代表调度器没开启或任务状态没有进入 QUEUED；</p>
 * <p>2. failed 持续增长，可能代表模板缺失、bridge plan 阻断、datasource-management 不可用或目标端写入失败；</p>
 * <p>3. dispatched 为 0 但 claimed 大于 0，通常说明任务在进入真实读写前被 fail-closed；</p>
 * <p>4. 最近成功时间不更新，说明调度器可能停止、实例异常或配置被关闭。</p>
 *
 * <p>指标标签保持低基数。这里不把 tenantId、taskId、executionId、traceId、workerId 放进标签，
 * 因为这些值在真实客户环境中会快速增长，容易让 Prometheus 时序爆炸。</p>
 */
@Component
public class DataSyncWorkerLoopMetrics {

    private final MeterRegistry meterRegistry;

    /** 最近一轮成功调度认领数量。 */
    private final AtomicLong lastClaimed = new AtomicLong(0L);

    /** 最近一轮成功派发到 datasource-management 的数量。 */
    private final AtomicLong lastDispatched = new AtomicLong(0L);

    /** 最近一轮 complete 回写数量。 */
    private final AtomicLong lastCompleted = new AtomicLong(0L);

    /** 最近一轮 fail 回写或 fail-closed 数量。 */
    private final AtomicLong lastFailed = new AtomicLong(0L);

    /** 最近一次 worker loop 成功完成的 Unix 秒时间戳。 */
    private final AtomicLong lastSuccessEpochSecond = new AtomicLong(0L);

    /** 最近一次 worker loop 外层异常的 Unix 秒时间戳。 */
    private final AtomicLong lastFailureEpochSecond = new AtomicLong(0L);

    public DataSyncWorkerLoopMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * 记录一轮 worker loop 成功返回。
     */
    public void recordRunSuccess(SyncWorkerLoopRunResult result, Duration duration) {
        long claimed = result == null ? 0L : result.claimedCount();
        long dispatched = result == null ? 0L : result.dispatchedCount();
        long completed = result == null ? 0L : result.completedCount();
        long failed = result == null ? 0L : result.failedCount();
        lastClaimed.set(claimed);
        lastDispatched.set(dispatched);
        lastCompleted.set(completed);
        lastFailed.set(failed);
        lastSuccessEpochSecond.set(Instant.now().getEpochSecond());

        Counter.builder("datasmart_data_sync_worker_loop_tick_total")
                .description("data-sync worker loop 调度轮次")
                .tag("result", resolveResult(claimed, dispatched, completed, failed))
                .register(meterRegistry)
                .increment();
        Counter.builder("datasmart_data_sync_worker_loop_execution_total")
                .description("data-sync worker loop 处理 execution 数量")
                .tag("outcome", "CLAIMED")
                .register(meterRegistry)
                .increment(claimed);
        Counter.builder("datasmart_data_sync_worker_loop_execution_total")
                .description("data-sync worker loop 处理 execution 数量")
                .tag("outcome", "DISPATCHED")
                .register(meterRegistry)
                .increment(dispatched);
        Counter.builder("datasmart_data_sync_worker_loop_execution_total")
                .description("data-sync worker loop 处理 execution 数量")
                .tag("outcome", "COMPLETED")
                .register(meterRegistry)
                .increment(completed);
        Counter.builder("datasmart_data_sync_worker_loop_execution_total")
                .description("data-sync worker loop 处理 execution 数量")
                .tag("outcome", "FAILED")
                .register(meterRegistry)
                .increment(failed);
        Timer.builder("datasmart_data_sync_worker_loop_duration")
                .description("data-sync worker loop 单轮耗时")
                .tag("result", resolveResult(claimed, dispatched, completed, failed))
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录因上一轮尚未结束而跳过的调度。
     */
    public void recordSkippedByReentry() {
        Counter.builder("datasmart_data_sync_worker_loop_skip_total")
                .description("data-sync worker loop 跳过次数")
                .tag("reason", "REENTRY")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录 worker loop 调度外层异常。
     */
    public void recordRunFailure(Duration duration) {
        lastFailureEpochSecond.set(Instant.now().getEpochSecond());
        Counter.builder("datasmart_data_sync_worker_loop_failure_total")
                .description("data-sync worker loop 外层异常次数")
                .tag("phase", "SCHEDULER")
                .register(meterRegistry)
                .increment();
        Timer.builder("datasmart_data_sync_worker_loop_duration")
                .description("data-sync worker loop 单轮耗时")
                .tag("result", "FAILED")
                .register(meterRegistry)
                .record(duration);
    }

    private void registerGauges() {
        Gauge.builder("datasmart_data_sync_worker_loop_last_claimed", lastClaimed, AtomicLong::get)
                .description("data-sync worker loop 最近一轮认领数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_worker_loop_last_dispatched", lastDispatched, AtomicLong::get)
                .description("data-sync worker loop 最近一轮派发数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_worker_loop_last_completed", lastCompleted, AtomicLong::get)
                .description("data-sync worker loop 最近一轮完成数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_worker_loop_last_failed", lastFailed, AtomicLong::get)
                .description("data-sync worker loop 最近一轮失败数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_worker_loop_last_success_epoch_seconds", lastSuccessEpochSecond, AtomicLong::get)
                .description("data-sync worker loop 最近一次成功 Unix 秒时间戳")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_worker_loop_last_failure_epoch_seconds", lastFailureEpochSecond, AtomicLong::get)
                .description("data-sync worker loop 最近一次失败 Unix 秒时间戳")
                .register(meterRegistry);
    }

    private String resolveResult(long claimed, long dispatched, long completed, long failed) {
        if (claimed == 0L) {
            return "EMPTY";
        }
        if (failed > 0L && completed > 0L) {
            return "MIXED";
        }
        if (failed > 0L) {
            return "FAILED";
        }
        if (completed > 0L) {
            return "COMPLETED";
        }
        if (dispatched > 0L) {
            return "DISPATCHED";
        }
        return "CLAIMED_ONLY";
    }
}
