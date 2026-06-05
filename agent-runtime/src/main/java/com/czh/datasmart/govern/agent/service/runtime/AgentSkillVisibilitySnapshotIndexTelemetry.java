/**
 * @Author : Cui
 * @Date: 2026/06/05 00:10
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotIndexTelemetry.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skill 可见性快照专用索引遥测服务。
 *
 * <p>该组件同时承担两类“轻量、低敏、低基数”的可观测性职责：</p>
 * <p>1. 内存诊断：通过 Atomic 计数器记录物化成功、重复、跳过、失败、查询来源和最近错误，供管理 API 返回；</p>
 * <p>2. Prometheus 指标：通过 Micrometer 写入 counter，供 Grafana/Alertmanager 观察索引链路是否持续异常。</p>
 *
 * <p>为什么不把这些计数直接写在 Store 或 Service 里：</p>
 * <p>1. Store 应聚焦“怎么写/查索引”，不应该关心 Prometheus 标签和指标命名；</p>
 * <p>2. ProjectionService 应聚焦“怎么把低敏快照转换成 API 视图”，不应该夹杂指标白名单；</p>
 * <p>3. 遥测独立后，未来可以把 memory/mysql/clickhouse/opensearch 的指标都映射到同一组时序。</p>
 *
 * <p>低基数约束是这里最重要的设计点：指标标签只允许 store、outcome、source、bindingStatus 这类有限枚举。
 * 严禁把 runId、sessionId、requestId、tenantId、projectId、actorId、traceId、manifestFingerprint 放入标签，
 * 否则 Prometheus 会因为业务请求维度无限增长而出现高基数风险。</p>
 */
@Component
public class AgentSkillVisibilitySnapshotIndexTelemetry {

    private static final String METRIC_PREFIX = "datasmart_agent_runtime_skill_visibility_index";
    private static final String UNKNOWN = "unknown";
    private static final String OTHER = "OTHER";

    /**
     * Manifest 绑定状态白名单。
     *
     * <p>这些状态来自 Python Runtime 的智能网关摘要。新增状态时应同步扩展白名单、文档和告警规则。
     * 未知值统一归并 OTHER，避免临时状态、异常文本或第三方输入成为 Prometheus 动态标签。</p>
     */
    private static final Set<String> ALLOWED_BINDING_STATUSES = Set.of(
            "BOUND_REMOTE_MANIFEST",
            "REMOTE_READY_WITHOUT_FINGERPRINT",
            "REMOTE_NOT_REFRESHED",
            "REMOTE_UNAVAILABLE_FALLBACK",
            "LOCAL_DEFAULT_OR_FALLBACK",
            "UNBOUND_NOT_CONFIGURED",
            "DIAGNOSTICS_UNAVAILABLE",
            "UNBOUND_UNKNOWN",
            "UNKNOWN",
            OTHER
    );

    private static final Set<String> ALLOWED_STORES = Set.of("memory", "mysql", "none", OTHER);
    private static final Set<String> ALLOWED_QUERY_SOURCES = Set.of("dedicated", "fallback", OTHER);

    private final MeterRegistry meterRegistry;
    private final AtomicLong materializedCount = new AtomicLong();
    private final AtomicLong duplicateMaterializationCount = new AtomicLong();
    private final AtomicLong skippedMaterializationCount = new AtomicLong();
    private final AtomicLong failedMaterializationCount = new AtomicLong();
    private final AtomicLong dedicatedQueryCount = new AtomicLong();
    private final AtomicLong fallbackQueryCount = new AtomicLong();
    private final AtomicLong failedQueryCount = new AtomicLong();
    private final AtomicLong dedicatedQueryResultCount = new AtomicLong();
    private final AtomicLong fallbackQueryResultCount = new AtomicLong();
    private final Map<String, AtomicLong> manifestBindingStatusCounts = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastMaterializedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastDuplicateMaterializationAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastSkippedMaterializationAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailedMaterializationAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastDedicatedQueryAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastFallbackQueryAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastQueryFailedAt = new AtomicReference<>();
    private final AtomicReference<String> lastFailureStage = new AtomicReference<>();
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>();
    private final AtomicReference<String> lastSkippedReason = new AtomicReference<>();

