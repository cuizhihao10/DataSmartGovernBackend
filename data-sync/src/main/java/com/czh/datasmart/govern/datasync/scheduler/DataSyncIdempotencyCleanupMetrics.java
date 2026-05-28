/**
 * @Author : Cui
 * @Date: 2026/05/08 23:26
 * @Description DataSmart Govern Backend - DataSyncIdempotencyCleanupMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.service.support.SyncIdempotencyCleanupResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * data-sync 幂等记录清理指标组件。
 *
 * <p>清理任务的可观测性同样重要：
 * 1. 删除量持续为 0 可能正常，也可能说明保留期过长或调度未命中过期数据；
 * 2. 删除量长期等于 batchSize，说明历史积压较多，应该考虑临时提高批次或缩短间隔；
 * 3. 失败次数持续增长，说明数据库压力、SQL 方言、权限或锁竞争存在问题；
 * 4. 最近成功时间长期不更新，说明清理任务可能被关闭或调度器没有运行。
 *
 * <p>标签仍然保持低基数：只记录 result/reason/phase，不把租户、任务、幂等键放进指标标签。
 */
@Component
public class DataSyncIdempotencyCleanupMetrics {

    private final MeterRegistry meterRegistry;

    /** 最近一轮实际删除的幂等记录数。 */
    private final AtomicLong lastDeleted = new AtomicLong(0L);

    /** 最近一轮配置化保留天数。 */
    private final AtomicLong lastRetentionDays = new AtomicLong(0L);

    /** 最近一轮使用的过期边界 Unix 秒时间戳。 */
    private final AtomicLong lastExpireBeforeEpochSecond = new AtomicLong(0L);

    /** 最近一次清理成功的 Unix 秒时间戳。 */
    private final AtomicLong lastSuccessEpochSecond = new AtomicLong(0L);

    /** 最近一次清理失败的 Unix 秒时间戳。 */
    private final AtomicLong lastFailureEpochSecond = new AtomicLong(0L);

    public DataSyncIdempotencyCleanupMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * 记录幂等清理成功轮次。
     *
     * @param result 清理服务返回的删除统计和过期边界
     * @param duration 本轮调度耗时
     */
    public void recordCleanupSuccess(SyncIdempotencyCleanupResult result, Duration duration) {
        long deleted = result == null ? 0L : result.deleted();
        long retentionDays = result == null ? 0L : result.retentionDays();
        long expireBeforeEpoch = result == null || result.expireBefore() == null
                ? 0L
                : result.expireBefore().toEpochSecond(ZoneOffset.UTC);

        lastDeleted.set(deleted);
        lastRetentionDays.set(retentionDays);
        lastExpireBeforeEpochSecond.set(expireBeforeEpoch);
        lastSuccessEpochSecond.set(Instant.now().getEpochSecond());

        Counter.builder("datasmart_data_sync_idempotency_cleanup_tick_total")
                .description("data-sync 幂等记录清理调度轮次")
                .tag("result", deleted > 0 ? "DELETED" : "EMPTY")
                .register(meterRegistry)
                .increment();
        Counter.builder("datasmart_data_sync_idempotency_cleanup_deleted_total")
                .description("data-sync 幂等记录清理删除数量")
                .register(meterRegistry)
                .increment(deleted);
        Timer.builder("datasmart_data_sync_idempotency_cleanup_duration")
                .description("data-sync 幂等记录清理单轮耗时")
                .tag("result", deleted > 0 ? "DELETED" : "EMPTY")
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录因上一轮尚未结束而跳过的清理轮次。
     */
    public void recordSkippedByReentry() {
        Counter.builder("datasmart_data_sync_idempotency_cleanup_skip_total")
                .description("data-sync 幂等记录清理跳过次数")
                .tag("reason", "REENTRY")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录清理调度外层异常。
     */
    public void recordCleanupFailure(Duration duration) {
        lastFailureEpochSecond.set(Instant.now().getEpochSecond());
        Counter.builder("datasmart_data_sync_idempotency_cleanup_failure_total")
                .description("data-sync 幂等记录清理外层异常次数")
                .tag("phase", "SCHEDULER")
                .register(meterRegistry)
                .increment();
        Timer.builder("datasmart_data_sync_idempotency_cleanup_duration")
                .description("data-sync 幂等记录清理单轮耗时")
                .tag("result", "FAILED")
                .register(meterRegistry)
                .record(duration);
    }

    private void registerGauges() {
        Gauge.builder("datasmart_data_sync_idempotency_cleanup_last_deleted", lastDeleted, AtomicLong::get)
                .description("data-sync 最近一轮幂等记录清理删除数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_idempotency_cleanup_last_retention_days", lastRetentionDays, AtomicLong::get)
                .description("data-sync 最近一轮幂等记录清理使用的保留天数")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_idempotency_cleanup_last_expire_before_epoch_seconds",
                        lastExpireBeforeEpochSecond, AtomicLong::get)
                .description("data-sync 最近一轮幂等记录清理使用的过期边界 Unix 秒时间戳")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_idempotency_cleanup_last_success_epoch_seconds",
                        lastSuccessEpochSecond, AtomicLong::get)
                .description("data-sync 最近一次幂等记录清理成功的 Unix 秒时间戳")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_idempotency_cleanup_last_failure_epoch_seconds",
                        lastFailureEpochSecond, AtomicLong::get)
                .description("data-sync 最近一次幂等记录清理失败的 Unix 秒时间戳")
                .register(meterRegistry);
    }
}
