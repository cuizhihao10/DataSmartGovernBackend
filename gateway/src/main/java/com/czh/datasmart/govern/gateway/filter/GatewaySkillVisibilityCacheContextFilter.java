/**
 * @Author : Cui
 * @Date: 2026/06/05 18:40
 * @Description DataSmart Govern Backend - GatewaySkillVisibilityCacheContextFilter.java
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 规划入口的 Skill 可见性缓存上下文过滤器。
 *
 * <p>该过滤器服务的是智能网关性能优化，而不是普通 HTTP 响应缓存。
 * `/api/agent/plans` 每次都会做 Skill 语义匹配、权限准入、工具预算和 workspace 隔离治理。
 * 其中“语义匹配”必须按用户 objective 每次计算，但“同一租户、同一角色、同一 workspace、同一数据范围、
 * 同一预算策略下哪些 Skill 具备准入资格”可以短时间复用。</p>
 *
 * <p>为什么缓存上下文由 gateway 生成：
 * 1. gateway 已经完成 Header 清理、身份注入和 permission-admin 路由授权，是控制面事实最可信的入口；
 * 2. Python Runtime 不应该相信终端自报的缓存 key，否则调用方可以诱导它复用别人的 READY Skill 结果；
 * 3. 本过滤器排在签名过滤器之前，生成的缓存 Header 会被 HMAC 签名保护，Python 只有验签通过才会使用。</p>
 *
 * <p>当前边界：
 * - 不读取 request body，避免在 Spring Cloud Gateway reactive 链路中缓存大请求体；
 * - 因此 projectId、sessionId、Manifest 指纹会由 Python Runtime 在最终 key 中继续拼接；
 * - gateway 生成的是“控制面摘要 key”，不是最终缓存 key；
 * - 后续可以把租户套餐、workspace 风险和预算策略版本从 permission-admin 真值回填，而不是使用配置默认值。</p>
 */
@Component
public class GatewaySkillVisibilityCacheContextFilter implements GlobalFilter, Ordered {

    private static final String KEY_ALGORITHM = "SHA-256";

    private final GatewayContextProperties contextProperties;

    public GatewaySkillVisibilityCacheContextFilter(GatewayContextProperties contextProperties) {
        this.contextProperties = contextProperties;
    }

    /**
     * 为 Agent 规划请求写入可签名的 Skill 可见性缓存上下文。
     *
     * <p>命中目标路径时会先清理调用方可能伪造的缓存 Header。
     * 即使配置关闭，也会执行清理，避免终端伪造 `X-DataSmart-Skill-Visibility-Cache-Key`
     * 后被 Python Runtime 误当成可信上下文。</p>
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        GatewayContextProperties.SkillVisibilityCache properties = contextProperties.getSkillVisibilityCache();
        if (!isTargetPath(request, properties)) {
            return chain.filter(exchange);
        }

        ServerHttpRequest.Builder builder = request.mutate()
                .headers(headers -> {
                    clearSkillVisibilityCacheHeaders(headers);
                    if (properties.isEnabled()) {
                        writeCacheContextHeaders(headers, properties);
                    }
                });
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    /**
     * 写入 Skill 可见性缓存上下文 Header。
     *
     * <p>租户套餐、workspace 风险和工具预算策略版本也在这里写入。
     * 当前它们来自 gateway 配置默认值；后续如果 permission-admin 在授权结果中返回真实值，可以把这些字段
     * 从配置默认升级为远端控制面事实，而 Python 侧缓存 key 不需要再改变结构。</p>
     */
    private void writeCacheContextHeaders(HttpHeaders headers,
                                          GatewayContextProperties.SkillVisibilityCache properties) {
        String version = normalize(properties.getVersion(), "v1");
        String tenantPlanCode = normalize(properties.getDefaultTenantPlanCode(), "STANDARD");
        String workspaceRiskLevel = normalize(properties.getDefaultWorkspaceRiskLevel(), "NORMAL");
        String toolBudgetPolicyVersion = normalize(properties.getDefaultToolBudgetPolicyVersion(), "gateway-default-v1");
        String scope = normalize(properties.getScope(), "session-ready-skill-admission");
        String ttlSeconds = String.valueOf(Math.max(1, properties.getTtlSeconds()));

        headers.set(PlatformContextHeaders.TENANT_PLAN_CODE, tenantPlanCode);
        headers.set(PlatformContextHeaders.WORKSPACE_RISK_LEVEL, workspaceRiskLevel);
        headers.set(PlatformContextHeaders.TOOL_BUDGET_POLICY_VERSION, toolBudgetPolicyVersion);
        headers.set(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_VERSION, version);
        headers.set(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_SCOPE, scope);
        headers.set(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_TTL_SECONDS, ttlSeconds);
        headers.set(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY, cacheKey(headers, version));
    }

