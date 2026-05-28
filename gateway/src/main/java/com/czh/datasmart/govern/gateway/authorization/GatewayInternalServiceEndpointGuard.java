/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - GatewayInternalServiceEndpointGuard.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import com.czh.datasmart.govern.gateway.config.InternalServiceEndpointProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 网关内部服务端点保护器。
 *
 * <p>这个组件保护的是“虽然挂在 `/api/**` 下，但实际上只应该由内部服务账号调用”的入口。
 * 典型例子是 `/api/agent/plan-ingestions`：它用于 Python AI Runtime 把 AgentPlan 交给 Java 控制面。
 * 如果普通用户直接调用，就可能绕过正常对话入口、模型网关、Skill 选择和运行时事件链路。
 *
 * <p>本组件做三类轻量但关键的入口治理：
 * 1. 路由匹配：只保护配置中声明的内部服务端点；
 * 2. 角色白名单：默认只允许 `SERVICE_ACCOUNT`；
 * 3. 固定窗口限流：按端点、租户、actor 做本地每分钟限流。
 *
 * <p>为什么不把这些逻辑都放进 permission-admin：
 * permission-admin 仍然是权限事实源和审计事实源，但 gateway 位于入口关键路径，
 * 对明显不符合内部协议的请求可以更早拒绝，减少权限中心和下游服务压力。
 * 后续生产化时，本地限流可替换为 Redis 分布式限流或云网关限流，本类仍保留为策略适配层。
 */
@Component
@RequiredArgsConstructor
public class GatewayInternalServiceEndpointGuard {

    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();

    private final GatewayAuthorizationProperties authorizationProperties;
    private final Clock clock = Clock.systemUTC();
    private final ConcurrentMap<String, RateLimitWindow> windows = new ConcurrentHashMap<>();

    /**
     * 校验当前请求是否满足内部服务端点约束。
     *
     * @param request 网关收到的原始请求。
     * @return 如果未命中内部端点，返回 `notProtected`；如果命中，则返回允许或拒绝原因。
     */
    public GuardDecision evaluate(ServerHttpRequest request) {
        InternalServiceEndpointProperties endpoint = resolveEndpoint(request.getPath().value());
        if (endpoint == null) {
            return GuardDecision.notProtected();
        }

        HttpHeaders headers = request.getHeaders();
        String actorRole = normalize(headers.getFirst(PlatformContextHeaders.ACTOR_ROLE));
        if (!roleAllowed(endpoint.getAllowedActorRoles(), actorRole)) {
            return GuardDecision.denied(endpoint.getName(), HttpStatus.FORBIDDEN,
                    "内部服务端点只允许服务账号调用，endpoint=" + endpoint.getName() + ", actorRole=" + actorRole);
        }

        if (!tokenAllowed(endpoint, headers)) {
            return GuardDecision.denied(endpoint.getName(), HttpStatus.FORBIDDEN,
                    "内部服务端点 Token 校验失败，endpoint=" + endpoint.getName());
        }

        if (endpoint.isRateLimitEnabled() && endpoint.getMaxRequestsPerMinute() > 0) {
            RateLimitDecision rateLimit = checkRateLimit(endpoint, request);
            if (!rateLimit.allowed()) {
                return GuardDecision.rateLimited(endpoint.getName(),
                        "内部服务端点触发限流，endpoint=" + endpoint.getName()
                                + ", maxRequestsPerMinute=" + endpoint.getMaxRequestsPerMinute(),
                        rateLimit.retryAfterSeconds());
            }
        }

        return GuardDecision.allowed(endpoint.getName());
    }

