/**
 * @Author : Cui
 * @Date: 2026/05/07 21:16
 * @Description DataSmart Govern Backend - DataSourceMetadataDiscoveryCacheSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源元数据发现缓存支撑组件。
 *
 * <p>元数据发现会访问外部业务库的系统表或 JDBC 元数据接口。在配置页面中，用户经常会重复刷新、
 * 调整过滤条件、打开或关闭字段详情。如果每次请求都直接打到源库，一方面会拖慢页面，另一方面也会
 * 给客户生产库造成额外压力。因此这里把“缓存命中、TTL 过期、结果复制”从主发现流程中拆出来。
 *
 * <p>为什么不直接把缓存逻辑留在 {@link DataSourceMetadataDiscoverySupport} 中：
 * 1. 主发现类已经承载连接、权限、字段、索引、采样预览等流程，继续堆缓存会造成职责膨胀；
 * 2. 缓存策略未来很可能升级，比如 Redis 分布式缓存、按租户隔离缓存、按连接器设置不同 TTL；
 * 3. 单独组件可以让 datasource-management 后续支持 PostgreSQL、Kafka、MongoDB、文件等连接器时，
 *    复用同一套“发现结果缓存”边界，而不是每种连接器都重复实现一遍。
 *
 * <p>当前实现是进程内缓存，适合第一阶段本地开发与单实例部署。商业化多实例环境需要进一步演进：
 * 1. 缓存介质升级为 Redis 或元数据结果表，保证多副本之间可共享结果；
 * 2. 缓存 Key 增加租户、项目、角色等维度，避免不同权限上下文复用不该复用的数据；
 * 3. 接入显式失效机制，例如数据源配置变更、连接器升级、手动刷新时主动清理缓存。
 */
@Component
public class DataSourceMetadataDiscoveryCacheSupport {

    /**
     * 进程内元数据发现缓存。
     *
     * <p>Key 由数据源 ID、catalog、schema、表名过滤条件和展开选项组成，Value 保存一次发现结果的快照。
     * 使用 {@link ConcurrentHashMap} 是因为元数据发现接口可能被多个用户或多个页面并发调用，
     * 普通 HashMap 在并发写入时存在结构损坏风险。这里虽然还不是分布式缓存，但至少保证单 JVM 内线程安全。
     */
    private static final Map<String, CachedDiscoveryEntry> METADATA_DISCOVERY_CACHE = new ConcurrentHashMap<>();

    /**
     * 按缓存 Key 尝试读取元数据发现结果。
     *
     * <p>读取时会同时处理 TTL 过期逻辑。如果缓存不存在或已经过期，返回 null，由调用方继续执行真实发现。
     * 如果命中缓存，则返回一份复制后的结果，并把 cacheHit 标记改为 true。复制的目的不是深拷贝所有表字段，
     * 而是避免直接修改缓存对象上的 cacheHit 标识，导致后续“真实发现结果”和“缓存命中结果”语义混淆。
     *
     * @param cacheKey   元数据发现缓存 Key，由主流程根据数据源和请求条件生成
     * @param ttlSeconds 缓存有效秒数，通常来自 MetadataDiscoveryProperties 配置
     * @return 命中的发现结果副本；如果不存在或过期则返回 null
     */
    public DataSourceMetadataDiscoveryResult getCachedDiscoveryResult(String cacheKey, long ttlSeconds) {
        CachedDiscoveryEntry cachedEntry = METADATA_DISCOVERY_CACHE.get(cacheKey);
        if (cachedEntry == null) {
            return null;
        }
        long ttlMillis = ttlSeconds * 1000L;
        if (System.currentTimeMillis() - cachedEntry.cachedAt() > ttlMillis) {
            METADATA_DISCOVERY_CACHE.remove(cacheKey);
            return null;
        }
        return copyDiscoveryResult(cachedEntry.result(), true);
    }

    /**
     * 写入元数据发现结果缓存。
     *
     * <p>写入缓存时保存的是复制对象，而不是主流程刚构建好的 result 引用。这样做是为了减少后续调用方
     * 对返回对象进行补充处理时影响缓存内容的风险。当前结果内的 tables、warnings 仍然是浅复制引用，
     * 因为这些集合在构建完成后按业务约定不再修改；若未来要支持异步补充字段画像或采样统计，应进一步升级为深拷贝。
     *
     * @param cacheKey 元数据发现缓存 Key
     * @param result   本次真实发现得到的结果
     */
    public void cacheDiscoveryResult(String cacheKey, DataSourceMetadataDiscoveryResult result) {
        METADATA_DISCOVERY_CACHE.put(cacheKey,
                new CachedDiscoveryEntry(copyDiscoveryResult(result, false), System.currentTimeMillis()));
    }

    /**
     * 复制发现结果并设置缓存命中标识。
     *
     * <p>cacheHit 是响应语义字段，不应该污染缓存中保存的原始真实发现结果：
     * 1. 第一次真实发现时 cacheHit=false，说明本次确实访问了外部源库；
     * 2. 后续缓存命中时 cacheHit=true，说明本次为了性能保护直接返回缓存；
     * 3. 如果不复制对象而直接修改缓存对象，后续日志、审计、调试时会很难判断结果来源。
     */
    private DataSourceMetadataDiscoveryResult copyDiscoveryResult(DataSourceMetadataDiscoveryResult source, boolean cacheHit) {
        DataSourceMetadataDiscoveryResult copied = new DataSourceMetadataDiscoveryResult();
        copied.setDatasourceId(source.getDatasourceId());
        copied.setDatasourceName(source.getDatasourceName());
        copied.setDatasourceType(source.getDatasourceType());
        copied.setProductName(source.getProductName());
        copied.setProductVersion(source.getProductVersion());
        copied.setDriverName(source.getDriverName());
        copied.setCatalog(source.getCatalog());
        copied.setSchemaPattern(source.getSchemaPattern());
        copied.setTableNamePattern(source.getTableNamePattern());
        copied.setTableCount(source.getTableCount());
        copied.setAppliedMaxTables(source.getAppliedMaxTables());
        copied.setAppliedMaxColumnsPerTable(source.getAppliedMaxColumnsPerTable());
        copied.setAppliedSampleRowLimit(source.getAppliedSampleRowLimit());
        copied.setCacheHit(cacheHit);
        copied.setDiscoveryDurationMs(source.getDiscoveryDurationMs());
        copied.setDiscoveredAt(source.getDiscoveredAt());
        copied.setTables(source.getTables());
        copied.setWarnings(source.getWarnings());
        return copied;
    }

    /**
     * 缓存条目快照。
     *
     * <p>result 是发现结果，cachedAt 是写入缓存的本地时间戳。这里使用 record 可以表达“不可变数据载体”
     * 的语义，避免缓存条目被后续流程随意修改。
     */
    private record CachedDiscoveryEntry(DataSourceMetadataDiscoveryResult result, long cachedAt) {
    }
}