    public AgentSkillVisibilitySnapshotIndexTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 创建仅内存计数的遥测对象。
     *
     * <p>历史单元测试会手动 new service，不经过 Spring 容器，也就没有 MeterRegistry。
     * 这个工厂方法保证老测试和轻量对象仍然能得到诊断计数，同时不会强行依赖 Prometheus 注册表。</p>
     */
    public static AgentSkillVisibilitySnapshotIndexTelemetry inMemoryOnly() {
        return new AgentSkillVisibilitySnapshotIndexTelemetry(null);
    }

    /**
     * 记录一条 Skill 可见性快照首次进入专用索引。
     *
     * <p>这代表“通用 runtime event projection 已接受该事件，并且专用索引也成功持有低敏能力边界事实”。
     * 对 MySQL store 来说，它意味着后续跨实例/重启查询已经有恢复依据。</p>
     */
    public void recordMaterialized(AgentRuntimeEventProjectionRecord record, String store) {
        materializedCount.incrementAndGet();
        lastMaterializedAt.set(Instant.now());
        String bindingStatus = bindingStatus(record);
        incrementBindingStatus(bindingStatus);
        recordMaterializationCounter("materialized", store, bindingStatus);
    }

    /**
     * 记录专用索引已经存在同一条快照。
     *
     * <p>重复不一定是坏事。Kafka 至少一次投递、consumer rebalance、管理员补偿重放都可能导致重复。
     * 关键是重复必须被 identityKey 收敛，不能把统计和审计事实放大。</p>
     */
    public void recordDuplicate(AgentRuntimeEventProjectionRecord record, String store) {
        duplicateMaterializationCount.incrementAndGet();
        lastDuplicateMaterializationAt.set(Instant.now());
        recordMaterializationCounter("duplicate", store, bindingStatus(record));
    }

    /**
     * 记录 Skill 快照因为专用索引未启用或未注册而跳过物化。
     *
     * <p>本地学习环境可能 intentionally 关闭专用索引；生产环境如果持续出现 skipped，则通常表示配置漏项，
     * 例如启用了 Kafka consumer 但没有启用 Skill index store。</p>
     */
    public void recordSkipped(AgentRuntimeEventProjectionRecord record, String reason) {
        skippedMaterializationCount.incrementAndGet();
        lastSkippedMaterializationAt.set(Instant.now());
        lastSkippedReason.set(reason);
        recordMaterializationCounter("skipped", "none", bindingStatus(record));
    }

    /**
     * 记录专用索引物化失败。
     *
     * <p>失败会被重新抛给上游消费链路；同时记录指标和最近错误，方便运维快速判断是 MySQL 不可用、
     * 表结构缺失、JSON 序列化失败，还是其他 Store 实现异常。</p>
     */
    public void recordMaterializationFailure(AgentRuntimeEventProjectionRecord record, String store, RuntimeException exception) {
        failedMaterializationCount.incrementAndGet();
        lastFailedMaterializationAt.set(Instant.now());
        rememberFailure("materialization", exception);
        recordMaterializationCounter("failed", store, bindingStatus(record));
    }

    /**
     * 记录一次专用索引查询成功。
     *
     * <p>resultCount 作为 counter 增量记录，表达“本实例通过专用索引返回了多少条快照”。
     * 它不是 gauge，也不代表数据库总量；数据库总量由诊断接口通过 store.size() 探测。</p>
     */
    public void recordDedicatedQuery(String store, int resultCount) {
        dedicatedQueryCount.incrementAndGet();
        dedicatedQueryResultCount.addAndGet(Math.max(0, resultCount));
        lastDedicatedQueryAt.set(Instant.now());
        recordQueryCounter("dedicated", "success", store, resultCount);
    }

    /**
     * 记录一次 fallback 查询。
     *
     * <p>fallback 表示当前没有专用索引 Store，查询退回通用 runtime event projection。
     * 生产环境如果长期 fallback，说明 Skill 可见性事实仍未进入专用索引或持久化链路。</p>
     */
    public void recordFallbackQuery(int resultCount) {
        fallbackQueryCount.incrementAndGet();
        fallbackQueryResultCount.addAndGet(Math.max(0, resultCount));
        lastFallbackQueryAt.set(Instant.now());
        recordQueryCounter("fallback", "success", "none", resultCount);
    }

