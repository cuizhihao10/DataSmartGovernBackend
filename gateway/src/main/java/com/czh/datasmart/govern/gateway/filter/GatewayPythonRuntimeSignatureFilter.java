/**
 * @Author : Cui
 * @Date: 2026/06/02 00:00
 * @Description DataSmart Govern Backend - GatewayPythonRuntimeSignatureFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * gateway -> Python AI Runtime 可信上下文签名过滤器。
 *
 * <p>这个过滤器解决一个很容易被忽略的安全问题：
 * gateway 虽然已经清理并重建 `X-DataSmart-Tenant-Id`、`X-DataSmart-Actor-Role`、数据范围等 Header，
 * 但是 Python Runtime 如果只检查 `X-DataSmart-Source-Service=datasmart-govern-gateway`，
 * 终端仍然可能绕过 gateway 直连 Python Runtime，并伪造同名 Header。
 *
 * <p>当前实现使用 HMAC-SHA256 对“可信控制面 Header 快照”签名。Python Runtime 只有在签名验证通过时，
 * 才会把这些 Header 重建成 `trustedControlPlane`。这样可以把“来源自报”升级为“来源可验证”。
 *
 * <p>执行顺序为什么是 -80：
 * 1. `GatewayContractFilter(-100)` 先清理外部 Header 并写入 traceId/sourceService；
 * 2. `GatewayDevelopmentIdentityFilter(-95)` 或未来 JWT 过滤器写入租户、操作者、角色、工作区；
 * 3. `GatewayAuthorizationFilter(-90)` 调用 permission-admin，并写入数据范围和审批 Header；
 * 4. 本过滤器最后对完整快照签名，再交给 Spring Cloud Gateway 路由过滤器转发。
 *
 * <p>当前边界：
 * - 签名保护的是可信 Header 快照，不读取 request body，避免在 reactive gateway 中引入 body 缓存和背压风险；
 * - 请求体中的 `trustedControlPlane` 会由 Python API 边界无条件删除，无法绕过 Header 签名；
 * - 完整链路机密性与 body 防篡改仍应由 HTTPS/mTLS、服务网格或 API Gateway 内网链路承担；
 * - nonce 当前只绑定进签名，后续可在 Python Runtime 或统一 Redis 中增加短 TTL 去重，抵御窗口内重放。
 */
@Slf4j
@Component
public class GatewayPythonRuntimeSignatureFilter implements GlobalFilter, Ordered {

    /**
     * 当前签名协议版本。
     *
     * <p>Java gateway 与 Python Runtime 必须使用同一版本拼接规则。
     */
    static final String SIGNATURE_VERSION = "v1";

    /**
     * HMAC 算法名称。
     */
    private static final String HMAC_SHA_256 = "HmacSHA256";

    /**
     * 签名原文中包含的可信 Header 顺序。
     *
     * <p>顺序是协议的一部分，不能随意调整。新增字段时应升级协议版本或保证 Java/Python 同步发布。
     * 这里同时纳入原始路径和 gateway 路由前缀，让签名不能被复制到另一条路由使用。
     */
    static final List<String> SIGNED_HEADERS = List.of(
            PlatformContextHeaders.SOURCE_SERVICE,
            "X-Gateway-Original-Path",
            "X-Gateway-Route-Prefix",
            PlatformContextHeaders.TRACE_ID,
            PlatformContextHeaders.TENANT_ID,
            PlatformContextHeaders.ACTOR_ID,
            PlatformContextHeaders.ACTOR_ROLE,
            PlatformContextHeaders.ACTOR_TYPE,
            PlatformContextHeaders.WORKSPACE_ID,
            PlatformContextHeaders.REQUEST_SOURCE,
            PlatformContextHeaders.TENANT_PLAN_CODE,
            PlatformContextHeaders.WORKSPACE_RISK_LEVEL,
            PlatformContextHeaders.TOOL_BUDGET_POLICY_VERSION,
            PlatformContextHeaders.TOOL_POLICY_ENVELOPE,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_VERSION,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_KEY,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_TTL_SECONDS,
            PlatformContextHeaders.SKILL_VISIBILITY_CACHE_SCOPE,
            PlatformContextHeaders.DATA_SCOPE_LEVEL,
            PlatformContextHeaders.DATA_SCOPE_EXPRESSION,
            PlatformContextHeaders.AUTHORIZED_PROJECT_IDS,
            PlatformContextHeaders.APPROVAL_REQUIRED
    );

    private final GatewayContextProperties contextProperties;
    private final Clock clock;

    public GatewayPythonRuntimeSignatureFilter(GatewayContextProperties contextProperties) {
        this(contextProperties, Clock.systemUTC());
    }

    /**
     * 允许测试注入固定时钟。
     *
     * <p>生产运行使用系统 UTC 时钟；测试使用固定 Clock 后，可以稳定验证 timestamp 和签名原文，
     * 避免依赖真实时间导致用例偶发失败。
     */
    GatewayPythonRuntimeSignatureFilter(GatewayContextProperties contextProperties, Clock clock) {
        this.contextProperties = contextProperties;
        this.clock = clock;
    }

