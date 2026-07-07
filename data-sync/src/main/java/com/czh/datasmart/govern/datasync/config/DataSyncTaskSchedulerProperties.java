/**
 * @Author : Cui
 * @Date: 2026/07/07 23:05
 * @Description DataSmart Govern Backend - DataSyncTaskSchedulerProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 任务级定时调度器配置。
 *
 * <p>它和 {@link DataSyncWorkerLoopProperties} 的职责不同：</p>
 * <p>1. task scheduler 负责扫描到期的 SCHEDULED 任务，并创建 SCHEDULED execution；</p>
 * <p>2. worker loop 负责认领 QUEUED execution，并调用 datasource-management 完成真实读写；</p>
 * <p>3. 两者分开配置，可以让生产环境先打开“生成执行记录”，再按容量逐步打开“真实数据搬运”。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.task-scheduler")
public class DataSyncTaskSchedulerProperties {

    /**
     * 是否启用后台定时扫描。
     *
     * <p>默认关闭，避免开发者启动 data-sync 时自动为历史 SCHEDULED 任务生成 execution。
     * 完整联调或商用部署可以显式开启，并配合 worker-loop.scheduler-enabled 控制是否继续真实执行。</p>
     */
    private boolean schedulerEnabled = false;

    /**
     * 单轮最多扫描多少个到期任务。
     *
     * <p>这个值控制的是“任务个数”，不是 execution 个数。若任务配置 CATCH_UP_LIMITED 且允许并发，
     * 一个任务可能在本轮生成多条 execution，但仍受 maxCatchUpRunsPerTask 保护。</p>
     */
    private int batchSize = 20;

    /**
     * 单个任务单轮最多补偿多少个历史触发窗口。
     *
     * <p>服务停机数小时后，如果每分钟调度一次，理论上会错过大量窗口。生产系统不能一次性补出所有历史 execution，
     * 否则会造成队列风暴、数据库写入突刺和目标端压力。因此需要平台级上限。</p>
     */
    private int maxCatchUpRunsPerTask = 3;

    /**
     * 可选租户过滤。
     *
     * <p>为空表示平台级扫描所有租户；租户专属部署时可以指定 tenantId，避免一个实例跨租户生成执行记录。</p>
     */
    private Long tenantId;

    /**
     * 服务启动后首次扫描延迟，单位毫秒。
     */
    private long initialDelayMs = 30000L;

    /**
     * 上一轮扫描结束后多久再次扫描，单位毫秒。
     */
    private long fixedDelayMs = 15000L;

    /**
     * 后台调度写审计时使用的平台系统 actorId。
     */
    private Long systemActorId = 0L;

    /**
     * 后台调度写审计时使用的平台系统角色。
     */
    private String systemActorRole = "SERVICE_ACCOUNT";

    /**
     * 自动生成 traceId 使用的低敏前缀。
     */
    private String traceIdPrefix = "data-sync-task-scheduler";

    /**
     * 返回安全裁剪后的扫描批次。
     */
    public int effectiveBatchSize(Integer override) {
        int value = override == null || override <= 0 ? batchSize : override;
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 200);
    }

    /**
     * 返回安全裁剪后的单任务补偿上限。
     */
    public int effectiveMaxCatchUpRunsPerTask() {
        if (maxCatchUpRunsPerTask < 1) {
            return 1;
        }
        return Math.min(maxCatchUpRunsPerTask, 20);
    }
}
