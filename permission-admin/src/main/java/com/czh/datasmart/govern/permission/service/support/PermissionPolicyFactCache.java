/**
 * @Author : Cui
 * @Date: 2026/05/06 21:37
 * @Description DataSmart Govern Backend - PermissionPolicyFactCache.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.config.PermissionPolicyCacheProperties;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeCode;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;

/**
 * permission-admin 内部权限事实缓存。
 *
 * <p>本组件缓存的是“授权判定所需事实”，不是某次请求最终是否放行的结果。
 * 最终结果缓存已经在 gateway 侧存在；这里缓存更底层的策略列表，是为了降低 permission-admin 自身数据库压力。
 *
 * <p>两层缓存的职责差异：
 * 1. gateway 授权判定缓存：缓存“用户 A 以角色 R 访问路径 P 的最终 allow/deny 结果”，命中率高、路径短；
 * 2. permission-admin 权限事实缓存：缓存“租户 T、角色 R 的策略事实”，服务于 evaluate 内部匹配逻辑；
 * 3. 两者都需要策略变更失效，但失效粒度和运行位置不同。
 *
 * <p>为什么要支持事务提交后失效？
 * 路由策略变更方法运行在数据库事务中。如果在事务提交前立即清理缓存，另一个并发 evaluate 请求可能会：
 * 1. 发现缓存空了；
 * 2. 从数据库读到尚未提交前的旧策略；
 * 3. 把旧策略重新写回缓存。
 * 因此策略变更成功后，我们尽量注册 afterCommit 回调，在事务真正提交后再清理对应租户缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionPolicyFactCache {

    private final PermissionPolicyCacheProperties properties;

    /**
     * 路由策略缓存。
     *
     * <p>key 包含 tenantId、roleCode 和 includeDisabled。
     * evaluate 只会读取启用策略；管理后台如果需要包含禁用策略，通常不是高频链路，当前也允许缓存但 TTL 较短。
     */
    private final Map<RoutePolicyCacheKey, CacheEntry<List<PermissionRoutePolicy>>> routePolicyCache = new ConcurrentHashMap<>();

    /**
     * 数据范围策略缓存。
     *
     * <p>key 包含 tenantId、roleCode 和 resourceType。
     * 数据范围直接影响业务模块的查询范围，属于敏感授权事实，因此缓存 TTL 不宜过长。
     */
    private final Map<DataScopePolicyCacheKey, CacheEntry<List<PermissionDataScopePolicy>>> dataScopePolicyCache = new ConcurrentHashMap<>();

    /**
     * 命中、未命中、加载、失效计数。
     *
     * <p>当前先作为运维接口返回数据；后续可以继续暴露为 Micrometer Counter/Gauge，
     * 接入 observability 模块并设置命中率或异常失效告警。
     */
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong loadCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();

    /**
     * 最近一次主动清理时间。
     */
    private volatile Instant lastEvictionTime;

    /**
     * 获取路由策略列表。
     *
     * @param tenantId 租户 ID，null 会被归一化为平台租户 0。
     * @param roleCode 角色编码。角色为空时通常是管理后台列表查询，当前不缓存，避免大范围列表挤占高频授权缓存空间。
     * @param includeDisabled 是否包含禁用策略。
     * @param loader 缓存未命中时的数据加载函数，通常由 PermissionQuerySupport 提供数据库查询。
     * @return 策略列表快照。返回值是不可变 List，避免调用方误改缓存中的集合。
     */
    public List<PermissionRoutePolicy> getRoutePolicies(Long tenantId,
                                                        String roleCode,
                                                        Boolean includeDisabled,
                                                        Supplier<List<PermissionRoutePolicy>> loader) {
        if (!isEnabled() || roleCode == null || roleCode.isBlank()) {
            return safeLoad(loader);
        }

        RoutePolicyCacheKey key = new RoutePolicyCacheKey(
                normalizeTenantId(tenantId),
                normalizeCode(roleCode),
                Boolean.TRUE.equals(includeDisabled)
        );
        return getOrLoad(routePolicyCache, key, properties.getRoutePolicyTtl(), loader);
    }

    /**
     * 获取数据范围策略列表。
     *
     * <p>resourceType 为空时代表管理后台想查看某角色下所有数据范围策略。
     * 这类请求不是授权判定高频路径，当前不缓存，避免大列表污染缓存。
     */
    public List<PermissionDataScopePolicy> getDataScopePolicies(Long tenantId,
                                                                String roleCode,
                                                                String resourceType,
                                                                Supplier<List<PermissionDataScopePolicy>> loader) {
        if (!isEnabled()
                || roleCode == null || roleCode.isBlank()
                || resourceType == null || resourceType.isBlank()) {
            return safeLoad(loader);
        }

        DataScopePolicyCacheKey key = new DataScopePolicyCacheKey(
                normalizeTenantId(tenantId),
                normalizeCode(roleCode),
                normalizeCode(resourceType)
        );
        return getOrLoad(dataScopePolicyCache, key, properties.getDataScopePolicyTtl(), loader);
    }

    /**
     * 清理全部权限事实缓存。
     *
     * <p>适用场景：
     * 1. 种子权限数据重建；
     * 2. 运维排障时怀疑缓存影响结果；
     * 3. 后续收到“全局策略版本变更”事件。
     */
    public CacheSnapshot evictAll(String reason) {
        int removed = routePolicyCache.size() + dataScopePolicyCache.size();
        routePolicyCache.clear();
        dataScopePolicyCache.clear();
        evictionCount.addAndGet(removed);
        lastEvictionTime = Instant.now();
        log.info("已清理 permission-admin 权限事实缓存，reason={}, evicted={}", reason, removed);
        return snapshot();
    }

    /**
     * 按租户清理权限事实缓存。
     *
     * <p>平台默认策略 tenantId=0 会影响所有租户。
     * 如果变更的是平台租户策略，按租户清理无法保证完整一致性，因此会退化为全量清理。
     */
    public CacheSnapshot evictTenant(Long tenantId, String reason) {
        Long normalizedTenantId = normalizeTenantId(tenantId);
        if (PermissionAdminSupport.isPlatformTenant(normalizedTenantId)) {
            return evictAll(reason + ":platform-tenant");
        }

        int removed = removeTenantEntries(normalizedTenantId);
        evictionCount.addAndGet(removed);
        lastEvictionTime = Instant.now();
        log.info("已按租户清理 permission-admin 权限事实缓存，tenantId={}, reason={}, evicted={}",
                normalizedTenantId, reason, removed);
        return snapshot();
    }

    /**
     * 注册“事务提交后”租户缓存失效。
     *
     * <p>如果当前线程没有事务同步上下文，例如未来某个定时任务或手工管理接口直接调用，
     * 则立即失效，保证调用语义仍然明确。
     */
    public void evictTenantAfterCommit(Long tenantId, String reason) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictTenant(tenantId, reason + ":no-transaction");
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictTenant(tenantId, reason + ":after-commit");
            }
        });
    }

    /**
     * 返回缓存运行快照。
     */
    public CacheSnapshot snapshot() {
        return new CacheSnapshot(
                properties.isEnabled(),
                routePolicyCache.size(),
                dataScopePolicyCache.size(),
                properties.getMaxEntries(),
                hitCount.get(),
                missCount.get(),
                loadCount.get(),
                evictionCount.get(),
                lastEvictionTime
        );
    }

    private <K, V> List<V> getOrLoad(Map<K, CacheEntry<List<V>>> cache,
                                     K key,
                                     Duration ttl,
                                     Supplier<List<V>> loader) {
        CacheEntry<List<V>> entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            hitCount.incrementAndGet();
            return entry.value();
        }
        if (entry != null) {
            cache.remove(key);
            evictionCount.incrementAndGet();
        }

        missCount.incrementAndGet();
        List<V> loaded = safeLoad(loader);
        loadCount.incrementAndGet();
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            cache.put(key, new CacheEntry<>(loaded, Instant.now().plus(ttl)));
            pruneIfNeeded();
        }
        return loaded;
    }

    private <T> List<T> safeLoad(Supplier<List<T>> loader) {
        List<T> loaded = loader.get();
        return loaded == null ? List.of() : List.copyOf(loaded);
    }

    private boolean isEnabled() {
        return properties.isEnabled();
    }

    private int removeTenantEntries(Long tenantId) {
        int removed = 0;
        Iterator<RoutePolicyCacheKey> routeIterator = routePolicyCache.keySet().iterator();
        while (routeIterator.hasNext()) {
            if (tenantId.equals(routeIterator.next().tenantId())) {
                routeIterator.remove();
                removed++;
            }
        }

        Iterator<DataScopePolicyCacheKey> dataScopeIterator = dataScopePolicyCache.keySet().iterator();
        while (dataScopeIterator.hasNext()) {
            if (tenantId.equals(dataScopeIterator.next().tenantId())) {
                dataScopeIterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private void pruneIfNeeded() {
        int maxEntries = properties.getMaxEntries();
        int currentSize = routePolicyCache.size() + dataScopePolicyCache.size();
        if (maxEntries <= 0 || currentSize <= maxEntries) {
            return;
        }

        int removed = removeExpiredEntries();
        currentSize = routePolicyCache.size() + dataScopePolicyCache.size();
        if (currentSize <= maxEntries) {
            evictionCount.addAndGet(removed);
            lastEvictionTime = Instant.now();
            return;
        }

        evictAll("policy-cache-capacity-exceeded");
    }

    private int removeExpiredEntries() {
        int removed = 0;
        removed += removeExpired(routePolicyCache);
        removed += removeExpired(dataScopePolicyCache);
        return removed;
    }

    private <K, V> int removeExpired(Map<K, CacheEntry<List<V>>> cache) {
        int removed = 0;
        Iterator<Map.Entry<K, CacheEntry<List<V>>>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private record RoutePolicyCacheKey(Long tenantId, String roleCode, boolean includeDisabled) {
    }

    private record DataScopePolicyCacheKey(Long tenantId, String roleCode, String resourceType) {
    }

    private record CacheEntry<T>(T value, Instant expiresAt) {
        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * 权限事实缓存快照。
     *
     * @param enabled 缓存是否启用。
     * @param routePolicyEntries 路由策略缓存条目数。
     * @param dataScopePolicyEntries 数据范围策略缓存条目数。
     * @param maxEntries 最大条目数。
     * @param hitCount 命中次数。
     * @param missCount 未命中次数。
     * @param loadCount 从数据库加载次数。
     * @param evictionCount 被清理条目累计数。
     * @param lastEvictionTime 最近一次主动失效时间。
     */
    public record CacheSnapshot(boolean enabled,
                                int routePolicyEntries,
                                int dataScopePolicyEntries,
                                int maxEntries,
                                long hitCount,
                                long missCount,
                                long loadCount,
                                long evictionCount,
                                Instant lastEvictionTime) {
    }
}