    /**
     * 记录查询失败。
     *
     * <p>查询失败通常会直接影响治理页和运营排障体验，因此单独计数，不与物化失败混在一起。</p>
     */
    public void recordQueryFailure(String source, String store, RuntimeException exception) {
        failedQueryCount.incrementAndGet();
        lastQueryFailedAt.set(Instant.now());
        rememberFailure("query:" + querySource(source), exception);
        recordQueryCounter(source, "failed", store, 0);
    }

    /**
     * 导出当前内存诊断快照。
     *
     * <p>该方法复制 Map 后返回，避免管理 API 调用方拿到内部 ConcurrentHashMap 并误修改计数器。</p>
     */
    public AgentSkillVisibilitySnapshotIndexTelemetrySnapshot snapshot() {
        return new AgentSkillVisibilitySnapshotIndexTelemetrySnapshot(
                materializedCount.get(),
                duplicateMaterializationCount.get(),
                skippedMaterializationCount.get(),
                failedMaterializationCount.get(),
                dedicatedQueryCount.get(),
                fallbackQueryCount.get(),
                failedQueryCount.get(),
                dedicatedQueryResultCount.get(),
                fallbackQueryResultCount.get(),
                bindingStatusSnapshot(),
                lastMaterializedAt.get(),
                lastDuplicateMaterializationAt.get(),
                lastSkippedMaterializationAt.get(),
                lastFailedMaterializationAt.get(),
                lastDedicatedQueryAt.get(),
                lastFallbackQueryAt.get(),
                lastQueryFailedAt.get(),
                lastFailureStage.get(),
                lastFailureReason.get(),
                lastSkippedReason.get()
        );
    }

    private void recordMaterializationCounter(String outcome, String store, String bindingStatus) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_PREFIX + "_materialization_total")
                .description("agent-runtime Skill 可见性快照专用索引物化次数")
                .tag("outcome", outcome)
                .tag("store", store(store))
                .tag("bindingStatus", bindingStatus(bindingStatus))
                .register(meterRegistry)
                .increment();
    }

    private void recordQueryCounter(String source, String result, String store, int resultCount) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_PREFIX + "_query_total")
                .description("agent-runtime Skill 可见性快照查询次数")
                .tag("source", querySource(source))
                .tag("result", result)
                .tag("store", store(store))
                .register(meterRegistry)
                .increment();
        if (resultCount > 0) {
            Counter.builder(METRIC_PREFIX + "_query_result_total")
                    .description("agent-runtime Skill 可见性快照查询返回记录数")
                    .tag("source", querySource(source))
                    .tag("store", store(store))
                    .register(meterRegistry)
                    .increment(resultCount);
        }
    }

    private void incrementBindingStatus(String bindingStatus) {
        manifestBindingStatusCounts
                .computeIfAbsent(bindingStatus(bindingStatus), ignored -> new AtomicLong())
                .incrementAndGet();
    }

    private Map<String, Long> bindingStatusSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        manifestBindingStatusCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().get()));
        return Map.copyOf(snapshot);
    }

    private void rememberFailure(String stage, RuntimeException exception) {
        lastFailureStage.set(stage);
        lastFailureReason.set(safeFailureReason(exception));
    }

    private String safeFailureReason(RuntimeException exception) {
        if (exception == null) {
            return UNKNOWN;
        }
        String message = exception.getMessage();
        String reason = exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ":" + message);
        return reason.length() <= 300 ? reason : reason.substring(0, 300);
    }

    private String bindingStatus(AgentRuntimeEventProjectionRecord record) {
        if (record == null || record.attributes() == null) {
            return "UNKNOWN";
        }
        return bindingStatus(Objects.toString(record.attributes().get("manifestBindingStatus"), "UNKNOWN"));
    }

    private String bindingStatus(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_BINDING_STATUSES.contains(normalized) ? normalized : OTHER;
    }

    private String store(String store) {
        if (store == null || store.isBlank()) {
            return "none";
        }
        String normalized = store.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_STORES.contains(normalized) ? normalized : OTHER;
    }

    private String querySource(String source) {
        if (source == null || source.isBlank()) {
            return OTHER;
        }
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_QUERY_SOURCES.contains(normalized) ? normalized : OTHER;
    }
}
