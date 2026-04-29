/**
 * @Author : Cui
 * @Date: 2026/04/25 23:22
 * @Description DataSmart Govern Backend - GatewayAuthorizationFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationDecisionCache;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionRequest;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionResult;
import com.czh.datasmart.govern.gateway.authorization.PermissionAdminDecisionClient;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.util.Map;
import java.util.Optional;

/**
 * 网关授权过滤器。
 *
 * <p>这个过滤器是 gateway 与 permission-admin 真正联动的第一步。
 * 它会在请求转发到业务服务之前，把请求上下文组装成权限判定请求，调用 permission-admin 的 evaluate 接口，
 * 再根据判定结果决定放行、拒绝，或者在影子模式下只记录不拦截。
 *
 * <p>它解决的是“入口路由级授权”，不是最终完整 IAM：
 * 1. JWT/IdP 登录态解析还没有完成；
 * 2. 数据范围表达式还没有注入到业务服务查询条件；
 * 3. 高风险审批流还没有接入；
 * 4. 缓存、熔断、权限变更事件也还没有生产化。
 *
 * <p>即便如此，这一步很重要，因为它让 permission-admin 从“能查询权限矩阵”前进到“可以参与请求治理”。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthorizationFilter implements GlobalFilter, Ordered {

    private final GatewayAuthorizationProperties authorizationProperties;
    private final PermissionAdminDecisionClient permissionAdminDecisionClient;
    private final GatewayAuthorizationDecisionCache authorizationDecisionCache;
    private final ObjectMapper objectMapper;

    /**
     * 执行路由级授权。
     *
     * <p>执行顺序上，它排在 GatewayContractFilter 之后。
     * 这样可以确保本过滤器读取到的是已经被网关清理和补齐后的 X-DataSmart-* 上下文。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (!authorizationProperties.isEnabled()) {
            return chain.filter(exchange);
        }
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String traceId = request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID);
        GatewayPermissionDecisionRequest decisionRequest = buildDecisionRequest(request);
        Optional<GatewayPermissionDecisionResult> cachedDecision = authorizationDecisionCache.get(decisionRequest);
        if (cachedDecision.isPresent()) {
            log.debug("网关授权缓存命中，traceId={}, role={}, path={}, action={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decisionRequest.getAction());
            return handleDecision(exchange, chain, cachedDecision.get(), decisionRequest, traceId);
        }

        return permissionAdminDecisionClient.evaluate(decisionRequest, traceId)
                .flatMap(decision -> {
                    authorizationDecisionCache.put(decisionRequest, decision);
                    return handleDecision(exchange, chain, decision, decisionRequest, traceId);
                })
                .onErrorResume(error -> handleDecisionError(exchange, chain, error, decisionRequest, traceId));
    }

    /**
     * 处理 permission-admin 返回的判定结果。
     *
     * <p>如果 shadowMode=true，即使权限中心返回拒绝，网关也只记录日志并继续放行。
     * 这适合灰度阶段校验权限矩阵是否准确，避免一上线就误杀真实业务流量。
     */
    private Mono<Void> handleDecision(ServerWebExchange exchange,
                                      GatewayFilterChain chain,
                                      GatewayPermissionDecisionResult decision,
                                      GatewayPermissionDecisionRequest decisionRequest,
                                      String traceId) {
        boolean allowed = Boolean.TRUE.equals(decision.getAllowed());
        if (allowed) {
            log.debug("网关权限判定通过，traceId={}, role={}, path={}, policyId={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decision.getMatchedRoutePolicyId());
            return chain.filter(exchange);
        }

        if (authorizationProperties.isShadowMode()) {
            log.warn("网关权限判定影子拒绝但继续放行，traceId={}, role={}, path={}, reason={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decision.getReason());
            return chain.filter(exchange);
        }

        log.warn("网关权限判定拒绝访问，traceId={}, role={}, path={}, reason={}",
                traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decision.getReason());
        return writeForbidden(exchange.getResponse(), traceId, decision.getReason());
    }

    /**
     * 处理权限中心调用异常。
     *
     * <p>failOpenOnError 是一个非常关键的生产开关：
     * 1. 开发环境可以 fail-open，避免权限中心暂时未启动影响所有联调；
     * 2. 生产环境建议 fail-closed，权限中心不可用时拒绝访问，避免越权风险扩大。
     */
    private Mono<Void> handleDecisionError(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           Throwable error,
                                           GatewayPermissionDecisionRequest decisionRequest,
                                           String traceId) {
        if (authorizationProperties.isFailOpenOnError()) {
            log.warn("权限中心调用异常，按 fail-open 策略继续放行，traceId={}, role={}, path={}, error={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), error.getMessage());
            return chain.filter(exchange);
        }

        log.error("权限中心调用异常，按 fail-closed 策略拒绝访问，traceId={}, role={}, path={}",
                traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), error);
        return writeForbidden(exchange.getResponse(), traceId, "权限中心暂时不可用，网关已拒绝本次访问");
    }

    /**
     * 构造权限判定请求。
     *
     * <p>当前从 X-DataSmart-* Header 读取租户、操作者和角色。
     * 因为 JWT 解析还未落地，所以缺失角色时会使用配置里的 defaultActorRole。
     * 这只是迁移期兜底，不应作为长期生产身份来源。
     */
    private GatewayPermissionDecisionRequest buildDecisionRequest(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String path = request.getPath().value();
        String method = request.getMethod().name();
        Long tenantId = parseLongOrDefault(headers.getFirst(PlatformContextHeaders.TENANT_ID), authorizationProperties.getDefaultTenantId());
        Long actorId = parseLongOrDefault(headers.getFirst(PlatformContextHeaders.ACTOR_ID), authorizationProperties.getAnonymousActorId());
        String actorRole = valueOrDefault(headers.getFirst(PlatformContextHeaders.ACTOR_ROLE), authorizationProperties.getDefaultActorRole());
        AuthorizationMetadata authorizationMetadata = resolveAuthorizationMetadata(path, method);

        return new GatewayPermissionDecisionRequest(
                tenantId,
                actorId,
                actorRole,
                method,
                path,
                authorizationMetadata.resourceType(),
                authorizationMetadata.action()
        );
    }

    /**
     * 解析路由授权元数据。
     *
     * <p>这是当前网关授权链路的关键抽象点。
     * 旧逻辑只能根据 URL 前缀和 HTTP 方法推断资源类型与动作，这在 demo 阶段可以接受，
     * 但商业产品里同一个 HTTP 方法可能代表完全不同的业务动作：
     * POST 可能是创建任务，也可能是重试、取消、审批、导出、回放或触发同步。
     *
     * <p>因此这里优先读取 GatewayAuthorizationProperties.routeMetadata。
     * 配置命中后，resourceType 和 action 都来自配置；
     * 未命中时才回退到 inferResourceType/inferAction，保证旧配置和本地联调不被突然破坏。
     */
    private AuthorizationMetadata resolveAuthorizationMetadata(String path, String method) {
        if (authorizationProperties.getRouteMetadata() == null || authorizationProperties.getRouteMetadata().isEmpty()) {
            return new AuthorizationMetadata(inferResourceType(path), inferAction(method));
        }

        for (GatewayAuthorizationProperties.RouteAuthorizationMetadata metadata : authorizationProperties.getRouteMetadata()) {
            if (metadata.getPathPattern() == null || metadata.getPathPattern().isBlank()) {
                continue;
            }
            if (!pathMatches(metadata.getPathPattern(), path)) {
                continue;
            }

            String resourceType = valueOrDefault(metadata.getResourceType(), inferResourceType(path));
            String action = resolveActionFromMetadata(metadata, method);
            return new AuthorizationMetadata(normalizeContractValue(resourceType), normalizeContractValue(action));
        }

        return new AuthorizationMetadata(inferResourceType(path), inferAction(method));
    }

    /**
     * 从路由元数据中解析动作。
     *
     * <p>methodActions 是可配置 Map，因此这里要处理大小写和空值问题。
     * 如果 GET/POST 等方法没有配置对应动作，则使用该路由的 defaultAction；
     * 如果 defaultAction 也为空，再回退到通用 HTTP 方法推断。
     */
    private String resolveActionFromMetadata(GatewayAuthorizationProperties.RouteAuthorizationMetadata metadata, String method) {
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        Map<String, String> methodActions = metadata.getMethodActions();
        if (methodActions != null && !methodActions.isEmpty()) {
            String action = findActionByMethod(methodActions, normalizedMethod);
            if (action != null && !action.isBlank()) {
                return action;
            }
        }

        return valueOrDefault(metadata.getDefaultAction(), inferAction(method));
    }

    /**
     * 大小写不敏感地读取 HTTP 方法动作映射。
     *
     * <p>配置文件中通常会写 GET、POST，但实际维护中也可能写成 get、Post。
     * 权限这种基础设施配置不应该因为大小写小错误就完全失效，所以这里做一层兼容。
     */
    private String findActionByMethod(Map<String, String> methodActions, String normalizedMethod) {
        String action = methodActions.get(normalizedMethod);
        if (action != null) {
            return action;
        }

        return methodActions.entrySet().stream()
                .filter(entry -> normalizedMethod.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 规范化传给 permission-admin 的契约值。
     *
     * <p>权限中心里的资源类型和动作编码推荐使用大写下划线格式。
     * 网关在这里做一次规范化，可以避免配置中出现 get/view/View 等大小写差异造成策略无法命中。
     */
    private String normalizeContractValue(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 资源类型兜底推断。
     *
     * <p>这是兼容逻辑，不再是首选路径。
     * 当 route-metadata 缺失或漏配时，它保证网关仍然能给 permission-admin 提供基础语义。
     */
    private String inferResourceType(String path) {
        if (path.startsWith("/api/datasource/")) {
            return "DATASOURCE";
        }
        if (path.startsWith("/api/task/")) {
            return "TASK";
        }
        if (path.startsWith("/api/quality/")) {
            return "QUALITY_RULE";
        }
        if (path.startsWith("/api/permission/")) {
            return "SYSTEM_SETTING";
        }
        if (path.startsWith("/api/observability/")) {
            return "AUDIT_LOG";
        }
        return "SYSTEM_SETTING";
    }

    /**
     * 动作兜底推断。
     *
     * <p>这只是最粗粒度的 REST 语义映射。
     * 真实产品中的 EXPORT、APPROVE、RETRY、CANCEL、ARCHIVE、FORCE_TERMINATE 等动作，
     * 应逐步通过 route-metadata 或 permission-admin 端点元数据表达，而不是长期停留在 HTTP 方法推断。
     */
    private String inferAction(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET" -> "VIEW";
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> "EXECUTE";
        };
    }

    /**
     * 判断是否属于公开路径。
     */
    private boolean isPublicPath(String path) {
        return authorizationProperties.getPublicPathPatterns().stream()
                .anyMatch(pattern -> pathMatches(pattern, path));
    }

    /**
     * 简单路径匹配，支持 /** 后缀通配和完全匹配。
     */
    private boolean pathMatches(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return pattern.equals(path);
    }

    /**
     * 解析 Long，失败时返回默认值。
     */
    private Long parseLongOrDefault(String value, Long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 字符串为空时使用默认值。
     */
    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 写出 403 响应。
     *
     * <p>这里直接使用 PlatformApiResponse，是为了让网关拒绝和业务服务拒绝保持同一种响应形态。
     */
    private Mono<Void> writeForbidden(ServerHttpResponse response, String traceId, String message) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        PlatformApiResponse<Void> body = PlatformApiResponse.error(PlatformErrorCode.FORBIDDEN, message, traceId);
        byte[] bytes = serialize(body);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 序列化响应体。
     */
    private byte[] serialize(PlatformApiResponse<Void> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            return "{\"code\":20002,\"reason\":\"FORBIDDEN\",\"message\":\"forbidden\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 排在 GatewayContractFilter 之后执行。
     */
    @Override
    public int getOrder() {
        return -90;
    }

    /**
     * 网关内部使用的授权元数据解析结果。
     *
     * <p>它只在本过滤器内部流转，不暴露给下游服务。
     * 使用 record 可以让 resourceType/action 在构造后保持不可变，避免后续处理链路意外修改判定语义。
     */
    private record AuthorizationMetadata(String resourceType, String action) {
    }
}
