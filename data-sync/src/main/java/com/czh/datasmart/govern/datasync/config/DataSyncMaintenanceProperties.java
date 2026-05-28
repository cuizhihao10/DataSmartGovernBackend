/**
 * @Author : Cui
 * @Date: 2026/05/08 23:41
 * @Description DataSmart Govern Backend - DataSyncMaintenanceProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 平台维护配置。
 *
 * <p>这个配置类承载“运行数据保留期、后台维护任务、运营数据治理”相关配置。
 * 它和 `DataSyncExecutorProperties` 的边界不同：
 * 1. ExecutorProperties 关注执行器协议，例如租约恢复、最大退避、执行器回调幂等；
 * 2. MaintenanceProperties 关注服务长期运行后的数据体积、保留期、清理节奏和维护成本；
 * 3. 拆成两个配置类可以避免所有配置都堆在一个文件里，后续也更容易按领域拆分文档和管理页面。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.maintenance")
public class DataSyncMaintenanceProperties {

    /**
     * data-sync 运行数据保留期清理配置。
     *
     * <p>运行数据包括 checkpoint、错误样本、审计记录和事故记录。
     * 它们都不是“同步任务主数据”，但都对恢复、排障、审计、运营报表很重要。
     */
    private OperationalDataCleanup operationalDataCleanup = new OperationalDataCleanup();

    /**
     * 返回安全裁剪后的单轮清理批次。
     *
     * <p>清理任务是后台维护能力，不应该一次删除过多数据。
     * 这里把批次限制在 1 到 5000 之间，既能处理积压，又避免误配置导致数据库长事务。
     */
    public int effectiveOperationalCleanupBatchSize() {
        int batchSize = operationalDataCleanup == null ? 500 : operationalDataCleanup.getBatchSize();
        if (batchSize < 1) {
            return 1;
        }
        return Math.min(batchSize, 5000);
    }

    /**
     * 安全裁剪 checkpoint 保留天数。
     */
    public int effectiveCheckpointRetentionDays() {
        return clampRetentionDays(operationalDataCleanup == null
                ? 30
                : operationalDataCleanup.getCheckpointRetentionDays());
    }

    /**
     * 安全裁剪错误样本保留天数。
     */
    public int effectiveErrorSampleRetentionDays() {
        return clampRetentionDays(operationalDataCleanup == null
                ? 90
                : operationalDataCleanup.getErrorSampleRetentionDays());
    }

    /**
     * 安全裁剪审计记录保留天数。
     */
    public int effectiveAuditRecordRetentionDays() {
        return clampRetentionDays(operationalDataCleanup == null
                ? 365
                : operationalDataCleanup.getAuditRecordRetentionDays());
    }

    /**
     * 安全裁剪已关闭事故保留天数。
     */
    public int effectiveClosedIncidentRetentionDays() {
        return clampRetentionDays(operationalDataCleanup == null
                ? 180
                : operationalDataCleanup.getClosedIncidentRetentionDays());
    }

    private int clampRetentionDays(int retentionDays) {
        if (retentionDays < 1) {
            return 1;
        }
        return Math.min(retentionDays, 3650);
    }

    /**
     * 运行数据保留期清理配置。
     */
    @Data
    public static class OperationalDataCleanup {

        /**
         * 是否启用运行数据后台清理。
         *
         * <p>生产环境建议开启。若处于审计取证、客户验收或事故复盘阶段，可以临时关闭，
         * 但关闭后需要关注表增长和数据库备份窗口。
         */
        private boolean enabled = true;

        /**
         * 单轮每张表最多删除多少条记录。
         *
         * <p>注意这是“每张表”的批次上限。一次调度最多可能分别清理 checkpoint、error sample、
         * audit record、closed incident 四类表，因此总删除量可能约等于该值的四倍。
         */
        private int batchSize = 500;

        /**
         * 服务启动后首次清理延迟，单位毫秒。
         */
        private long initialDelayMs = 90000L;

        /**
         * 两次清理之间的固定延迟，单位毫秒。
         */
        private long fixedDelayMs = 600000L;

        /**
         * checkpoint 保留天数。
         *
         * <p>checkpoint 对恢复最关键，但很久以前的 checkpoint 通常不再用于继续执行。
         * 默认保留 30 天，适合常规任务恢复、补跑和短期问题排查。
         */
        private int checkpointRetentionDays = 30;

        /**
         * 错误样本保留天数。
         *
         * <p>错误样本用于定位坏数据、字段转换问题和目标写入问题。
         * 默认保留 90 天，给运营人员和数据负责人足够的回溯窗口。
         */
        private int errorSampleRetentionDays = 90;

        /**
         * 审计记录保留天数。
         *
         * <p>审计记录承担合规追溯责任，默认保留 365 天。
         * 金融、政企或强审计客户可提高到 730 或更长。
         */
        private int auditRecordRetentionDays = 365;

        /**
         * 已关闭事故保留天数。
         *
         * <p>只清理 CLOSED 事故，OPEN/ACKNOWLEDGED/RESOLVED 都必须保留。
         * 默认 180 天用于事故复盘、SLA 报表和客户服务质量分析。
         */
        private int closedIncidentRetentionDays = 180;
    }
}
