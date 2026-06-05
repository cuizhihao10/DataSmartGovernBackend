/**
 * @Author : Cui
 * @Date: 2026/04/25 22:45
 * @Description DataSmart Govern Backend - GatewayContractFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 网关契约过滤器。
 *
 * <p>这个过滤器位于所有业务微服务之前，是 DataSmart Govern 平台级入口契约的第一道落地点。
 * 它不直接实现复杂 RBAC，也不保存业务数据；它负责把每次请求都整理成下游服务可以理解的统一上下文。
 *
 * <p>当前阶段它主要解决四件事：
 * 1. 生成或透传 traceId，让日志、错误响应、审计事件和指标可以串成一条链路；
 * 2. 写入 sourceService 和 requestSource，让下游知道请求从哪个入口进入；
 * 3. 默认清理外部伪造的租户和操作者 Header，避免没有认证中心时出现越权缺口；
 * 4. 保留旧版 X-Request-Id 兼容，降低从早期网关契约迁移到平台契约的成本。
 *
 * <p>为什么这些逻辑要放在 gateway？
 * 因为 datasource-management、data-quality、task-management 等领域服务应该消费“已经可信的上下文”，
 * 而不是各自重复解析外部身份、判断 Header 是否可信。否则每个模块都会长出一套本地安全逻辑，
 * 既难维护，也容易产生安全边界不一致的问题。
 */
@Component
public class GatewayContractFilter implements GlobalFilter, Ordered {

    /**
     * 平台上下文 Header 白名单。
     *
     * <p>这里列出的 Header 会在网关转发前统一清理，然后由网关根据当前安全策略重新写入。
     * 这么做的核心目的，是避免调用方伪造 X-DataSmart-Tenant-Id、X-DataSmart-Actor-Role 等字段。
     * 等后续接入真实 JWT / OAuth2 / 服务账号认证后，也应该由认证结果生成这些 Header，而不是直接相信客户端输入。
     */
    private static final List<String> PLATFORM_CONTEXT_HEADERS = List.of(
            PlatformContextHeaders.TRACE_ID,
            PlatformContextHeaders.TENANT_ID,
            PlatformContextHeaders.ACTOR_ID,
            PlatformContextHeaders.ACTOR_ROLE,
            PlatformContextHeaders.ACTOR_TYPE,
            PlatformContextHeaders.SOURCE_SERVICE,
            PlatformContextHeaders.WORKSPACE_ID,
            PlatformContextHeaders.REQUEST_SOURCE,
            PlatformContextHeaders.TENANT_PLAN_CODE,
            PlatformContextHeaders.WORKSPACE_RISK_LEVEL,
            PlatformContextHeaders.TOOL_BUDGET_POLICY_VERSION,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_VERSION,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_TTL_SECONDS,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_SCOPE,
            PlatformContextHeaders.DATA_SCOPE_LEVEL,
            PlatformContextHeaders.DATA_SCOPE_EXPRESSION,
            PlatformContextHeaders.AUTHORIZED_PROJECT_IDS,
            PlatformContextHeaders.APPROVAL_REQUIRED
    );

    /**
     * 网关上下文传播策略配置。
     *
     * <p>过滤器执行固定流程，配置描述当前环境策略。这样做能避免后续为了本地开发、生产安全、内部网关、
     * Agent 网关等不同场景反复修改过滤器核心代码。
     */
    private final GatewayContextProperties contextProperties;

    public GatewayContractFilter(GatewayContextProperties contextProperties) {
        this.contextProperties = contextProperties;
    }

    /**
     * 统一补充网关上下文请求头。
     *
     * <p>请求进入网关后，这个方法会先解析 traceId，再清理所有平台上下文 Header，最后写入网关认可的新上下文。
     * 清理再写入的顺序很重要：如果只是在原请求 Header 上追加值，那么下游可能看到多个同名 Header，
     * 进而因为框架取第一个值还是最后一个值不同而产生不可预测的权限和审计结果。
     *
     * <p>当前还没有完整认证中心，所以这里不会凭空生成 tenantId 或 actorId。
     * 它只负责 traceId、sourceService、requestSource 等非身份字段；身份字段要么来自未来认证过滤器，
     * 要么在明确开启 trustIncomingPlatformContext 的受控联调场景中从可信上游复制。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = resolveTraceId(request);
        String routePrefix = resolveRoutePrefix(request.getPath().value());

        ServerHttpRequest mutatedRequest = request.mutate()
                .headers(headers -> {
                    clearUnsafeContextHeaders(headers);
                    writeGatewayOwnedHeaders(request, headers, traceId, routePrefix);

                    if (contextProperties.isTrustIncomingPlatformContext()) {
                        copyTrustedIncomingContext(request, headers);
                    }
                })
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 清理不应被外部调用方直接控制的平台上下文 Header。
     *
     * <p>这一步是安全边界的一部分。举例来说，如果客户端直接传入
     * X-DataSmart-Tenant-Id=1 和 X-DataSmart-Actor-Role=PLATFORM_ADMINISTRATOR，
     * 在没有清理机制的情况下，下游服务可能误以为这是网关认证后的结果。
     */
    private void clearUnsafeContextHeaders(HttpHeaders headers) {
        PLATFORM_CONTEXT_HEADERS.forEach(headers::remove);
        headers.remove(contextProperties.getRoutePrefixHeader());
        headers.remove(contextProperties.getOriginalPathHeader());

        if (contextProperties.isMirrorTraceIdToLegacyRequestId()) {
            headers.remove(contextProperties.getLegacyRequestIdHeader());
        }
    }

