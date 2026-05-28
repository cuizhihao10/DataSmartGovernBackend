/**
 * @Author : Cui
 * @Date: 2026/05/08 23:27
 * @Description DataSmart Govern Backend - DataSyncIdempotencyCleanupScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.DataSyncExecutorProperties;
import com.czh.datasmart.govern.datasync.service.support.SyncCallbackIdempotencyCleanupService;
import com.czh.datasmart.govern.datasync.service.support.SyncIdempotencyCleanupResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * data-sync 幂等记录保留期清理调度器。
 *
 * <p>幂等记录是执行器协议可靠性的“防抖垫片”：它保证同一个 start、checkpoint、complete、fail、heartbeat、
 * defer 或恢复动作重复到达时，服务端不会重复推进业务状态。这个表对近期请求很重要，但历史记录不应该永久增长。
 *
 * <p>本调度器的设计目标：
 * 1. 后台小批量删除超过保留期的记录，避免业务表和唯一索引无限膨胀；
 * 2. 清理策略配置化，让不同客户可以按审计要求调整 7/30/90/365 天；
 * 3. 通过 AtomicBoolean 避免单 JVM 内重入，减少重复删除压力；
 * 4. 删除过程有指标和日志，后续可以接入 Prometheus 告警。
 *
 * <p>多实例说明：
 * 多个 data-sync 实例同时运行该调度器时，可能会扫描到相同过期区间。
 * 由于删除 SQL 是按数据库真实存在行执行，重复清理通常只会表现为其中一个实例 deleted>0，另一个实例 deleted=0。
 * 如果未来幂等表规模达到千万级以上，建议再增加分布式锁、租户分片或独立 maintenance worker。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncIdempotencyCleanupScheduler {

    private final DataSyncExecutorProperties executorProperties;
    private final SyncCallbackIdempotencyCleanupService cleanupService;
    private final DataSyncIdempotencyCleanupMetrics cleanupMetrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时清理超过保留期的幂等记录。
     *
     * <p>调度参数来自 `datasmart.data-sync.executor.idempotency-cleanup`：
     * - `enabled` 控制是否开启后台清理；
     * - `initial-delay-ms` 控制启动后首次清理延迟；
     * - `fixed-delay-ms` 控制上一轮结束后多久启动下一轮；
     * - `retention-days` 控制记录保留窗口；
     * - `batch-size` 控制单轮删除上限。
     */
    @Scheduled(
            initialDelayString = "${datasmart.data-sync.executor.idempotency-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${datasmart.data-sync.executor.idempotency-cleanup.fixed-delay-ms:300000}"
    )
    public void cleanupExpiredIdempotencyRecords() {
        DataSyncExecutorProperties.IdempotencyCleanup cleanup = executorProperties.getIdempotencyCleanup();
        if (cleanup == null || !cleanup.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("data-sync 幂等记录清理上一轮尚未结束，本轮跳过");
            cleanupMetrics.recordSkippedByReentry();
            return;
        }

        Instant startedAt = Instant.now();
        try {
            SyncIdempotencyCleanupResult result = cleanupService.cleanupExpiredRecords(
                    executorProperties.effectiveIdempotencyRetentionDays(),
                    executorProperties.effectiveIdempotencyCleanupBatchSize()
            );
            cleanupMetrics.recordCleanupSuccess(result, Duration.between(startedAt, Instant.now()));
            if (result.deleted() > 0) {
                log.info("data-sync 幂等记录清理完成: retentionDays={}, expireBefore={}, requestedLimit={}, deleted={}",
                        result.retentionDays(), result.expireBefore(), result.requestedLimit(), result.deleted());
            } else {
                log.debug("data-sync 幂等记录清理未发现过期记录: retentionDays={}, expireBefore={}, requestedLimit={}",
                        result.retentionDays(), result.expireBefore(), result.requestedLimit());
            }
        } catch (Exception exception) {
            cleanupMetrics.recordCleanupFailure(Duration.between(startedAt, Instant.now()));
            log.error("data-sync 幂等记录清理失败，系统将在下一轮继续尝试", exception);
        } finally {
            running.set(false);
        }
    }
}