    /**
     * 为命中的 Python Runtime 请求生成内部签名。
     *
     * <p>无论签名开关是否开启，只要命中目标路径，都会先删除调用方可能伪造的旧签名 Header。
     * 这可以避免“gateway 没有开启签名，但终端自己塞入一组签名 Header”被 Python Runtime 误用。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        GatewayContextProperties.PythonRuntimeSignature properties = contextProperties.getPythonRuntimeSignature();
        if (!isTargetPath(request, properties)) {
            return chain.filter(exchange);
        }

        ServerHttpRequest sanitizedRequest = request.mutate()
                .headers(GatewayPythonRuntimeSignatureFilter::clearSignatureHeaders)
                .build();

        if (!properties.isEnabled()) {
            return chain.filter(exchange.mutate().request(sanitizedRequest).build());
        }
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            log.error("Python Runtime 内部签名已开启，但共享密钥为空，已拒绝生成签名。path={}, traceId={}",
                    request.getPath().value(), request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID));
            return Mono.error(new IllegalStateException("Python Runtime 内部签名密钥未配置"));
        }

        String timestamp = String.valueOf(clock.millis());
        String nonce = UUID.randomUUID().toString();
        String keyId = normalize(properties.getKeyId());
        String signature = sign(
                sanitizedRequest.getHeaders(),
                timestamp,
                nonce,
                keyId,
                properties.getSecret()
        );

        ServerHttpRequest signedRequest = sanitizedRequest.mutate()
                .headers(headers -> {
                    headers.set(PlatformContextHeaders.GATEWAY_SIGNATURE_VERSION, SIGNATURE_VERSION);
                    headers.set(PlatformContextHeaders.GATEWAY_SIGNATURE_TIMESTAMP, timestamp);
                    headers.set(PlatformContextHeaders.GATEWAY_SIGNATURE_NONCE, nonce);
                    headers.set(PlatformContextHeaders.GATEWAY_SIGNATURE_KEY_ID, keyId);
                    headers.set(PlatformContextHeaders.GATEWAY_SIGNATURE, signature);
                })
                .build();
        return chain.filter(exchange.mutate().request(signedRequest).build());
    }

    /**
     * 计算 URL-safe Base64 HMAC-SHA256 签名。
     *
     * <p>URL-safe 且无 padding 的编码在 HTTP Header 中更稳妥，避免 `+`、`/`、`=` 被代理层错误处理。
     */
    static String sign(HttpHeaders headers,
                       String timestamp,
                       String nonce,
                       String keyId,
                       String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            byte[] digest = mac.doFinal(canonicalPayload(headers, timestamp, nonce, keyId)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 Python Runtime 内部签名", exception);
        }
    }

    /**
     * 构造 Java/Python 两端共享的签名原文。
     *
     * <p>格式保持朴素的逐行 `Header-Name:value`，便于学习、跨语言实现和故障排查。
     * 空 Header 仍然写入空值，避免两端因为“字段缺失时是否跳过”产生不同理解。
     */
    static String canonicalPayload(HttpHeaders headers,
                                   String timestamp,
                                   String nonce,
                                   String keyId) {
        StringBuilder builder = new StringBuilder(SIGNATURE_VERSION);
        SIGNED_HEADERS.forEach(headerName -> appendLine(builder, headerName, headers.getFirst(headerName)));
        appendLine(builder, PlatformContextHeaders.GATEWAY_SIGNATURE_TIMESTAMP, timestamp);
        appendLine(builder, PlatformContextHeaders.GATEWAY_SIGNATURE_NONCE, nonce);
        appendLine(builder, PlatformContextHeaders.GATEWAY_SIGNATURE_KEY_ID, keyId);
        return builder.toString();
    }

    private static void appendLine(StringBuilder builder, String headerName, String value) {
        builder.append('\n').append(headerName).append(':').append(normalize(value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isTargetPath(ServerHttpRequest request,
                                 GatewayContextProperties.PythonRuntimeSignature properties) {
        return properties != null
                && properties.getTargetPaths() != null
                && properties.getTargetPaths().contains(request.getPath().value());
    }

    private static void clearSignatureHeaders(HttpHeaders headers) {
        headers.remove(PlatformContextHeaders.GATEWAY_SIGNATURE_VERSION);
        headers.remove(PlatformContextHeaders.GATEWAY_SIGNATURE_TIMESTAMP);
        headers.remove(PlatformContextHeaders.GATEWAY_SIGNATURE_NONCE);
        headers.remove(PlatformContextHeaders.GATEWAY_SIGNATURE_KEY_ID);
        headers.remove(PlatformContextHeaders.GATEWAY_SIGNATURE);
    }

    @Override
    public int getOrder() {
        return -80;
    }
}
