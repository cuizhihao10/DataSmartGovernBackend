/**
 * @Author : Cui
 * @Date: 2026/04/26 20:42
 * @Description DataSmart Govern Backend - GatewayAuthorizationCacheController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationDecisionCache;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关授权缓存管理控制器。
 *
 * <p>这个控制器默认不会注册，只有显式开启：
 * datasmart.gateway.authorization.cache.management-endpoint.enabled=true
 * 时才会暴露。
 *
 * <p>为什么要默认关闭？
 * 清理授权缓存虽然不直接授予权限，但它会影响网关性能和权限策略生效节奏。
 * 如果没有保护地暴露给外部调用方，攻击者可以反复清理缓存，让所有请求都回源 permission-admin，
 * 造成权限中心压力升高，甚至形成一种低成本的性能型攻击。
 *
 * <p>当前阶段没有完整 JWT/IdP 管理员认证，因此这里使用临时内部 Header Token 保护。
 * 这不是最终生产方案。后续应升级为：
 * 1. 平台管理员角色授权；
 * 2. 服务账号签名；
 * 3. permission-admin 权限变更事件自动失效；
 * 4. observability 记录谁在什么时间清理了哪些缓存。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/gateway/authorization/cache")
@ConditionalOnProperty(
        prefix = "datasmart.gateway.authorization.cache.management-endpoint",
        name = "enabled",
        havingValue = "true"
)
public class GatewayAuthorizationCacheController {

    private final GatewayAuthorizationDecisionCache authorizationDecisionCache;
    private final GatewayAuthorizationProperties authorizationProperties;

    /**
     * 查看当前网关本地授权缓存状态。
     *
     * <p>该接口用于联调和运维排障，例如确认缓存是否启用、当前条目数、命中/未命中/清理次数。
     * 它不返回具体缓存键，避免泄露租户、用户、路径和角色访问模式。
     */
    @GetMapping
    public ResponseEntity<PlatformApiResponse<GatewayAuthorizationDecisionCache.CacheSnapshot>> snapshot(
            @RequestHeader HttpHeaders headers) {
        String traceId = headers.getFirst(PlatformContextHeaders.TRACE_ID);
        if (!hasValidAdminToken(headers)) {
            return ResponseEntity.status(403)
                    .body(PlatformApiResponse.error(PlatformErrorCode.FORBIDDEN, "网关授权缓存管理令牌无效", traceId));
        }

        return ResponseEntity.ok(PlatformApiResponse.success(
                "网关授权缓存状态查询成功",
                authorizationDecisionCache.snapshot(),
                traceId
        ));
    }

    /**
     * 清理当前 gateway 实例的全部本地授权缓存。
     *
     * <p>适用场景：
     * 1. permission-admin 路由策略或数据范围策略发生较大调整；
     * 2. 本地联调发现缓存影响权限问题排查；
     * 3. 权限中心种子数据重建后，需要快速避免旧判定继续生效。
     *
     * <p>注意：当前只清理本实例缓存。多 gateway 实例部署时，需要对每个实例调用，
     * 或者由后续 Kafka 权限变更事件统一广播失效。
     */
    @DeleteMapping
    public ResponseEntity<PlatformApiResponse<GatewayAuthorizationDecisionCache.CacheSnapshot>> evictAll(
            @RequestHeader HttpHeaders headers,
            @RequestParam(value = "reason", required = false, defaultValue = "manual-evict-all") String reason) {
        String traceId = headers.getFirst(PlatformContextHeaders.TRACE_ID);
        if (!hasValidAdminToken(headers)) {
            return ResponseEntity.status(403)
                    .body(PlatformApiResponse.error(PlatformErrorCode.FORBIDDEN, "网关授权缓存管理令牌无效", traceId));
        }

        return ResponseEntity.ok(PlatformApiResponse.success(
                "网关授权缓存已全量清理",
                authorizationDecisionCache.evictAll(reason),
                traceId
        ));
    }

    /**
     * 按租户清理当前 gateway 实例的本地授权缓存。
     *
     * <p>这是比全量清理更温和的策略。真实 SaaS 或多租户私有化部署中，
     * A 租户调整权限不应该让 B 租户的热点缓存全部失效。
     */
    @DeleteMapping("/tenants/{tenantId}")
    public ResponseEntity<PlatformApiResponse<GatewayAuthorizationDecisionCache.CacheSnapshot>> evictTenant(
            @RequestHeader HttpHeaders headers,
            @PathVariable Long tenantId,
            @RequestParam(value = "reason", required = false, defaultValue = "manual-evict-tenant") String reason) {
        String traceId = headers.getFirst(PlatformContextHeaders.TRACE_ID);
        if (!hasValidAdminToken(headers)) {
            return ResponseEntity.status(403)
                    .body(PlatformApiResponse.error(PlatformErrorCode.FORBIDDEN, "网关授权缓存管理令牌无效", traceId));
        }

        return ResponseEntity.ok(PlatformApiResponse.success(
                "网关授权缓存已按租户清理",
                authorizationDecisionCache.evictTenant(tenantId, reason),
                traceId
        ));
    }

    /**
     * 校验临时管理令牌。
     *
     * <p>如果配置中的 token 为空，则始终拒绝。
     * 这样即使有人误开启了管理端点，也不会形成无保护的缓存清理入口。
     */
    private boolean hasValidAdminToken(HttpHeaders headers) {
        GatewayAuthorizationProperties.CacheManagementEndpointProperties endpointProperties =
                authorizationProperties.getCache().getManagementEndpoint();
        String expectedToken = endpointProperties.getToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            return false;
        }

        String providedToken = headers.getFirst(endpointProperties.getTokenHeaderName());
        return expectedToken.equals(providedToken);
    }
}
