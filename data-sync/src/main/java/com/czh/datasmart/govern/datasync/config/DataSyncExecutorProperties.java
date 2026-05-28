/**
 * @Author : Cui
 * @Date: 2026/05/08 22:08
 * @Description DataSmart Govern Backend - DataSyncExecutorProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 执行器协议配置。
 *
 * <p>这个配置类不是为了“把常量搬到 yml”这么简单，而是为了把生产环境会频繁调整的执行策略从代码中拆出去。
 * 例如不同客户的 MySQL、PostgreSQL、Kafka、对象存储同步链路稳定性不同，worker 数量和租约时长也不同；
 * 如果退避上限写死在代码里，线上一旦遇到连接器抖动、目标库限流或网络隔离，就只能重新发版才能调整策略。
 *
 * <p>当前先承载最大退避次数。后续可以继续扩展：
 * 1. 租约默认时长与上限；
     * 2. 不同 connectorType 的并发上限；
     * 3. 租户级 claim 配额；
     * 4. 自动恢复任务的扫描间隔和批次大小；
     * 5. 幂等记录、执行日志、错误样本等运营数据的保留期；
     * 6. 人工介入任务是否自动触发告警或工单。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.executor")
public class DataSyncExecutorProperties {

    /**
     * 单条 execution 允许被 defer 或过期恢复回队列的最大次数。
     *
     * <p>deferCount 代表“这条执行记录已经因为容量、限流、租约过期等非成功路径被放回队列多少次”。
     * 当次数达到该阈值后，系统不再继续 requeue，而是把 execution 标记为 FAILED，
     * 同步把任务标记为 AWAITING_OPERATOR_ACTION，提醒运营人员排查连接器、数据源、目标端容量或配置问题。
     *
     * <p>默认值选择 5 次，是一个偏保守的商用系统起点：
     * 足够覆盖短暂网络抖动和目标端瞬时限流，又不会让故障任务在队列中无限循环、持续消耗 worker 资源。
     */
    private int maxDeferCount = 5;

    /**
     * 过期租约自动恢复配置。
     *
     * <p>租约恢复既可以通过运维接口手动触发，也可以由后台定时任务自动触发。
     * 自动恢复适合生产环境：worker 崩溃、网络隔离或节点重启后，平台不应等待人工发现 RUNNING 卡死任务。
     */
    private Recovery recovery = new Recovery();

    /**
     * 幂等记录清理配置。
     *
     * <p>回调幂等表会随着执行器重试、checkpoint、heartbeat、defer 和恢复动作持续增长。
     * 如果不设置保留期，生产运行几个月后会出现两个问题：
     * 1. 唯一索引和二级索引越来越大，影响新回调写入和重复请求裁决；
     * 2. 历史幂等事实的排障价值会随时间下降，但仍持续占用存储和备份成本。
     */
    private IdempotencyCleanup idempotencyCleanup = new IdempotencyCleanup();

    /**
     * 返回经过安全边界裁剪后的最大退避次数。
     *
     * <p>配置中心或环境变量可能被误配为 0、负数或过大的数字。
     * 这里在代码侧再做一层保护：最小为 1，最大为 100，避免极端配置导致任务永不进入人工介入，
     * 或者第一次轻微抖动就直接失败。
     */
    public int effectiveMaxDeferCount() {
        if (maxDeferCount < 1) {
            return 1;
        }
        return Math.min(maxDeferCount, 100);
    }

    /**
     * 返回经过安全裁剪后的自动恢复批次大小。
     *
     * <p>批次过小会导致积压恢复慢，批次过大则可能让一次调度占用太多数据库和业务线程。
     * 当前限制在 1 到 500 之间，和手动恢复接口的保护边界保持一致。
     */
    public int effectiveRecoveryBatchSize() {
        int batchSize = recovery == null ? 50 : recovery.getBatchSize();
        if (batchSize < 1) {
            return 1;
        }
        return Math.min(batchSize, 500);
    }

    /**
     * 返回经过安全裁剪后的幂等记录清理批次大小。
     *
     * <p>幂等表清理是后台维护任务，不应为了追赶历史积压而对数据库造成明显冲击。
     * 当前允许 1 到 5000 条之间的批量删除；如果实际生产表很大，建议通过缩短 fixedDelay
     * 或临时提高批次逐步追赶，而不是一次删除几十万行。
     */
    public int effectiveIdempotencyCleanupBatchSize() {
        int batchSize = idempotencyCleanup == null ? 1000 : idempotencyCleanup.getBatchSize();
        if (batchSize < 1) {
            return 1;
        }
        return Math.min(batchSize, 5000);
    }

    /**
     * 返回经过安全裁剪后的幂等记录保留天数。
     *
     * <p>最小保留 1 天，避免误配为 0 后把当天重试仍可能依赖的记录清掉。
     * 最大保留 10 年，主要是防止配置中心填入极端值导致清理永远无效。
     */
    public int effectiveIdempotencyRetentionDays() {
        int retentionDays = idempotencyCleanup == null ? 30 : idempotencyCleanup.getRetentionDays();
        if (retentionDays < 1) {
            return 1;
        }
        return Math.min(retentionDays, 3650);
    }

    /**
     * 自动恢复任务的细分配置。
     */
    @Data
    public static class Recovery {

        /**
         * 是否启用过期租约自动恢复。
         *
         * <p>生产环境建议开启；测试或本地调试时如果不想后台自动修改 execution 状态，可以关闭。
         */
        private boolean enabled = true;

        /**
         * 服务启动后首次扫描延迟，单位毫秒。
         *
         * <p>预留启动延迟可以让 Spring 容器、数据库连接池、服务注册和下游依赖先完成初始化，
         * 避免应用刚启动就立即执行恢复逻辑。
         */
        private long initialDelayMs = 30000L;

        /**
         * 两次扫描之间的固定延迟，单位毫秒。
         *
         * <p>使用 fixedDelay 而不是 fixedRate，是为了保证上一轮恢复完成之后再等待下一轮，
         * 避免恢复逻辑执行较慢时发生本实例内重叠扫描。
         */
        private long fixedDelayMs = 60000L;

        /**
         * 每次自动恢复最多扫描多少条过期 RUNNING execution。
         */
        private int batchSize = 50;

        /**
         * 可选租户 ID。
         *
         * <p>默认为空表示扫描全平台。后续如果一个 data-sync 实例被部署为租户专属 worker，
         * 可以通过该字段只恢复指定租户的过期租约。
         */
        private Long tenantId;

        /**
         * 自动恢复写入审计和 errorSummary 的原因摘要。
         */
        private String reason = "自动定时扫描发现执行器租约过期，系统恢复执行记录";
    }

    /**
     * 幂等记录保留期清理配置。
     */
    @Data
    public static class IdempotencyCleanup {

        /**
         * 是否启用幂等记录后台清理。
         *
         * <p>生产环境建议开启；如果处于问题排查期，需要临时保留完整幂等历史，可以短期关闭。
         */
        private boolean enabled = true;

        /**
         * 幂等记录保留天数。
         *
         * <p>默认 30 天适合大多数执行器重试和排障窗口。
         * 对金融、审计要求更严格的客户，可以提高到 90、180 或 365 天；
         * 对高频 heartbeat 场景，如果表增长较快，可以降低到 7 或 14 天，并配合审计表保存关键操作。
         */
        private int retentionDays = 30;

        /**
         * 服务启动后首次清理延迟，单位毫秒。
         *
         * <p>清理任务不属于核心业务路径，启动后稍晚执行可以避免和服务注册、连接池预热、恢复扫描争抢资源。
         */
        private long initialDelayMs = 60000L;

        /**
         * 两次清理之间的固定延迟，单位毫秒。
         *
         * <p>使用 fixedDelay 的原因和恢复任务一致：上一轮删除完成后再等待下一轮，避免单实例内重叠删除。
         */
        private long fixedDelayMs = 300000L;

        /**
         * 单轮最多删除多少条历史幂等记录。
         *
         * <p>这是控制数据库压力的关键阀门。数据量较大时，不建议直接设置过高，
         * 而应结合数据库指标、binlog 延迟和业务低峰时间逐步调优。
         */
        private int batchSize = 1000;
    }
}
