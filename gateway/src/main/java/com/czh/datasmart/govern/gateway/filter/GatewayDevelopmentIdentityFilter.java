/**
 * @Author : Cui
 * @Date: 2026/04/25 23:41
 * @Description DataSmart Govern Backend - GatewayDevelopmentIdentityFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformActorType;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.gateway.config.GatewayDevelopmentIdentityProperties;
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

/**
 * 网关开发期身份注入过滤器。
 *
 * <p>它位于 GatewayContractFilter 之后、GatewayAuthorizationFilter 之前：
 * 1. GatewayContractFilter 先清理所有外部伪造的 X-DataSmart-* 身份 Header；
 * 2. 本过滤器再把“经过开发期解析规则确认的身份令牌”转换成内部上下文 Header；
 * 3. GatewayAuthorizationFilter 最后读取这些 Header，调用 permission-admin 做路由级授权。
 *
 * <p>这个顺序非常关键。如果身份注入发生在清理之前，刚写入的 Header 会被清掉；
 * 如果身份注入发生在授权之后，permission-admin 仍然只能看到默认匿名身份。
 *
 * <p>商业化演进方向：
 * 当前类只服务本地和测试联调，令牌格式简单、没有签名，不具备生产安全性。
 * 后续接入真实认证时，可以新增 JWT/IdP 过滤器并保持相同输出：写入 X-DataSmart-Tenant-Id、
 * X-DataSmart-Actor-Id、X-DataSmart-Actor-Role、X-DataSmart-Actor-Type、X-DataSmart-Workspace-Id。
 * 这样 permission-admin、task-management、datasource-management 等下游模块不需要关心身份最初来自哪里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayDevelopmentIdentityFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayDevelopmentIdentityProperties identityProperties;
    private final ObjectMapper objectMapper;

    /**
     * 解析开发期身份并写入平台内部上下文。
     *
     * <p>当 enabled=false 时，本过滤器完全透明，不改变请求。这保证了默认启动行为仍然安全、可预期。
     * 当 enabled=true 且请求携带开发令牌时，过滤器会解析租户、操作者、角色、操作者类型和工作区，
     * 并通过 request.mutate().headers(...) 写回到当前 ServerWebExchange。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!identityProperties.isEnabled()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String rawIdentityToken = resolveRawIdentityToken(request);
        if (rawIdentityToken == null || rawIdentityToken.isBlank()) {
            return chain.filter(exchange);
        }

        IdentityContext identityContext = parseIdentityContext(rawIdentityToken.trim());
        if (!identityContext.valid()) {
            if (identityProperties.isRejectMalformedIdentity()) {
                return writeUnauthorized(exchange.getResponse(),
                        request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID),
                        identityContext.errorMessage());
            }
            log.warn("开发期身份令牌解析失败，已按宽松策略忽略身份注入，traceId={}, error={}",
                    request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID), identityContext.errorMessage());
            return chain.filter(exchange);
        }

        ServerHttpRequest mutatedRequest = request.mutate()
                .headers(headers -> writeIdentityHeaders(headers, identityContext))
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 从请求中读取开发期身份令牌。
     *
     * <p>优先读取专用 Header，是为了让联调脚本可以在不干扰真实 Authorization 头的情况下模拟身份。
     * 如果专用 Header 不存在，再读取 Authorization: Bearer dev:...。
     * 读取 Authorization 时必须检查 dev: 前缀，避免误解析真实 JWT。
     */
    private String resolveRawIdentityToken(ServerHttpRequest request) {
        String identityHeaderValue = request.getHeaders().getFirst(identityProperties.getIdentityHeader());
        if (identityHeaderValue != null && !identityHeaderValue.isBlank()) {
            return identityHeaderValue;
        }

        if (!identityProperties.isAllowAuthorizationBearerToken()) {
            return null;
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String bearerToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!bearerToken.startsWith(identityProperties.getTokenPrefix())) {
            return null;
        }
        return bearerToken;
    }

    /**
     * 将开发令牌解析成平台身份上下文。
     *
     * <p>令牌格式为：
     * dev:{tenantId}:{actorId}:{actorRole}[:actorType][:workspaceId]
     *
     * <p>这里没有使用复杂 JSON，是因为它只服务本地调试，冒号分隔能让 curl 非常轻量。
     * 但解析规则仍然做了必要校验：租户和操作者必须是数字，角色必须在允许列表中，
     * actorType 必须属于平台定义的 PlatformActorType。
     */
    private IdentityContext parseIdentityContext(String rawIdentityToken) {
        String tokenPrefix = identityProperties.getTokenPrefix();
        if (!rawIdentityToken.startsWith(tokenPrefix)) {
            return IdentityContext.invalid("开发期身份令牌必须以 " + tokenPrefix + " 开头");
        }

        String payload = rawIdentityToken.substring(tokenPrefix.length());
        String[] parts = payload.split(":", -1);
        if (parts.length < 3 || parts.length > 5) {
            return IdentityContext.invalid("开发期身份令牌格式应为 dev:{tenantId}:{actorId}:{actorRole}[:actorType][:workspaceId]");
        }

        Long tenantId = parseLong(parts[0], "tenantId");
        if (tenantId == null) {
            return IdentityContext.invalid("tenantId 必须是数字");
        }

        Long actorId = parseLong(parts[1], "actorId");
        if (actorId == null) {
            return IdentityContext.invalid("actorId 必须是数字");
        }

        String actorRole = normalizeRole(parts[2]);
        if (actorRole == null) {
            return IdentityContext.invalid("actorRole 不能为空");
        }
        if (!identityProperties.getAllowedRoles().contains(actorRole)) {
            return IdentityContext.invalid("actorRole 不在开发期允许模拟的角色列表中: " + actorRole);
        }

        String actorType = parts.length >= 4 && !parts[3].isBlank()
                ? parts[3].trim().toUpperCase(Locale.ROOT)
                : identityProperties.getDefaultActorType();
        if (!isSupportedActorType(actorType)) {
            return IdentityContext.invalid("actorType 不属于平台支持的操作者类型: " + actorType);
        }

        String workspaceId = parts.length == 5 && !parts[4].isBlank()
                ? parts[4].trim()
                : identityProperties.getDefaultWorkspaceId();

        return IdentityContext.valid(tenantId, actorId, actorRole, actorType, workspaceId);
    }

    /**
     * 写入下游服务消费的平台上下文 Header。
     *
     * <p>这里使用 set 而不是 add，是为了确保一个 Header 只有一个可信值。
     * 多值 Header 在安全领域很危险，因为不同框架可能读取第一个值或最后一个值，导致审计和授权结果不一致。
     */
    private void writeIdentityHeaders(HttpHeaders headers, IdentityContext identityContext) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(identityContext.tenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID, String.valueOf(identityContext.actorId()));
        headers.set(PlatformContextHeaders.ACTOR_ROLE, identityContext.actorRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, identityContext.actorType());
        headers.set(PlatformContextHeaders.WORKSPACE_ID, identityContext.workspaceId());
    }

    /**
     * 角色编码规范化。
     *
     * <p>平台角色统一使用大写下划线形式，例如 PLATFORM_ADMINISTRATOR。
     * 这让数据库策略、前端菜单、网关联调令牌和审计日志能使用同一套稳定编码。
     */
    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        return role.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 安全解析 Long。
     *
     * <p>租户 ID 和操作者 ID 最终会进入权限审计记录。格式错误时不应悄悄变成 0，
     * 否则排查时会误以为请求来自匿名用户，而不是令牌写错。
     */
    private Long parseLong(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            log.debug("开发期身份字段解析失败，field={}, value={}", fieldName, value);
            return null;
        }
    }

    /**
     * 校验 actorType 是否属于平台公共契约。
     */
    private boolean isSupportedActorType(String actorType) {
        try {
            PlatformActorType.valueOf(actorType);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 写出 401 响应。
     *
     * <p>格式错误的开发身份属于认证阶段问题，而不是授权阶段问题：
     * 认证回答“你是谁”，授权回答“你能不能做这件事”。令牌无法解析时，网关还没有可信身份，
     * 因此返回 UNAUTHORIZED 更符合语义。
     */
    private Mono<Void> writeUnauthorized(ServerHttpResponse response, String traceId, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        PlatformApiResponse<Void> body = PlatformApiResponse.error(PlatformErrorCode.UNAUTHORIZED, message, traceId);
        byte[] bytes = serialize(body);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 序列化响应体。
     *
     * <p>网关过滤器运行在响应链路非常靠前的位置，不能把序列化异常继续抛给业务模块处理。
     * 如果 ObjectMapper 极端情况下失败，就返回一个最小 JSON，保证客户端仍能收到结构化错误。
     */
    private byte[] serialize(PlatformApiResponse<Void> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            return "{\"code\":20001,\"reason\":\"UNAUTHORIZED\",\"message\":\"unauthorized\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 当前过滤器必须排在上下文清理之后、权限判定之前。
     */
    @Override
    public int getOrder() {
        return -95;
    }

    /**
     * 开发期身份上下文解析结果。
     *
     * <p>使用 record 可以让解析结果保持不可变，避免在响应式过滤链中被后续逻辑意外修改。
     */
    private record IdentityContext(boolean valid,
                                   String errorMessage,
                                   Long tenantId,
                                   Long actorId,
                                   String actorRole,
                                   String actorType,
                                   String workspaceId) {

        private static IdentityContext valid(Long tenantId,
                                             Long actorId,
                                             String actorRole,
                                             String actorType,
                                             String workspaceId) {
            return new IdentityContext(true, null, tenantId, actorId, actorRole, actorType, workspaceId);
        }

        private static IdentityContext invalid(String errorMessage) {
            return new IdentityContext(false, errorMessage, null, null, null, null, null);
        }
    }
}
