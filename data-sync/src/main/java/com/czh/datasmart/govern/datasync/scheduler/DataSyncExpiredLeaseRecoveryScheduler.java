/**
 * @Author : Cui
 * @Date: 2026/05/08 22:55
 * @Description DataSmart Govern Backend - DataSyncExpiredLeaseRecoveryScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.DataSyncExecutorProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryResult;
import com.czh.datasmart.govern.datasync.service.DataSyncExecutorLeaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * data-sync 过期租约自动恢复调度器。
 *
 * <p>这个调度器解决的是生产环境里非常常见的问题：
 * worker 认领 execution 后，如果进程崩溃、宿主机重启、网络隔离或长时间卡死，
 * execution 会停留在 RUNNING，并且不再有 heartbeat。人工恢复接口虽然可用，但真正的商用产品不能依赖人工盯着列表。
 *
 * <p>调度器只负责“定时触发”，不直接操作数据库：
 * 1. 真正扫描和恢复逻辑仍由 `DataSyncExecutorLeaseService` 承担；
 * 2. 这样手动恢复接口和自动恢复任务共用一套状态流转、最大退避次数、人工介入和审计逻辑；
 * 3. 后续如果接入 Quartz、XXL-JOB、Spring Cloud Task 或 Kubernetes CronJob，也可以复用同一个 Service。
 *
 * <p>并发与可靠性说明：
 * 1. 本类使用 `AtomicBoolean` 防止同一个 JVM 内上一轮还没结束又进入下一轮；
 * 2. 多实例部署时仍可能多个实例同时扫描，但 mapper 层有状态和租约过期条件二次裁决，只有一个实例能更新成功；
 * 3. 后续如果恢复流量变大，应补 Redis/DB 分布式锁或调度中心分片，减少重复扫描成本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncExpiredLeaseRecoveryScheduler {

    /**
     * 定时恢复使用服务账号身份写审计。
     *
     * <p>当前 actorId 使用 0 表示平台系统动作；后续接入服务账号体系后，可以替换成真实 service account ID。
     */
    private static final SyncActorContext SYSTEM_ACTOR =
            new SyncActorContext(0L, 0L, "SERVICE_ACCOUNT", "data-sync-expired-lease-recovery");

    private final DataSyncExecutorProperties executorProperties;
    private final DataSyncExecutorLeaseService leaseService;
    private final DataSyncRecoveryMetrics recoveryMetrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时扫描并恢复过期执行租约。
     *
     * <p>`fixedDelayString` 和 `initialDelayString` 直接读取配置文件：
     * - `initial-delay-ms` 控制服务启动后多久第一次扫描；
     * - `fixed-delay-ms` 控制上一轮结束后多久启动下一轮。
     *
     * <p>如果配置关闭 `enabled=false`，方法会快速返回，不触发恢复。
     * 这样保留调度器 Bean 的同时，允许不同环境通过配置决定是否启用自动恢复。
     */
    @Scheduled(
            initialDelayString = "${datasmart.data-sync.executor.recovery.initial-delay-ms:30000}",
            fixedDelayString = "${datasmart.data-sync.executor.recovery.fixed-delay-ms:60000}"
    )
    public void recoverExpiredLeases() {
        DataSyncExecutorProperties.Recovery recovery = executorProperties.getRecovery();
        if (recovery == null || !recovery.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("data-sync 过期租约自动恢复上一轮尚未结束，本轮跳过");
            recoveryMetrics.recordSkippedByReentry();
            return;
        }
        Instant startedAt = Instant.now();
        try {
            SyncExpiredLeaseRecoveryRequest request = new SyncExpiredLeaseRecoveryRequest(
                    recovery.getTenantId(),
                    executorProperties.effectiveRecoveryBatchSize(),
                    true,
                    recovery.getReason(),
                    null
            );
            SyncExpiredLeaseRecoveryResult result = leaseService.recoverExpiredLeases(request, SYSTEM_ACTOR);
            recoveryMetrics.recordRecoverySuccess(result, Duration.between(startedAt, Instant.now()));
            if (result.recovered() > 0 || result.attentionRequired() > 0) {
                log.info("data-sync 过期租约自动恢复完成: scanned={}, recovered={}, attentionRequired={}",
                        result.scanned(), result.recovered(), result.attentionRequired());
            } else {
                log.debug("data-sync 过期租约自动恢复未发现待处理执行记录: scanned={}", result.scanned());
            }
        } catch (Exception exception) {
            recoveryMetrics.recordRecoveryFailure(Duration.between(startedAt, Instant.now()));
            log.error("data-sync 过期租约自动恢复失败，系统将在下一轮继续尝试", exception);
        } finally {
            running.set(false);
        }
    }
}
