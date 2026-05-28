/**
 * @Author : Cui
 * @Date: 2026/05/25 01:38
 * @Description DataSmart Govern Backend - GatewayAgentEventWebSocketGuardFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.gateway.config.GatewayAgentEventWebSocketProperties;
import com.czh.datasmart.govern.gateway.monitoring.GatewayAgentEventWebSocketMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 实时事件 WebSocket 入口守卫过滤器。
 *
 * <p>这个过滤器位于网关上下文补齐之后、权限中心判定之前。这样设计有三个原因：
 * 1. `GatewayContractFilter` 已经写入 traceId、sourceService、requestSource 等基础上下文，拒绝响应可以带上 traceId；
 * 2. `GatewayDevelopmentIdentityFilter` 已经能在本地开发模式下注入 tenant/actor，上限可以按租户和用户统计；
 * 3. `GatewayAuthorizationFilter` 仍然负责真正的 RBAC/route policy，本过滤器只做长连接资源治理，不替代权限中心。
 *
 * <p>当前实现是单 gateway 实例内存计数。它不是最终的分布式配额系统，但已经能覆盖本地开发、单实例部署和最基础的
 * “不要让一个用户无限打开实时事件连接”问题。后续生产多实例可以把连接租约迁移到 Redis，或者让网关接入统一限流中心。
 */
@Slf4j
@Component
public class GatewayAgentEventWebSocketGuardFilter implements GlobalFilter, Ordered {

    private final GatewayAgentEventWebSocketProperties properties;
    private final GatewayAgentEventWebSocketMetrics metrics;
    private final ObjectMapper objectMapper;

    /**
     * 当前 gateway 实例内的全局活跃连接数。
     *
     * <p>该值会被 Micrometer gauge 读取。它只表达当前 JVM 实例，不代表整个 gateway 集群。
     */
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    /**
     * 当前实例内每个租户的活跃连接数。
     *
     * <p>key 使用 tenantId 字符串。未识别租户时使用 `UNKNOWN_TENANT`，避免空 key 造成统计混乱。
     */
    private final ConcurrentMap<String, AtomicInteger> tenantConnections = new ConcurrentHashMap<>();

    /**
     * 当前实例内每个操作者的活跃连接数。
     *
     * <p>key 使用 `tenantId:actorId`。把 tenantId 拼进去是为了避免不同租户里相同 actorId 互相影响。
     */
    private final ConcurrentMap<String, AtomicInteger> actorConnections = new ConcurrentHashMap<>();

    /**
     * 显式构造器。
     *
     * <p>这里不使用 Lombok 生成构造器，是因为过滤器创建完成时需要把 `activeConnections` 绑定到 Micrometer gauge。
     * 如果依赖 Lombok 的默认构造逻辑，指标注册步骤容易被遗漏；显式构造器让依赖注入和初始化副作用一目了然。
     */
    public GatewayAgentEventWebSocketGuardFilter(GatewayAgentEventWebSocketProperties properties,
                                                 GatewayAgentEventWebSocketMetrics metrics,
                                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.metrics.bindActiveConnectionsGauge(activeConnections);
    }