    /**
     * 根据可信控制面 Header 生成稳定摘要 key。
     *
     * <p>key 原文只包含低敏控制面事实，不包含 traceId/requestId，也不包含用户目标或请求体。
     * traceId/requestId 每次请求都会变化，放入 key 会导致完全无法命中；用户目标和请求体则不能进入
     * gateway 级缓存上下文，避免把业务内容扩散到网关日志、Header 或缓存层。</p>
     */
    static String cacheKey(HttpHeaders headers, String version) {
        try {
            MessageDigest digest = MessageDigest.getInstance(KEY_ALGORITHM);
            byte[] bytes = digest.digest(canonicalPayload(headers, version).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 Skill 可见性缓存 key", exception);
        }
    }

    /**
     * 构造缓存 key 原文。
     *
     * <p>该方法保留包级可见性，便于单元测试固定协议。未来如果增加真实租户套餐、workspace 风险、
     * 策略版本或权限包摘要，也应先更新这里的测试，再同步 Python Runtime 的最终 key 解释。</p>
     */
    static String canonicalPayload(HttpHeaders headers, String version) {
        StringBuilder builder = new StringBuilder("skill-visibility-cache:").append(normalize(version, "v1"));
        append(builder, "tenantId", headers.getFirst(PlatformContextHeaders.TENANT_ID));
        append(builder, "actorId", headers.getFirst(PlatformContextHeaders.ACTOR_ID));
        append(builder, "actorRole", headers.getFirst(PlatformContextHeaders.ACTOR_ROLE));
        append(builder, "actorType", headers.getFirst(PlatformContextHeaders.ACTOR_TYPE));
        append(builder, "workspaceId", headers.getFirst(PlatformContextHeaders.WORKSPACE_ID));
        append(builder, "requestSource", headers.getFirst(PlatformContextHeaders.REQUEST_SOURCE));
        append(builder, "dataScopeLevel", headers.getFirst(PlatformContextHeaders.DATA_SCOPE_LEVEL));
        append(builder, "dataScopeExpressionHash", shortHash(headers.getFirst(PlatformContextHeaders.DATA_SCOPE_EXPRESSION)));
        append(builder, "authorizedProjectIds", normalizeAuthorizedProjectIds(
                headers.getFirst(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS)));
        append(builder, "approvalRequired", headers.getFirst(PlatformContextHeaders.APPROVAL_REQUIRED));
        append(builder, "tenantPlanCode", headers.getFirst(PlatformContextHeaders.TENANT_PLAN_CODE));
        append(builder, "workspaceRiskLevel", headers.getFirst(PlatformContextHeaders.WORKSPACE_RISK_LEVEL));
        append(builder, "toolBudgetPolicyVersion", headers.getFirst(PlatformContextHeaders.TOOL_BUDGET_POLICY_VERSION));
        return builder.toString();
    }

    private static void append(StringBuilder builder, String name, String value) {
        builder.append('\n').append(name).append('=').append(normalize(value, ""));
    }

    /**
     * 对数据范围表达式做短摘要。
     *
     * <p>表达式可能包含字段名或策略占位符，不宜直接进入 Header 派生 key 的可读原文。
     * 使用短摘要可以保留“表达式变化会导致 key 变化”的能力，同时减少日志和测试输出中的策略细节。</p>
     */
    private static String shortHash(String value) {
        String normalized = normalize(value, "");
        if (normalized.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(KEY_ALGORITHM);
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return encoded.substring(0, Math.min(16, encoded.length()));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成数据范围表达式摘要", exception);
        }
    }

    /**
     * 归一化授权项目集合。
     *
     * <p>permission-admin 返回的项目 ID 顺序不应影响缓存命中。
     * 例如 `20,30` 与 `30,20` 表达同一组授权项目，应该生成相同 key。</p>
     */
    private static String normalizeAuthorizedProjectIds(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> projectIds = new ArrayList<>();
        for (String item : value.split(",")) {
            String normalized = item.trim();
            if (!normalized.isBlank()) {
                projectIds.add(normalized);
            }
        }
        projectIds.sort(Comparator.naturalOrder());
        return String.join(",", projectIds);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean isTargetPath(ServerHttpRequest request,
                                 GatewayContextProperties.SkillVisibilityCache properties) {
        return properties != null
                && properties.getTargetPaths() != null
                && properties.getTargetPaths().contains(request.getPath().value());
    }

    private static void clearSkillVisibilityCacheHeaders(HttpHeaders headers) {
        headers.remove(PlatformContextHeaders.TENANT_PLAN_CODE);
        headers.remove(PlatformContextHeaders.WORKSPACE_RISK_LEVEL);
        headers.remove(PlatformContextHeaders.TOOL_BUDGET_POLICY_VERSION);
        headers.remove(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_VERSION);
        headers.remove(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY);
        headers.remove(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_TTL_SECONDS);
        headers.remove(PlatformContextHeaders.SKILL_VISIBILITY_CACHE_SCOPE);
    }

    /**
     * 执行顺序必须位于授权之后、Python Runtime 签名之前。
     *
     * <p>授权之后才能拿到 dataScope/authorizedProjectIds；签名之前写入才能让 Python Runtime 验证这些缓存
     * Header 确实由 gateway 生成，而不是终端伪造。</p>
     */
    @Override
    public int getOrder() {
        return -85;
    }
}
