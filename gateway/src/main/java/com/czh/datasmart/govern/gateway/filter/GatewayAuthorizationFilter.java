/**
 * @Author : Cui
 * @Date: 2026/04/25 23:22
 * @Description DataSmart Govern Backend - GatewayAuthorizationFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationDecisionCache;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationErrorWriter;
import com.czh.datasmart.govern.gateway.authorization.GatewayInternalServiceEndpointGuard;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionRequest;
import com.czh.datasmart.govern.gateway.authorization.GatewayPermissionDecisionResult;
import com.czh.datasmart.govern.gateway.authorization.GatewayServiceAccountDelegationSupport;
import com.czh.datasmart.govern.gateway.authorization.PermissionAdminDecisionClient;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import com.czh.datasmart.govern.gateway.monitoring.GatewayAuthorizationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
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

    /**
     * 路由授权元数据的路径模式解析器。
     *
     * <p>网关这一侧必须和 permission-admin 使用相同级别的匹配能力，否则会出现“网关把请求解释成 A 资源，
     * 权限中心路由策略按 B 路径命中”的认知差异。引入 PathPatternParser 后，配置可以表达端点级规则：
     * 1. 同步事故工作台；
     * 2. 某个同步任务下的人工介入高风险动作；
     * 3. 某个同步任务、某次 execution 下的执行器服务账号回调。
     */
    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();

    private final GatewayAuthorizationProperties authorizationProperties;
    private final PermissionAdminDecisionClient permissionAdminDecisionClient;
    private final GatewayAuthorizationDecisionCache authorizationDecisionCache;
    private final GatewayAuthorizationMetrics authorizationMetrics;
    private final GatewayInternalServiceEndpointGuard internalServiceEndpointGuard;
    private final GatewayAuthorizationErrorWriter authorizationErrorWriter;
    private final GatewayServiceAccountDelegationSupport serviceAccountDelegationSupport;

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
            authorizationMetrics.recordBypass("AUTH_DISABLED");
            return chain.filter(exchange);
        }
        if (isPublicPath(path)) {
            authorizationMetrics.recordBypass("PUBLIC_PATH");
            return chain.filter(exchange);
        }

        String traceId = request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID);
        GatewayInternalServiceEndpointGuard.GuardDecision guardDecision = internalServiceEndpointGuard.evaluate(request);
        if (guardDecision.protectedEndpoint()) {
            if (!guardDecision.allowed()) {
                authorizationMetrics.recordInternalEndpointGuard(guardDecision.endpointName(),
                        guardDecision.status() == HttpStatus.TOO_MANY_REQUESTS ? "RATE_LIMITED" : "DENY");
                log.warn("网关内部服务端点保护拒绝请求，traceId={}, endpoint={}, path={}, status={}, reason={}",
                        traceId, guardDecision.endpointName(), path, guardDecision.status(), guardDecision.reason());
                return authorizationErrorWriter.writeGuardDenied(exchange.getResponse(), traceId, guardDecision);
            }
            authorizationMetrics.recordInternalEndpointGuard(guardDecision.endpointName(), "ALLOW");
        }

        GatewayPermissionDecisionRequest decisionRequest = buildDecisionRequest(request);
        Optional<GatewayPermissionDecisionResult> cachedDecision = authorizationDecisionCache.get(decisionRequest);
        if (cachedDecision.isPresent()) {
            authorizationMetrics.recordCacheAccess(true);
            log.debug("网关授权缓存命中，traceId={}, role={}, path={}, action={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decisionRequest.getAction());
            return handleDecision(exchange, chain, cachedDecision.get(), decisionRequest, traceId, "CACHE");
        }
        authorizationMetrics.recordCacheAccess(false);

        long decisionStartedAt = System.nanoTime();
        return permissionAdminDecisionClient.evaluate(decisionRequest, traceId)
                .flatMap(decision -> {
                    authorizationDecisionCache.put(decisionRequest, decision);
                    authorizationMetrics.recordDecisionLatency("REMOTE",
                            Duration.ofNanos(System.nanoTime() - decisionStartedAt));
                    return handleDecision(exchange, chain, decision, decisionRequest, traceId, "REMOTE");
                })
                .onErrorResume(error -> {
                    authorizationMetrics.recordDecisionLatency("REMOTE",
                            Duration.ofNanos(System.nanoTime() - decisionStartedAt));
                    return handleDecisionError(exchange, chain, error, decisionRequest, traceId, "REMOTE");
                });
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
                                      String traceId,
                                      String decisionSource) {
        boolean allowed = Boolean.TRUE.equals(decision.getAllowed());
        if (allowed) {
            authorizationMetrics.recordDecisionOutcome(decisionSource, "ALLOW");
            log.debug("网关权限判定通过，traceId={}, role={}, path={}, policyId={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decision.getMatchedRoutePolicyId());
            return chain.filter(exchangeWithDataScope(exchange, decision));
        }

        if (authorizationProperties.isShadowMode()) {
            authorizationMetrics.recordDecisionOutcome(decisionSource, "SHADOW_DENY");
            log.warn("网关权限判定影子拒绝但继续放行，traceId={}, role={}, path={}, reason={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decision.getReason());
            return chain.filter(exchange);
        }

        authorizationMetrics.recordDecisionOutcome(decisionSource, "DENY");
        log.warn("网关权限判定拒绝访问，traceId={}, role={}, path={}, reason={}",
                traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), decision.getReason());
        return authorizationErrorWriter.writeForbidden(exchange.getResponse(), traceId, decision.getReason());
    }

    /**
     * 把 permission-admin 的数据范围判定结果透传给下游业务服务。
     *
     * <p>路由授权只能回答“这个角色是否允许访问这个入口”，但真正的商业化权限还必须回答
     * “这个角色进入列表页后最多能看到哪些数据”。permission-admin 已经在判定结果中返回
     * dataScopeLevel、dataScopeExpression 和 approvalRequired，如果 gateway 不把这些字段继续传给业务服务，
     * 那么权限中心的范围策略就只停留在审计记录里，无法真正约束 SQL 查询。
     *
     * <p>这里有意只做可信透传，不在 gateway 解析表达式：
     * 1. gateway 不掌握每个业务表的字段、索引和关联关系；
     * 2. 不同模块对 SELF、PROJECT、TENANT 的落地字段不同；
     * 3. 把表达式解析下沉到业务模块，可以让 data-sync、datasource-management、data-quality 各自选择最合适的查询条件。
     */
    private ServerWebExchange exchangeWithDataScope(ServerWebExchange exchange,
                                                    GatewayPermissionDecisionResult decision) {
        ServerHttpRequest scopedRequest = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    setHeaderIfPresent(headers, PlatformContextHeaders.DATA_SCOPE_LEVEL, decision.getDataScopeLevel());
                    setHeaderIfPresent(headers, PlatformContextHeaders.DATA_SCOPE_EXPRESSION, decision.getDataScopeExpression());
                    setAuthorizedProjectIds(headers, decision.getAuthorizedProjectIds());
                    if (decision.getApprovalRequired() != null) {
                        headers.set(PlatformContextHeaders.APPROVAL_REQUIRED, decision.getApprovalRequired().toString());
                    }
                })
                .build();
        return exchange.mutate().request(scopedRequest).build();
    }

    /**
     * 设置透传 Header。
     *
     * <p>不透传空值可以避免业务服务误以为权限中心显式返回了空范围。
     */
    private void setHeaderIfPresent(HttpHeaders headers, String headerName, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(headerName, value.trim());
        }
    }

    /**
     * 透传权限中心已经物化的项目授权集合。
     *
     * <p>这里刻意把 List<Long> 转成逗号分隔 Header，而不是让下游服务重新调用 permission-admin。
     * 原因有三点：
     * 1. gateway 已经处在授权判定链路上，多一次远程查询会增加每个业务服务的耦合和延迟；
     * 2. 下游业务模块只需要知道“本次请求允许哪些项目”，不需要理解权限中心的数据结构；
     * 3. Header 快照可以进入日志、审计和排障链路，便于解释某次 PROJECT 范围查询为什么命中了这些项目。
     */
    private void setAuthorizedProjectIds(HttpHeaders headers, List<Long> authorizedProjectIds) {
        if (authorizedProjectIds == null || authorizedProjectIds.isEmpty()) {
            return;
        }
        String value = PlatformAuthorizedProjectHeaderSupport.format(authorizedProjectIds);
        if (!value.isBlank()) {
            headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, value);
        }
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
                                           String traceId,
                                           String decisionSource) {
        if (authorizationProperties.isFailOpenOnError()) {
            authorizationMetrics.recordDecisionOutcome(decisionSource, "ERROR_FAIL_OPEN");
            log.warn("权限中心调用异常，按 fail-open 策略继续放行，traceId={}, role={}, path={}, error={}",
                    traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), error.getMessage());
            return chain.filter(exchange);
        }

        authorizationMetrics.recordDecisionOutcome(decisionSource, "ERROR_FAIL_CLOSED");
        log.error("权限中心调用异常，按 fail-closed 策略拒绝访问，traceId={}, role={}, path={}",
                traceId, decisionRequest.getActorRole(), decisionRequest.getRequestPath(), error);
        return authorizationErrorWriter.writeForbidden(exchange.getResponse(), traceId, "权限中心暂时不可用，网关已拒绝本次访问");
    }

    /**
     * 构造权限判定请求。
     *
     * <p>当前从 X-DataSmart-* Header 读取租户、操作者和角色。
     * OIDC 接入后，这些 Header 通常由 GatewayOidcAuthenticationContextFilter 根据已验证 JWT 写入；
     * 本地开发模式下也可能由受控开发身份过滤器写入。缺失角色时仍会使用配置里的 defaultActorRole，
     * 但这只是迁移期兜底，不应作为长期生产身份来源。
     *
     * <p>除了租户、角色、路径和动作，本方法还会补齐 actorType、workspace、requestSource 和服务账号委托上下文。
     * 这些字段看似不直接影响当前路由策略匹配，但它们决定审计责任链和未来更细粒度策略：
     * 1. SERVICE_ACCOUNT 不是超级管理员，必须说明机器主体是谁；
     * 2. Agent/tool/data-sync worker 代表用户执行时，需要说明 representedActor；
     * 3. workspace 是 Agent 记忆、工具和数据范围的重要隔离边界；
     * 4. requestSource 可用于区分 Web UI、OpenAPI、调度器和 Agent 工具调用。
     */
    private GatewayPermissionDecisionRequest buildDecisionRequest(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String path = request.getPath().value();
        String method = request.getMethod().name();
        Long tenantId = parseLongOrDefault(headers.getFirst(PlatformContextHeaders.TENANT_ID), authorizationProperties.getDefaultTenantId());
        Long actorId = parseLongOrDefault(headers.getFirst(PlatformContextHeaders.ACTOR_ID), authorizationProperties.getAnonymousActorId());
        String actorRole = valueOrDefault(headers.getFirst(PlatformContextHeaders.ACTOR_ROLE), authorizationProperties.getDefaultActorRole());
        String actorType = normalizeOptionalContractValue(headers.getFirst(PlatformContextHeaders.ACTOR_TYPE));
        String workspaceId = trimToNull(headers.getFirst(PlatformContextHeaders.WORKSPACE_ID));
        String requestSource = normalizeOptionalContractValue(headers.getFirst(PlatformContextHeaders.REQUEST_SOURCE));
        GatewayAuthorizationMetadata authorizationMetadata = resolveAuthorizationMetadata(path, method);

        GatewayPermissionDecisionRequest decisionRequest = new GatewayPermissionDecisionRequest();
        decisionRequest.setTenantId(tenantId);
        decisionRequest.setActorId(actorId);
        decisionRequest.setActorRole(normalizeContractValue(actorRole));
        decisionRequest.setActorType(actorType);
        decisionRequest.setWorkspaceId(workspaceId);
        decisionRequest.setRequestSource(requestSource);
        decisionRequest.setHttpMethod(method);
        decisionRequest.setRequestPath(path);
        decisionRequest.setResourceType(authorizationMetadata.resourceType());
        decisionRequest.setAction(authorizationMetadata.action());
        decisionRequest.setRequestedPolicyVersion(trimToNull(headers.getFirst(PlatformContextHeaders.REQUESTED_POLICY_VERSION)));
        serviceAccountDelegationSupport.populate(headers, decisionRequest, actorId);
        return decisionRequest;
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
    private GatewayAuthorizationMetadata resolveAuthorizationMetadata(String path, String method) {
        if (authorizationProperties.getRouteMetadata() == null || authorizationProperties.getRouteMetadata().isEmpty()) {
            return new GatewayAuthorizationMetadata(inferResourceType(path), inferAction(method));
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
            return new GatewayAuthorizationMetadata(normalizeContractValue(resourceType), normalizeContractValue(action));
        }

        return new GatewayAuthorizationMetadata(inferResourceType(path), inferAction(method));
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
        if (path.startsWith("/api/task/task-drafts/")) {
            return "TASK_DRAFT";
        }
        if (path.startsWith("/api/task/")) {
            return "TASK";
        }
        if (path.startsWith("/api/quality/")) {
            return "QUALITY_RULE";
        }
        if (path.startsWith("/api/identity/")) {
            return "IDENTITY_USER";
        }
        if (path.startsWith("/api/permission/")) {
            return "SYSTEM_SETTING";
        }
        if (path.startsWith("/api/observability/")) {
            return "AUDIT_LOG";
        }
        if (path.startsWith("/api/agent/")) {
            return "AI_RUNTIME";
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
        try {
            return PATH_PATTERN_PARSER.parse(pattern).matches(PathContainer.parsePath(path));
        } catch (IllegalArgumentException ignored) {
            /*
             * 兼容保护：如果某条配置暂时不是合法 PathPattern，继续使用旧版匹配能力。
             * 这样可以避免配置灰度期间因为一条坏规则导致网关授权过滤器整体异常。
             */
        }
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
     * 规范化可选契约编码。
     *
     * <p>actorType、requestSource、delegationType 等字段通常是枚举型编码，转成大写下划线有利于权限审计、
     * 缓存键和后续策略匹配保持一致。空值保持 null，不制造伪默认值。</p>
     */
    private String normalizeOptionalContractValue(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : normalizeContractValue(trimmed);
    }

    /**
     * 字符串为空时使用默认值。
     */
    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 去除空白并把空字符串归一化为 null。
     */
    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    /**
     * 排在 GatewayContractFilter 之后执行。
     */
    @Override
    public int getOrder() {
        return -90;
    }
}