    /**
     * 对 Agent 实时事件 WebSocket 握手执行入口治理。
     *
     * <p>正常流程：
     * 1. 非 `/api/agent/events/ws` 路径直接放行；
     * 2. 校验是否为 WebSocket Upgrade 请求；
     * 3. 尝试占用全局、租户、操作者三个层级的连接名额；
     * 4. 放行到后续授权过滤器和 Spring Cloud Gateway WebSocket Routing Filter；
     * 5. WebSocket 连接结束后释放名额。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!properties.isEnabled() || !isAgentEventWebSocketPath(request)) {
            return chain.filter(exchange);
        }

        String traceId = request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID);
        if (properties.isRequireUpgradeHeader() && !isWebSocketUpgrade(request)) {
            metrics.recordRejected("INVALID_UPGRADE");
            log.warn("Agent 实时事件 WebSocket 握手格式非法，traceId={}, path={}", traceId, request.getPath().value());
            return writeError(exchange.getResponse(), HttpStatus.BAD_REQUEST, PlatformErrorCode.BAD_REQUEST,
                    traceId, "Agent 实时事件入口只接受 WebSocket Upgrade 请求。");
        }

        ConnectionIdentity identity = resolveIdentity(request);
        ConnectionAcquireResult acquireResult = tryAcquire(identity);
        if (!acquireResult.acquired()) {
            metrics.recordRejected(acquireResult.reason());
            log.warn("Agent 实时事件 WebSocket 连接配额拒绝，traceId={}, tenantId={}, actorId={}, reason={}",
                    traceId, identity.tenantId(), identity.actorId(), acquireResult.reason());
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(properties.getRetryAfterSeconds()));
            return writeError(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS, PlatformErrorCode.RATE_LIMITED,
                    traceId, acquireResult.message());
        }

        metrics.recordAccepted();
        log.debug("Agent 实时事件 WebSocket 连接进入后续网关链路，traceId={}, tenantId={}, actorId={}, active={}",
                traceId, identity.tenantId(), identity.actorId(), activeConnections.get());

        /*
         * 对 WebSocket 来说，chain.filter(exchange) 返回的 Mono 通常会在连接关闭、异常或取消时结束。
         * doFinally 能覆盖完成、错误、取消三类信号，确保计数不会因为客户端断开或下游异常而泄漏。
         */
        return chain.filter(exchange)
                .doFinally(signalType -> {
                    release(identity);
                    metrics.recordClosed(signalType.name());
                    log.debug("Agent 实时事件 WebSocket 连接释放，traceId={}, tenantId={}, actorId={}, signal={}, active={}",
                            traceId, identity.tenantId(), identity.actorId(), signalType, activeConnections.get());
                });
    }

    /**
     * 判断当前请求是否命中 Agent 实时事件入口。
     *
     * <p>这里使用完整路径精确匹配，避免 `/api/agent/events/ws-debug`、`/api/agent/events/ws/history`
     * 这类未来扩展接口被错误当成长连接入口治理。
     */
    private boolean isAgentEventWebSocketPath(ServerHttpRequest request) {
        return Objects.equals(properties.getPath(), request.getPath().value());
    }

    /**
     * 判断当前请求是否为 WebSocket Upgrade 握手。
     *
     * <p>HTTP Header 名称大小写不敏感，但 `Connection` 的值里可能包含多个 token，例如
     * `Connection: keep-alive, Upgrade`。因此这里用 contains 风格判断，而不是要求完全等于 `Upgrade`。
     */
    private boolean isWebSocketUpgrade(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String upgrade = headers.getUpgrade();
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            return false;
        }
        return headers.getConnection().stream()
                .anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains("upgrade"));
    }

    /**
     * 从平台上下文 Header 解析连接身份。
     *
     * <p>这里不直接解析 JWT，因为当前网关上下文职责已经拆分：认证或开发身份过滤器负责把可信身份写入 Header，
     * 本过滤器只消费统一上下文。这样后续接入企业 IdP、服务账号签名或多渠道入口时，不需要改连接配额逻辑。
     */
    private ConnectionIdentity resolveIdentity(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String tenantId = valueOrUnknown(headers.getFirst(PlatformContextHeaders.TENANT_ID), "UNKNOWN_TENANT");
        String actorId = valueOrUnknown(headers.getFirst(PlatformContextHeaders.ACTOR_ID), "UNKNOWN_ACTOR");
        return new ConnectionIdentity(tenantId, actorId);
    }

    /**
     * 按全局、租户、操作者三个层级尝试占用连接名额。
     *
     * <p>顺序从粗到细：先保护 gateway 实例整体，再保护租户公平性，最后保护单个用户或服务账号。
     * 任何一级失败都会回滚已经占用的上级计数，避免拒绝请求造成连接数泄漏。
     */
    private ConnectionAcquireResult tryAcquire(ConnectionIdentity identity) {
        int global = activeConnections.incrementAndGet();
        if (exceeds(global, properties.getMaxActiveConnections())) {
            activeConnections.decrementAndGet();
            return ConnectionAcquireResult.rejected("GLOBAL_LIMIT", "当前网关实例 Agent 实时事件连接数已达到上限。");
        }

        AtomicInteger tenantCounter = tenantConnections.computeIfAbsent(identity.tenantId(), ignored -> new AtomicInteger(0));
        int tenant = tenantCounter.incrementAndGet();
        if (exceeds(tenant, properties.getMaxConnectionsPerTenant())) {
            decrementAndCleanup(activeConnections, null, null);
            decrementAndCleanup(tenantCounter, tenantConnections, identity.tenantId());
            return ConnectionAcquireResult.rejected("TENANT_LIMIT", "当前租户 Agent 实时事件连接数已达到上限。");
        }

        String actorKey = identity.actorKey();
        AtomicInteger actorCounter = actorConnections.computeIfAbsent(actorKey, ignored -> new AtomicInteger(0));
        int actor = actorCounter.incrementAndGet();
        if (exceeds(actor, properties.getMaxConnectionsPerActor())) {
            decrementAndCleanup(activeConnections, null, null);
            decrementAndCleanup(tenantCounter, tenantConnections, identity.tenantId());
            decrementAndCleanup(actorCounter, actorConnections, actorKey);
            return ConnectionAcquireResult.rejected("ACTOR_LIMIT", "当前用户 Agent 实时事件连接数已达到上限。");
        }

        return ConnectionAcquireResult.accepted();
    }

    /**
     * 判断当前值是否超过配置阈值。
     *
     * <p>小于等于 0 表示“不限制”，便于本地压测或特殊租户场景临时关闭某一层配额。
     */
    private boolean exceeds(int current, int limit) {
        return limit > 0 && current > limit;
    }

    /**
     * 释放连接占用的三层计数。
     *
     * <p>该方法必须允许重复调用和局部缺失，因为真实网络断开、下游异常、测试取消都可能让释放路径以不同信号进入。
     */
    private void release(ConnectionIdentity identity) {
        decrementAndCleanup(activeConnections, null, null);
        decrementAndCleanup(tenantConnections.get(identity.tenantId()), tenantConnections, identity.tenantId());
        decrementAndCleanup(actorConnections.get(identity.actorKey()), actorConnections, identity.actorKey());
    }

    /**
     * 对一个计数器减一，并在归零后从 owner map 中移除。
     *
     * <p>及时清理 map key 可以避免大量短连接、临时用户或服务账号在网关内存中留下永久空计数器。
     */
    private void decrementAndCleanup(AtomicInteger counter, ConcurrentMap<String, AtomicInteger> owner, String key) {
        if (counter == null) {
            return;
        }
        int value = counter.decrementAndGet();
        if (owner != null && key != null && value <= 0) {
            owner.remove(key, counter);
        }
    }

    private String valueOrUnknown(String value, String unknown) {
        if (value == null || value.isBlank()) {
            return unknown;
        }
        return value.trim();
    }

    /**
     * 写出统一平台错误响应。
     *
     * <p>WebSocket 握手失败仍然发生在 HTTP 阶段，因此可以返回 JSON envelope。真正升级成功后，
     * 协议错误才应由 Python Runtime 通过 WebSocket control frame 表达。
     */
    private Mono<Void> writeError(ServerHttpResponse response,
                                  HttpStatus status,
                                  PlatformErrorCode errorCode,
                                  String traceId,
                                  String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        PlatformApiResponse<Void> body = PlatformApiResponse.error(errorCode, message, traceId);
        DataBuffer buffer = response.bufferFactory().wrap(serialize(body));
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serialize(PlatformApiResponse<Void> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            return "{\"code\":90000,\"reason\":\"INTERNAL_ERROR\",\"message\":\"gateway websocket guard error\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 排在开发身份注入之后、授权过滤器之前。
     */
    @Override
    public int getOrder() {
        return -93;
    }

    /**
     * 连接配额使用的最小身份对象。
     */
    private record ConnectionIdentity(String tenantId, String actorId) {

        private String actorKey() {
            return tenantId + ":" + actorId;
        }
    }

    /**
     * 连接名额申请结果。
     */
    private record ConnectionAcquireResult(boolean acquired, String reason, String message) {

        private static ConnectionAcquireResult accepted() {
            return new ConnectionAcquireResult(true, "OK", null);
        }

        private static ConnectionAcquireResult rejected(String reason, String message) {
            return new ConnectionAcquireResult(false, reason, message);
        }
    }
}
