/**
 * @Author : Cui
 * @Date: 2026/05/08 23:45
 * @Description DataSmart Govern Backend - DataSyncOperationalDataCleanupMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.service.support.SyncOperationalDataCleanupResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * data-sync 运行数据清理指标组件。
 *
 * <p>运行数据清理不在用户请求链路上，但它影响生产数据库可持续运行：
 * 1. 如果删除量长期等于 batchSize，说明历史数据积压仍未追平；
 * 2. 如果失败次数持续增长，可能是数据库权限、锁竞争、SQL 方言或索引问题；
 * 3. 如果最近成功时间长期不更新，说明维护任务可能被关闭或调度器未运行；
 * 4. 如果某类表删除量明显高于其他表，说明该业务能力需要进一步拆分归档或降采样。
 *
 * <p>这里使用 `data_type` 标签区分 CHECKPOINT、ERROR_SAMPLE、AUDIT_RECORD、CLOSED_INCIDENT。
 * 这些标签值是固定枚举，属于低基数标签；不要把 taskId、tenantId、executionId 放进指标标签。
 */
@Component
public class DataSyncOperationalDataCleanupMetrics {

    private final MeterRegistry meterRegistry;

    /** 最近一轮总删除数量。 */
    private final AtomicLong lastTotalDeleted = new AtomicLong(0L);

    /** 最近一次清理成功的 Unix 秒时间戳。 */
    private final AtomicLong lastSuccessEpochSecond = new AtomicLong(0L);

    /** 最近一次清理失败的 Unix 秒时间戳。 */
    private final AtomicLong lastFailureEpochSecond = new AtomicLong(0L);

    public DataSyncOperationalDataCleanupMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * 记录运行数据清理成功结果。
     */
    public void recordCleanupSuccess(SyncOperationalDataCleanupResult result, Duration duration) {
        int totalDeleted = result == null ? 0 : result.totalDeleted();
        lastTotalDeleted.set(totalDeleted);
        lastSuccessEpochSecond.set(Instant.now().getEpochSecond());

        Counter.builder("datasmart_data_sync_operational_cleanup_tick_total")
                .description("data-sync 运行数据清理调度轮次")
                .tag("result", totalDeleted > 0 ? "DELETED" : "EMPTY")
                .register(meterRegistry)
                .increment();

        recordDeleted("CHECKPOINT", result == null ? 0 : result.deletedCheckpoints());
        recordDeleted("ERROR_SAMPLE", result == null ? 0 : result.deletedErrorSamples());
        recordDeleted("AUDIT_RECORD", result == null ? 0 : result.deletedAuditRecords());
        recordDeleted("CLOSED_INCIDENT", result == null ? 0 : result.deletedClosedIncidents());

        Timer.builder("datasmart_data_sync_operational_cleanup_duration")
                .description("data-sync 运行数据清理单轮耗时")
                .tag("result", totalDeleted > 0 ? "DELETED" : "EMPTY")
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * 记录因上一轮未结束而跳过的清理轮次。
     */
    public void recordSkippedByReentry() {
        Counter.builder("datasmart_data_sync_operational_cleanup_skip_total")
                .description("data-sync 运行数据清理跳过次数")
                .tag("reason", "REENTRY")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录运行数据清理调度外层异常。
     */
    public void recordCleanupFailure(Duration duration) {
        lastFailureEpochSecond.set(Instant.now().getEpochSecond());
        Counter.builder("datasmart_data_sync_operational_cleanup_failure_total")
                .description("data-sync 运行数据清理外层异常次数")
                .tag("phase", "SCHEDULER")
                .register(meterRegistry)
                .increment();
        Timer.builder("datasmart_data_sync_operational_cleanup_duration")
                .description("data-sync 运行数据清理单轮耗时")
                .tag("result", "FAILED")
                .register(meterRegistry)
                .record(duration);
    }

    private void recordDeleted(String dataType, int deleted) {
        Counter.builder("datasmart_data_sync_operational_cleanup_deleted_total")
                .description("data-sync 运行数据清理删除数量")
                .tag("data_type", dataType)
                .register(meterRegistry)
                .increment(deleted);
    }

    private void registerGauges() {
        Gauge.builder("datasmart_data_sync_operational_cleanup_last_total_deleted", lastTotalDeleted, AtomicLong::get)
                .description("data-sync 最近一轮运行数据清理总删除数量")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_operational_cleanup_last_success_epoch_seconds",
                        lastSuccessEpochSecond, AtomicLong::get)
                .description("data-sync 最近一次运行数据清理成功的 Unix 秒时间戳")
                .register(meterRegistry);
        Gauge.builder("datasmart_data_sync_operational_cleanup_last_failure_epoch_seconds",
                        lastFailureEpochSecond, AtomicLong::get)
                .description("data-sync 最近一次运行数据清理失败的 Unix 秒时间戳")
                .register(meterRegistry);
    }
}
