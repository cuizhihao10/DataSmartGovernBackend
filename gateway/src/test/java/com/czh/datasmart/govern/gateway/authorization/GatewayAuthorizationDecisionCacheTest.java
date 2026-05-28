/**
 * @Author : Cui
 * @Date: 2026/05/23 17:05
 * @Description DataSmart Govern Backend - GatewayAuthorizationDecisionCacheTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关授权判定缓存测试。
 *
 * <p>本测试重点覆盖项目成员授权变更后的缓存失效粒度。
 * PROJECT 数据范围会把权限中心返回的 `authorizedProjectIds` 缓存在 gateway 本地判定结果中。
 * 如果某个 actor 的项目成员关系发生变化，应该只清理该 actor 的缓存，而不是把整个租户的所有热点缓存都清空。
 */
class GatewayAuthorizationDecisionCacheTest {

    /**
     * 项目成员授权变化时，只清理目标 actor 的授权缓存。
     *
     * <p>这个场景对应真实生产问题：
     * 1. actorA 被新增项目授权；
     * 2. actorA 的旧缓存里没有新 projectId，必须失效；
     * 3. actorB 没有发生授权变化，缓存应继续保留，避免无谓回源 permission-admin。
     */
    @Test
    void evictActorOnlyRemovesTargetActorCache() {
        GatewayAuthorizationDecisionCache cache = new GatewayAuthorizationDecisionCache(cacheEnabledProperties());
        GatewayPermissionDecisionRequest actorARequest = request(10L, 1001L, "/api/datasource/sync-tasks");
        GatewayPermissionDecisionRequest actorBRequest = request(10L, 1002L, "/api/datasource/sync-tasks");

        cache.put(actorARequest, allowedDecision());
        cache.put(actorBRequest, allowedDecision());

        cache.evictActor(10L, 1001L, "project-membership-changed");

        assertThat(cache.get(actorARequest)).isEmpty();
        assertThat(cache.get(actorBRequest)).isPresent();
    }

    /**
     * 构造开启缓存的配置。
     *
     * <p>生产环境是否开启缓存由配置决定，单元测试中显式开启，避免默认保守值导致 put/get 不生效。
     */
    private GatewayAuthorizationProperties cacheEnabledProperties() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.setShadowMode(false);
        properties.getCache().setEnabled(true);
        return properties;
    }

    /**
     * 构造网关发送给 permission-admin 的判定请求。
     */
    private GatewayPermissionDecisionRequest request(Long tenantId, Long actorId, String path) {
        return new GatewayPermissionDecisionRequest(
                tenantId,
                actorId,
                "PROJECT_OWNER",
                "GET",
                path,
                "DATASOURCE",
                "VIEW"
        );
    }

    /**
     * 构造允许访问的判定结果。
     */
    private GatewayPermissionDecisionResult allowedDecision() {
        GatewayPermissionDecisionResult decision = new GatewayPermissionDecisionResult();
        decision.setAllowed(true);
        decision.setReason("测试允许");
        decision.setDataScopeLevel("PROJECT");
        decision.setAuthorizedProjectIds(java.util.List.of(101L, 102L));
        return decision;
    }
}
