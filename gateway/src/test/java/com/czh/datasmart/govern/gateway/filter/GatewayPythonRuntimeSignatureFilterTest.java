/**
 * @Author : Cui
 * @Date: 2026/06/02 00:00
 * @Description DataSmart Govern Backend - GatewayPythonRuntimeSignatureFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gateway 到 Python Runtime 的内部签名过滤器测试。
 *
 * <p>这些用例保护的是智能网关信任链，而不是普通 Header 写入：
 * 1. 非 Python Runtime 规划路径不能被误加签名，避免影响其他微服务；
 * 2. 命中路径时必须先清理调用方伪造的旧签名 Header，即使签名开关关闭也不能把伪造签名透传给下游；
 * 3. 开启签名后必须写入版本、时间戳、nonce、keyId 与 HMAC 签名；
 * 4. 开启签名但未配置密钥时必须失败，避免生产环境“以为启用了安全，实际仍裸奔”；
 * 5. 签名原文必须稳定，方便 Python Runtime 用完全相同规则验签。
 */
class GatewayPythonRuntimeSignatureFilterTest {

    /**
     * 非目标路径应直接绕过签名逻辑。
     */
    @Test
    void nonTargetPathShouldBypassSignatureFilter() {
        GatewayPythonRuntimeSignatureFilter filter = filter(properties(false, ""));
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/models/routes"), chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(PlatformContextHeaders.GATEWAY_SIGNATURE)).isFalse();
    }

    /**
     * 签名关闭时仍要清理调用方伪造的签名 Header。
     *
     * <p>这条规则很关键：本地开发可以关闭签名，但不能因此把终端随手带来的
     * `X-DataSmart-Gateway-Signature` 透传给 Python Runtime，否则 Python 端未来一旦开启“看到签名就校验”，
     * 就可能出现难以排查的伪造残留。
     */
    @Test
    void disabledSigningShouldClearForgedSignatureHeaders() {
        GatewayPythonRuntimeSignatureFilter filter = filter(properties(false, ""));
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/plans")
                .mutate()
                .request(MockServerHttpRequest.post("/api/agent/plans")
                        .header(PlatformContextHeaders.GATEWAY_SIGNATURE, "forged")
                        .header(PlatformContextHeaders.GATEWAY_SIGNATURE_VERSION, "v1")
                        .build())
                .build(), chain).block();