    private InternalServiceEndpointProperties resolveEndpoint(String path) {
        List<InternalServiceEndpointProperties> endpoints =
                authorizationProperties.getInternalServiceEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            return null;
        }
        return endpoints.stream()
                .filter(endpoint -> endpoint.getPathPattern() != null && !endpoint.getPathPattern().isBlank())
                .filter(endpoint -> pathMatches(endpoint.getPathPattern(), path))
                .findFirst()
                .orElse(null);
    }

    private boolean roleAllowed(List<String> allowedRoles, String actorRole) {
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            return false;
        }
        return allowedRoles.stream()
                .filter(Objects::nonNull)
                .map(this::normalize)
                .anyMatch(role -> role.equals(actorRole));
    }

    private boolean tokenAllowed(InternalServiceEndpointProperties endpoint,
                                 HttpHeaders headers) {
        String expectedToken = endpoint.getInternalToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            return true;
        }
        String tokenHeaderName = endpoint.getTokenHeaderName();
        if (tokenHeaderName == null || tokenHeaderName.isBlank()) {
            return false;
        }
        return expectedToken.equals(headers.getFirst(tokenHeaderName));
    }

    private RateLimitDecision checkRateLimit(InternalServiceEndpointProperties endpoint,
                                             ServerHttpRequest request) {
        long nowSecond = clock.instant().getEpochSecond();
        long windowStartSecond = nowSecond - (nowSecond % 60);
        String key = endpoint.getName() + ":" + tenantId(request) + ":" + actorId(request);
        RateLimitWindow window = windows.computeIfAbsent(key, ignored -> new RateLimitWindow(windowStartSecond));
        synchronized (window) {
            if (window.windowStartSecond != windowStartSecond) {
                window.windowStartSecond = windowStartSecond;
                window.count = 0;
            }
            if (window.count >= endpoint.getMaxRequestsPerMinute()) {
                long retryAfter = Math.max(1, 60 - (nowSecond - windowStartSecond));
                return new RateLimitDecision(false, retryAfter);
            }
            window.count++;
            return new RateLimitDecision(true, 0);
        }
    }

    private String tenantId(ServerHttpRequest request) {
        String value = request.getHeaders().getFirst(PlatformContextHeaders.TENANT_ID);
        return value == null || value.isBlank() ? "tenant:unknown" : "tenant:" + value.trim();
    }

    private String actorId(ServerHttpRequest request) {
        String value = request.getHeaders().getFirst(PlatformContextHeaders.ACTOR_ID);
        return value == null || value.isBlank() ? "actor:unknown" : "actor:" + value.trim();
    }

    private boolean pathMatches(String pattern, String path) {
        try {
            return PATH_PATTERN_PARSER.parse(pattern).matches(PathContainer.parsePath(path));
        } catch (IllegalArgumentException ignored) {
            if (pattern.endsWith("/**")) {
                String prefix = pattern.substring(0, pattern.length() - 3);
                return path.equals(prefix) || path.startsWith(prefix + "/");
            }
            return pattern.equals(path);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 内部端点保护判定结果。
     *
     * @param protectedEndpoint 是否命中了内部端点保护规则。
     * @param allowed 是否允许继续进入后续 permission-admin 判定链路。
     * @param endpointName 端点规则名称。
     * @param status 拒绝时建议返回的 HTTP 状态码。
     * @param reason 拒绝原因或通过说明。
     * @param retryAfterSeconds 限流拒绝时建议客户端等待秒数。
     */
    public record GuardDecision(boolean protectedEndpoint,
                                boolean allowed,
                                String endpointName,
                                HttpStatus status,
                                String reason,
                                long retryAfterSeconds) {

        public static GuardDecision notProtected() {
            return new GuardDecision(false, true, null, null, null, 0);
        }

        public static GuardDecision allowed(String endpointName) {
            return new GuardDecision(true, true, endpointName, null, "内部服务端点保护通过", 0);
        }

        public static GuardDecision denied(String endpointName, HttpStatus status, String reason) {
            return new GuardDecision(true, false, endpointName, status, reason, 0);
        }

        public static GuardDecision rateLimited(String endpointName, String reason, long retryAfterSeconds) {
            return new GuardDecision(true, false, endpointName, HttpStatus.TOO_MANY_REQUESTS, reason, retryAfterSeconds);
        }
    }

    private record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
    }

    private static class RateLimitWindow {
        private long windowStartSecond;
        private int count;

        private RateLimitWindow(long windowStartSecond) {
            this.windowStartSecond = windowStartSecond;
        }
    }
}