    /**
     * 写入由网关负责维护的上下文 Header。
     *
     * <p>这些字段不依赖具体业务模块，因此适合在网关统一生成：
     * traceId 用于链路追踪；sourceService 用于审计来源；requestSource 用于区分请求入口；
     * routePrefix 和 originalPath 用于路由排障。
     */
    private void writeGatewayOwnedHeaders(ServerHttpRequest request,
                                          HttpHeaders headers,
                                          String traceId,
                                          String routePrefix) {
        headers.add(PlatformContextHeaders.TRACE_ID, traceId);
        headers.add(PlatformContextHeaders.SOURCE_SERVICE, contextProperties.getSourceService());
        headers.add(PlatformContextHeaders.REQUEST_SOURCE, resolveRequestSource(request));
        headers.add(contextProperties.getRoutePrefixHeader(), routePrefix);
        headers.add(contextProperties.getOriginalPathHeader(), request.getURI().getPath());

        if (contextProperties.isMirrorTraceIdToLegacyRequestId()) {
            headers.add(contextProperties.getLegacyRequestIdHeader(), traceId);
        }
    }

    /**
     * 解析本次请求的 traceId。
     *
     * <p>traceId 与租户、角色不同，它不会直接决定权限，所以可以优先接收上游传入的
     * X-DataSmart-Trace-Id，方便和外部调用方、负载均衡器、测试工具串联链路。
     * 如果新 Header 不存在，则兼容旧的 X-Request-Id；两者都没有时由网关生成 UUID。
     */
    private String resolveTraceId(ServerHttpRequest request) {
        String platformTraceId = request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID);
        if (platformTraceId != null && !platformTraceId.isBlank()) {
            return platformTraceId.trim();
        }

        String legacyRequestId = request.getHeaders().getFirst(contextProperties.getLegacyRequestIdHeader());
        if (legacyRequestId != null && !legacyRequestId.isBlank()) {
            return legacyRequestId.trim();
        }

        return UUID.randomUUID().toString();
    }

    /**
     * 解析请求来源。
     *
     * <p>当 trustIncomingPlatformContext=false 时，即使调用方传入 X-DataSmart-Request-Source，
     * 网关也不会信任它，而是使用配置中的默认值。这样做是为了避免外部用户把自己伪装成
     * SCHEDULER、SERVICE_ACCOUNT 或 AGENT_TOOL_CALL 等内部来源。
     */
    private String resolveRequestSource(ServerHttpRequest request) {
        if (!contextProperties.isTrustIncomingPlatformContext()) {
            return contextProperties.getDefaultRequestSource();
        }

        String incomingRequestSource = request.getHeaders().getFirst(PlatformContextHeaders.REQUEST_SOURCE);
        if (incomingRequestSource == null || incomingRequestSource.isBlank()) {
            return contextProperties.getDefaultRequestSource();
        }
        return incomingRequestSource.trim();
    }

    /**
     * 复制可信上游传入的租户、操作者和工作区上下文。
     *
     * <p>该方法只有在 trustIncomingPlatformContext=true 时才会被调用。
     * 典型使用场景包括：
     * 1. 前面还有一层企业统一网关已经完成认证和租户解析；
     * 2. 本地开发阶段需要用 Header 模拟不同租户和角色；
     * 3. 内部服务账号通过受控网络调用本项目网关。
     *
     * <p>生产环境更推荐由认证过滤器解析 JWT/服务账号凭证后写入这些 Header，
     * 而不是直接信任客户端原样传入的身份字段。
     */
    private void copyTrustedIncomingContext(ServerHttpRequest request, HttpHeaders headers) {
        copyIfPresent(request, headers, PlatformContextHeaders.TENANT_ID);
        copyIfPresent(request, headers, PlatformContextHeaders.ACTOR_ID);
        copyIfPresent(request, headers, PlatformContextHeaders.ACTOR_ROLE);
        copyIfPresent(request, headers, PlatformContextHeaders.ACTOR_TYPE);
        copyIfPresent(request, headers, PlatformContextHeaders.WORKSPACE_ID);
    }

    /**
     * 如果原始请求中存在非空 Header，则复制到即将转发给下游的 Header 集合。
     */
    private void copyIfPresent(ServerHttpRequest request, HttpHeaders headers, String headerName) {
        String value = request.getHeaders().getFirst(headerName);
        if (value != null && !value.isBlank()) {
            headers.add(headerName, value.trim());
        }
    }

    /**
     * 根据请求路径推断它命中了哪个网关前缀。
     *
     * <p>这里刻意保持显式判断，而不是过度抽象，是为了让学习者可以直接把代码和 application.yml
     * 中的 Spring Cloud Gateway 路由规则对应起来。后续如果路由数量增加，可以再演进成配置驱动的 Route Metadata。
     */
    private String resolveRoutePrefix(String path) {
        if (path.startsWith("/api/task/")) {
            return "/api/task/**";
        }
        if (path.startsWith("/api/permission/")) {
            return "/api/permission/**";
        }
        if (path.startsWith("/api/datasource/")) {
            return "/api/datasource/**";
        }
        if (path.startsWith("/api/sync/")) {
            return "/api/sync/**";
        }
        if (path.startsWith("/api/quality/")) {
            return "/api/quality/**";
        }
        if (path.startsWith("/api/observability/")) {
            return "/api/observability/**";
        }
        if (path.startsWith("/api/agent/")) {
            return "/api/agent/**";
        }
        return "/**";
    }

    /**
     * 过滤器顺序尽量靠前，让后续 Spring Cloud Gateway 过滤器链和业务转发都能拿到补齐后的上下文。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