        HttpHeaders headers = chain.exchange().getRequest().getHeaders();
        assertThat(headers.containsKey(PlatformContextHeaders.GATEWAY_SIGNATURE)).isFalse();
        assertThat(headers.containsKey(PlatformContextHeaders.GATEWAY_SIGNATURE_VERSION)).isFalse();
    }

    /**
     * 开启签名后应写入 Python Runtime 可校验的签名 Header。
     */
    @Test
    void enabledSigningShouldWriteSignatureHeaders() {
        GatewayContextProperties properties = properties(true, "secret-for-test");
        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC);
        GatewayPythonRuntimeSignatureFilter filter = new GatewayPythonRuntimeSignatureFilter(properties, fixedClock);
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain();

        filter.filter(exchange("/api/agent/plans"), chain).block();

        HttpHeaders headers = chain.exchange().getRequest().getHeaders();
        assertThat(headers.getFirst(PlatformContextHeaders.GATEWAY_SIGNATURE_VERSION)).isEqualTo("v1");
        assertThat(headers.getFirst(PlatformContextHeaders.GATEWAY_SIGNATURE_TIMESTAMP)).isEqualTo("1800000000000");
        assertThat(headers.getFirst(PlatformContextHeaders.GATEWAY_SIGNATURE_NONCE)).isNotBlank();
        assertThat(headers.getFirst(PlatformContextHeaders.GATEWAY_SIGNATURE_KEY_ID)).isEqualTo("gateway-local-v1");
        assertThat(headers.getFirst(PlatformContextHeaders.GATEWAY_SIGNATURE)).isNotBlank();
    }

    /**
     * 开启签名但密钥为空应失败关闭。
     */
    @Test
    void enabledSigningWithoutSecretShouldFailClosed() {
        GatewayPythonRuntimeSignatureFilter filter = filter(properties(true, ""));

        assertThatThrownBy(() -> filter.filter(exchange("/api/agent/plans"), new RecordingGatewayFilterChain()).block())
                .hasMessageContaining("Python Runtime 内部签名密钥未配置");
    }

    /**
     * 签名原文格式必须稳定。
     *
     * <p>该测试相当于 Java/Python 跨语言协议的锚点：如果未来误改 Header 顺序、字段名或空值处理，
     * 这里会先失败，提醒我们同步升级 Python Runtime 的验签规则。
     */
    @Test
    void canonicalPayloadShouldBeStable() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "datasmart-govern-gateway");
        headers.set(PlatformContextHeaders.TRACE_ID, "trace-001");
        headers.set(PlatformContextHeaders.TENANT_ID, "10");
        headers.set(PlatformContextHeaders.ACTOR_ID, "1001");

        String payload = GatewayPythonRuntimeSignatureFilter.canonicalPayload(
                headers,
                "1800000000000",
                "nonce-001",
                "gateway-local-v1"
        );

        assertThat(payload).startsWith("""
                v1
                X-DataSmart-Source-Service:datasmart-govern-gateway
                X-Gateway-Original-Path:
                X-Gateway-Route-Prefix:
                X-DataSmart-Trace-Id:trace-001
                X-DataSmart-Tenant-Id:10
                X-DataSmart-Actor-Id:1001""");
        assertThat(payload).contains(PlatformContextHeaders.TOOL_POLICY_ENVELOPE + ":");
        assertThat(GatewayPythonRuntimeSignatureFilter.sign(
                headers,
                "1800000000000",
                "nonce-001",
                "gateway-local-v1",
                "secret-for-test"
        )).isEqualTo("B7r5irPoHuecSPLsxR4TtOtrfbL4rllxtrsZR4cVJZQ");
    }

    /**
     * 构造签名配置。
     */
    private GatewayContextProperties properties(boolean enabled, String secret) {
        GatewayContextProperties properties = new GatewayContextProperties();
        properties.getPythonRuntimeSignature().setEnabled(enabled);
        properties.getPythonRuntimeSignature().setSecret(secret);
        properties.getPythonRuntimeSignature().setKeyId("gateway-local-v1");
        return properties;
    }

    /**
     * 构造被测过滤器。
     */
    private GatewayPythonRuntimeSignatureFilter filter(GatewayContextProperties properties) {
        return new GatewayPythonRuntimeSignatureFilter(properties);
    }

    /**
     * 构造带平台上下文的 mock 请求。
     */
    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .header(PlatformContextHeaders.SOURCE_SERVICE, "datasmart-govern-gateway")
                .header("X-Gateway-Original-Path", path)
                .header("X-Gateway-Route-Prefix", "/api/agent")
                .header(PlatformContextHeaders.TRACE_ID, "trace-001")
                .header(PlatformContextHeaders.TENANT_ID, "10")
                .header(PlatformContextHeaders.ACTOR_ID, "1001")
                .header(PlatformContextHeaders.ACTOR_ROLE, "PROJECT_OWNER")
                .header(PlatformContextHeaders.ACTOR_TYPE, "USER")
                .header(PlatformContextHeaders.WORKSPACE_ID, "workspace-a")
                .header(PlatformContextHeaders.REQUEST_SOURCE, "WEB_UI")
                .header(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT")
                .header(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "20,30")
                .build());
    }

    /**
     * 记录过滤器是否进入下游链路，以及进入下游时携带的 exchange。
     */
    private static class RecordingGatewayFilterChain implements GatewayFilterChain {

        private final AtomicBoolean called = new AtomicBoolean(false);
        private final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called.set(true);
            this.exchange.set(exchange);
            return Mono.empty();
        }

        private boolean called() {
            return called.get();
        }

        private ServerWebExchange exchange() {
            return exchange.get();
        }
    }
}
