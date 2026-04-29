/**
 * @Author : Cui
 * @Date: 2026/04/26 20:38
 * @Description DataSmart Govern Backend - GatewayAuthorizationDecisionCache.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关本地授权判定缓存。
 *
 * <p>它缓存的是 gateway 调用 permission-admin `/permissions/evaluate` 后得到的判定结果。
 * 在高并发商业场景里，这一层非常重要：
 * 1. 同一个用户或角色会在短时间内反复访问同一路由，例如列表刷新、轮询、分页查询；
 * 2. 如果每次都远程访问 permission-admin，权限中心会成为全平台入口链路的性能瓶颈；
 * 3. 权限中心偶发抖动也会放大成所有业务模块的入口延迟抖动。
 *
 * <p>为什么当前先做本地缓存，而不是一步到位 Redis？
 * 当前项目还处于平台契约和权限链路建设期，本地缓存能先建立正确的缓存键、TTL、失效和统计模型，
 * 不增加 Redis 序列化、网络故障、集群一致性等额外复杂度。
 * 后续迁移到 Redis 时，可以复用本类对“缓存什么、什么时候缓存、如何失效”的业务判断。
 *
 * <p>重要限制：
 * 本地缓存只对当前 gateway 实例生效。多网关实例部署时，某个实例清理缓存不会自动影响其他实例。
 * 因此生产化必须接入 permission-admin 的权限变更事件，例如 Kafka topic：
 * permission-policy-changed -> 所有 gateway 实例收到事件 -> 清理相关租户/角色/资源缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthorizationDecisionCache {

    /**
     * 本地缓存容器。
     *
     * <p>ConcurrentHashMap 适合当前阶段的高并发读写；它不是最终缓存产品，
     * 但足以支撑本地 TTL 缓存的第一版实现。
     */
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 命中、未命中和清理次数统计。
     *
     * <p>这些统计先用于开发排障，后续可以暴露给 observability 或 Micrometer 指标。
     */
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();

    /**
     * 最近一次清理时间。
     */
    private volatile Instant lastEvictionTime;

    private final GatewayAuthorizationProperties authorizationProperties;

    /**
     * 从缓存读取权限判定结果。
     *
     * <p>返回 Optional.empty() 代表以下几类情况：
     * 1. 缓存功能未启用；
     * 2. 当前请求不适合缓存；
     * 3. 没有缓存条目；
     * 4. 缓存条目已过期并被清理。
     */
    public Optional<GatewayPermissionDecisionResult> get(GatewayPermissionDecisionRequest request) {
        if (!isCacheEnabledForCurrentMode()) {
            return Optional.empty();
        }

        CacheKey key = CacheKey.from(request);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            missCount.incrementAndGet();
            return Optional.empty();
        }

        if (entry.isExpired()) {
            cache.remove(key);
            evictionCount.incrementAndGet();
            missCount.incrementAndGet();
            return Optional.empty();
        }

        hitCount.incrementAndGet();
        return Optional.of(entry.decision());
    }

    /**
     * 写入权限判定结果。
     *
     * <p>不是所有判定都应该缓存：
     * 1. 缓存关闭时不写入；
     * 2. 影子模式默认不写入，因为影子模式更重视真实请求审计和策略观察；
     * 3. 拒绝结果是否缓存由 cacheDeniedDecisions 控制；
     * 4. 需要审批的结果默认不缓存，因为它通常代表高风险动作。
     */
    public void put(GatewayPermissionDecisionRequest request, GatewayPermissionDecisionResult decision) {
        if (!shouldCache(decision)) {
            return;
        }

        Duration ttl = resolveTtl(decision);
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }

        CacheKey key = CacheKey.from(request);
        cache.put(key, new CacheEntry(decision, Instant.now().plus(ttl)));
        pruneIfNeeded();
    }

    /**
     * 清理全部本地缓存。
     *
     * <p>这是最安全也最粗粒度的失效方式。权限策略刚发生大规模变更、权限中心种子数据重建、
     * 或者本地联调发现缓存影响排查时，可以调用该方法。
     */
    public CacheSnapshot evictAll(String reason) {
        int beforeSize = cache.size();
        cache.clear();
        evictionCount.addAndGet(beforeSize);
        lastEvictionTime = Instant.now();
        log.info("已清理网关本地授权缓存，reason={}, evicted={}", reason, beforeSize);
        return snapshot();
    }

    /**
     * 按租户清理本地缓存。
     *
     * <p>多租户系统中，权限变更往往发生在某个租户内部。
     * 如果每次都全量清理，会降低其他租户热点授权缓存命中率；
     * 因此先提供租户维度清理能力，后续还可以扩展到角色、资源类型、策略版本等更细粒度。
     */
    public CacheSnapshot evictTenant(Long tenantId, String reason) {
        if (tenantId == null) {
            return evictAll(reason);
        }

        int removed = 0;
        Iterator<Map.Entry<CacheKey, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheKey, CacheEntry> entry = iterator.next();
            if (tenantId.equals(entry.getKey().tenantId())) {
                iterator.remove();
                removed++;
            }
        }
        evictionCount.addAndGet(removed);
        lastEvictionTime = Instant.now();
        log.info("已按租户清理网关本地授权缓存，tenantId={}, reason={}, evicted={}", tenantId, reason, removed);
        return snapshot();
    }

    /**
     * 返回当前缓存快照。
     *
     * <p>该对象适合管理端点返回给调用方，也适合后续转成 Micrometer Gauge/Counter。
     */
    public CacheSnapshot snapshot() {
        return new CacheSnapshot(
                authorizationProperties.getCache().isEnabled(),
                cache.size(),
                authorizationProperties.getCache().getMaxEntries(),
                hitCount.get(),
                missCount.get(),
                evictionCount.get(),
                lastEvictionTime
        );
    }

    /**
     * 判断当前运行模式是否允许读取或写入缓存。
     */
    private boolean isCacheEnabledForCurrentMode() {
        GatewayAuthorizationProperties.AuthorizationCacheProperties cacheProperties = authorizationProperties.getCache();
        if (!cacheProperties.isEnabled()) {
            return false;
        }
        return !authorizationProperties.isShadowMode() || cacheProperties.isCacheShadowModeDecisions();
    }

    /**
     * 判断某次判定是否应该进入缓存。
     */
    private boolean shouldCache(GatewayPermissionDecisionResult decision) {
        if (!isCacheEnabledForCurrentMode()) {
            return false;
        }
        if (decision == null || decision.getAllowed() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(decision.getApprovalRequired())
                && !authorizationProperties.getCache().isCacheApprovalRequiredDecisions()) {
            return false;
        }
        if (!Boolean.TRUE.equals(decision.getAllowed())
                && !authorizationProperties.getCache().isCacheDeniedDecisions()) {
            return false;
        }
        return true;
    }

    /**
     * 根据判定结果选择 TTL。
     *
     * <p>允许结果一般可以缓存更久，因为它常见于正常业务访问；
     * 拒绝结果建议缓存更短，避免管理员授权后用户仍然被旧拒绝结果影响太久。
     */
    private Duration resolveTtl(GatewayPermissionDecisionResult decision) {
        if (Boolean.TRUE.equals(decision.getAllowed())) {
            return authorizationProperties.getCache().getAllowTtl();
        }
        return authorizationProperties.getCache().getDenyTtl();
    }

    /**
     * 达到容量上限时做保守清理。
     *
     * <p>当前没有引入 Caffeine 等专业本地缓存库，因此这里采用简单策略：
     * 先清理过期条目，如果仍然超过上限，则清空全部缓存。
     * 这不是命中率最优策略，但能保证网关不会因为缓存键爆炸导致内存不可控。
     */
    private void pruneIfNeeded() {
        int maxEntries = authorizationProperties.getCache().getMaxEntries();
        if (maxEntries <= 0 || cache.size() <= maxEntries) {
            return;
        }

        int removed = removeExpiredEntries();
        if (cache.size() <= maxEntries) {
            evictionCount.addAndGet(removed);
            lastEvictionTime = Instant.now();
            return;
        }

        int beforeSize = cache.size();
        cache.clear();
        evictionCount.addAndGet(beforeSize);
        lastEvictionTime = Instant.now();
        log.warn("网关本地授权缓存超过最大容量，已执行保守全量清理，maxEntries={}, evicted={}", maxEntries, beforeSize);
    }

    /**
     * 清理过期条目。
     */
    private int removeExpiredEntries() {
        int removed = 0;
        Iterator<Map.Entry<CacheKey, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheKey, CacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * 缓存键。
     *
     * <p>这里刻意包含 tenantId、actorId、actorRole、method、path、resourceType、action。
     * 从性能角度看，只按 tenant+role+path 缓存命中率更高；
     * 但当前权限中心未来可能加入用户级授权、委托审批、工作区隔离、服务账号差异策略。
     * 第一版选择更保守的键，优先保证不把 A 用户的允许结果误用于 B 用户。
     */
    private record CacheKey(Long tenantId,
                            Long actorId,
                            String actorRole,
                            String requestMethod,
                            String requestPath,
                            String resourceType,
                            String action) {

        private static CacheKey from(GatewayPermissionDecisionRequest request) {
            return new CacheKey(
                    request.getTenantId(),
                    request.getActorId(),
                    request.getActorRole(),
                    request.getHttpMethod(),
                    request.getRequestPath(),
                    request.getResourceType(),
                    request.getAction()
            );
        }
    }

    /**
     * 缓存条目。
     */
    private record CacheEntry(GatewayPermissionDecisionResult decision, Instant expiresAt) {

        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * 缓存运行状态快照。
     *
     * @param enabled 缓存是否启用。
     * @param size 当前本地缓存条目数。
     * @param maxEntries 最大条目数。
     * @param hitCount 命中次数。
     * @param missCount 未命中次数。
     * @param evictionCount 清理条目累计数。
     * @param lastEvictionTime 最近一次清理时间。
     */
    public record CacheSnapshot(boolean enabled,
                                int size,
                                int maxEntries,
                                long hitCount,
                                long missCount,
                                long evictionCount,
                                Instant lastEvictionTime) {
    }
}
