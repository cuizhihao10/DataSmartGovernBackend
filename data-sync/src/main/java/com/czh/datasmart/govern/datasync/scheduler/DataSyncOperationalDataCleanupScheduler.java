/**
 * @Author : Cui
 * @Date: 2026/05/08 23:46
 * @Description DataSmart Govern Backend - DataSyncOperationalDataCleanupScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.DataSyncMaintenanceProperties;
import com.czh.datasmart.govern.datasync.service.support.SyncOperationalDataCleanupResult;
import com.czh.datasmart.govern.datasync.service.support.SyncOperationalDataCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * data-sync 运行数据保留期清理调度器。
 *
 * <p>本调度器负责周期性触发 checkpoint、错误样本、审计记录和已关闭事故的保留期清理。
 * 这些数据都服务于恢复、排障、审计和运营分析，但如果永久保留，会逐渐拖慢数据库索引、备份和查询。
 *
 * <p>职责边界：
 * 1. 调度器只做触发、重入保护、日志和指标；
 * 2. 不同表的保留期和删除逻辑交给 `SyncOperationalDataCleanupService`；
 * 3. 具体 SQL 仍在各 Mapper 内，便于每张表按自己的索引和业务条件演进。
 *
 * <p>多实例说明：
 * 多个 data-sync 实例同时运行时，可能并发清理同一张表的历史数据。
 * 当前依赖数据库 DELETE 的真实行匹配自然裁决；大规模部署时可进一步增加 Redis/DB 分布式锁、
 * leader election 或租户分片，减少重复扫描和删除竞争。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncOperationalDataCleanupScheduler {

    private final DataSyncMaintenanceProperties maintenanceProperties;
    private final SyncOperationalDataCleanupService cleanupService;
    private final DataSyncOperationalDataCleanupMetrics cleanupMetrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时清理超过保留期的运行数据。
     *
     * <p>调度配置来自 `datasmart.data-sync.maintenance.operational-data-cleanup`：
     * - `enabled` 控制是否启用；
     * - `initial-delay-ms` 控制启动后首次清理延迟；
     * - `fixed-delay-ms` 控制上一轮结束后的等待时间；
     * - 各类 `*-retention-days` 控制不同数据类型的保留窗口；
     * - `batch-size` 控制每张表单轮最多删除多少行。
     */
    @Scheduled(
            initialDelayString = "${datasmart.data-sync.maintenance.operational-data-cleanup.initial-delay-ms:90000}",
            fixedDelayString = "${datasmart.data-sync.maintenance.operational-data-cleanup.fixed-delay-ms:600000}"
    )
    public void cleanupExpiredOperationalData() {
        DataSyncMaintenanceProperties.OperationalDataCleanup cleanup =
                maintenanceProperties.getOperationalDataCleanup();
        if (cleanup == null || !cleanup.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("data-sync 运行数据保留期清理上一轮尚未结束，本轮跳过");
            cleanupMetrics.recordSkippedByReentry();
            return;
        }

        Instant startedAt = Instant.now();
        try {
            SyncOperationalDataCleanupResult result = cleanupService.cleanupExpiredOperationalData();
            cleanupMetrics.recordCleanupSuccess(result, Duration.between(startedAt, Instant.now()));
            if (result.totalDeleted() > 0) {
                log.info("data-sync 运行数据清理完成: checkpoints={}, errorSamples={}, auditRecords={}, closedIncidents={}, limit={}",
                        result.deletedCheckpoints(), result.deletedErrorSamples(), result.deletedAuditRecords(),
                        result.deletedClosedIncidents(), result.requestedLimitPerTable());
            } else {
                log.debug("data-sync 运行数据清理未发现过期记录: limit={}", result.requestedLimitPerTable());
            }
        } catch (Exception exception) {
            cleanupMetrics.recordCleanupFailure(Duration.between(startedAt, Instant.now()));
            log.error("data-sync 运行数据保留期清理失败，系统将在下一轮继续尝试", exception);
        } finally {
            running.set(false);
        }
    }
}
