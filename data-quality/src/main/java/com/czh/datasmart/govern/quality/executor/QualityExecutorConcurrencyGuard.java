/**
 * @Author : Cui
 * @Date: 2026/04/29 00:56
 * @Description DataSmart Govern Backend - QualityExecutorConcurrencyGuard.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 质量执行器本实例并发护栏。
 *
 * <p>这个组件解决的是“单个 data-quality 实例内部不要同时跑太多质量扫描”的问题。
 * 它按三个维度限制正在执行的扫描数量：
 * 1. GLOBAL：当前实例总并发；
 * 2. TENANT：当前实例内单租户并发；
 * 3. DATASOURCE：当前实例内单数据源并发。
 *
 * <p>它与 task-management 的职责不同：
 * - task-management 负责队列、认领、租约、心跳、失败恢复和最终任务状态；
 * - 本组件负责 data-quality 实例内的运行时资源保护；
 * - datasource-management 负责真实源库访问、只读 SQL、行数/超时和审计。
 *
 * <p>为什么先做“本实例”而不是“一步到位全局分布式配额”：
 * 当前项目还没有统一 Redis 分布式锁、配额中心或资源调度中心。
 * 如果此时硬做分布式配额，会引入较多基础设施复杂度，并且可能和 task-management 后续调度策略冲突。
 * 先把本实例护栏建好，可以在未来引入线程池和多实例部署时保留清晰扩展点。
 */
@Component
@RequiredArgsConstructor
public class QualityExecutorConcurrencyGuard {

    private static final String UNKNOWN_TENANT = "UNKNOWN_TENANT";
    private static final String UNKNOWN_DATASOURCE = "UNKNOWN_DATASOURCE";

    private final TaskManagementIntegrationProperties properties;
    private final QualityExecutorMetrics qualityExecutorMetrics;

    /**
     * 当前实例正在运行的质量扫描总数。
     */
    private final AtomicInteger globalRunning = new AtomicInteger(0);

    /**
     * 当前实例内按租户统计的运行中扫描数。
     *
     * <p>key 使用字符串，是为了兼容 tenantId 为空的历史任务，空租户统一归到 UNKNOWN_TENANT。
     */
    private final Map<String, AtomicInteger> tenantRunning = new ConcurrentHashMap<>();

    /**
     * 当前实例内按数据源统计的运行中扫描数。
     */
    private final Map<String, AtomicInteger> datasourceRunning = new ConcurrentHashMap<>();

    /**
     * 尝试获取执行许可。
     *
     * @param tenantId 租户 ID，当前来自质量任务 payload；为空时归入 UNKNOWN_TENANT。
     * @param datasourceId 数据源 ID，当前来自扫描计划；为空时归入 UNKNOWN_DATASOURCE。
     * @return 许可对象，调用方必须在 finally 或 try-with-resources 中释放。
     *
     * <p>这里使用 synchronized，是为了让三个维度的检查与递增保持原子性。
     * 当前并发规模很小，synchronized 的成本远低于实现复杂无锁多维配额的维护成本。
     */
    public synchronized Lease acquire(Long tenantId, Long datasourceId) {
        if (!properties.isExecutorConcurrencyGuardEnabled()) {
            return Lease.noop();
        }

        String tenantKey = tenantKey(tenantId);
        String datasourceKey = datasourceKey(datasourceId);
        ensureWithinLimit("GLOBAL", globalRunning.get(), properties.getSafeExecutorMaxConcurrentRunsGlobal());
        ensureWithinLimit("TENANT", currentValue(tenantRunning, tenantKey),
                properties.getSafeExecutorMaxConcurrentRunsPerTenant());
        ensureWithinLimit("DATASOURCE", currentValue(datasourceRunning, datasourceKey),
                properties.getSafeExecutorMaxConcurrentRunsPerDatasource());

        globalRunning.incrementAndGet();
        tenantRunning.computeIfAbsent(tenantKey, key -> new AtomicInteger(0)).incrementAndGet();
        datasourceRunning.computeIfAbsent(datasourceKey, key -> new AtomicInteger(0)).incrementAndGet();
        return new Lease(this, tenantKey, datasourceKey, true);
    }

    /**
     * 释放执行许可。
     *
     * <p>释放时允许计数被防御性压回 0，避免异常路径重复释放导致负数。
     */
    private synchronized void release(String tenantKey, String datasourceKey) {
        decrement(globalRunning);
        decrementAndCleanup(tenantRunning, tenantKey);
        decrementAndCleanup(datasourceRunning, datasourceKey);
    }

    /**
     * 校验当前维度是否还允许继续获取许可。
     */
    private void ensureWithinLimit(String scope, int current, int limit) {
        if (current >= limit) {
            qualityExecutorMetrics.recordConcurrencyRejected(scope);
            throw new ConcurrencyRejectedException(scope,
                    "质量执行器并发护栏触发: scope=" + scope + ", current=" + current + ", limit=" + limit);
        }
    }

    /**
     * 获取指定 key 的当前计数。
     */
    private int currentValue(Map<String, AtomicInteger> counters, String key) {
        AtomicInteger value = counters.get(key);
        return value == null ? 0 : value.get();
    }

    /**
     * 普通计数器递减。
     */
    private void decrement(AtomicInteger counter) {
        counter.updateAndGet(value -> Math.max(0, value - 1));
    }

    /**
     * Map 计数器递减并在归零后清理 key。
     */
    private void decrementAndCleanup(Map<String, AtomicInteger> counters, String key) {
        AtomicInteger counter = counters.get(key);
        if (counter == null) {
            return;
        }
        int remaining = counter.updateAndGet(value -> Math.max(0, value - 1));
        if (remaining == 0) {
            counters.remove(key, counter);
        }
    }

    private String tenantKey(Long tenantId) {
        return tenantId == null ? UNKNOWN_TENANT : String.valueOf(tenantId);
    }

    private String datasourceKey(Long datasourceId) {
        return datasourceId == null ? UNKNOWN_DATASOURCE : String.valueOf(datasourceId);
    }

    /**
     * 并发护栏许可。
     *
     * <p>实现 AutoCloseable 后，调用方可以使用 try-with-resources，
     * 确保无论扫描成功、失败还是抛异常，许可都会被释放。
     */
    public static final class Lease implements AutoCloseable {

        private static final Lease NOOP = new Lease(null, null, null, false);

        private final QualityExecutorConcurrencyGuard owner;
        private final String tenantKey;
        private final String datasourceKey;
        private final boolean active;
        private boolean closed;

        private Lease(QualityExecutorConcurrencyGuard owner, String tenantKey, String datasourceKey, boolean active) {
            this.owner = owner;
            this.tenantKey = tenantKey;
            this.datasourceKey = datasourceKey;
            this.active = active;
        }

        private static Lease noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (!active || closed) {
                return;
            }
            closed = true;
            owner.release(tenantKey, datasourceKey);
        }
    }

    /**
     * 并发护栏拒绝异常。
     *
     * <p>它继承 RuntimeException，是为了让 try-with-resources 外层执行流程可以统一捕获。
     * coordinator 会专门识别该异常，并调用 task-management defer 接口把任务延迟回队列，
     * 而不是把容量不足误判成业务失败。
     */
    public static class ConcurrencyRejectedException extends RuntimeException {

        private final String scope;

        public ConcurrencyRejectedException(String scope, String message) {
            super(message);
            this.scope = scope;
        }

        public String getScope() {
            return scope;
        }
    }
}
