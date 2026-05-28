/**
 * @Author : Cui
 * @Date: 2026/05/25 02:12
 * @Description DataSmart Govern Backend - GatewayAgentEventWebSocketGuardFilterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayAgentEventWebSocketProperties;
import com.czh.datasmart.govern.gateway.monitoring.GatewayAgentEventWebSocketMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 实时事件 WebSocket 网关守卫测试。
 *
 * <p>这些用例保护的不是普通 Controller 输入校验，而是“长连接入口的生产安全边界”：
 * 1. 只有真正的 WebSocket Upgrade 才能进入 Python Runtime 路由；
 * 2. 单个 gateway 实例不能被无限连接打满；
 * 3. 一个租户不能挤占所有租户的实时事件通道；
 * 4. 一个用户或服务账号不能因为多标签页、重连风暴或脚本 bug 无限创建连接；
 * 5. 正常连接结束后必须释放计数，否则系统运行一段时间后会错误地拒绝新连接。
 */
class GatewayAgentEventWebSocketGuardFilterTest {

    /**
     * 非 Agent 实时事件路径应直接绕过守卫。
     *
     * <p>gateway 中还有大量 `/api/agent/**` 普通 HTTP 控制面，例如模型路由、工具目录、会话创建。
     * WebSocket 配额不能误伤这些接口，否则会把普通 REST 请求错误解释成长连接资源。
     */
    @Test
    void nonAgentEventPathShouldBypassGuard() {
        GatewayAgentEventWebSocketGuardFilter filter = filter(defaultProperties());
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain(Mono.empty());
        MockServerWebExchange exchange = exchange("/api/agent/models/routes", false, "10", "1001");

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    /**
     * 普通 HTTP GET 误访问 WebSocket 入口时应返回 400。
     *
     * <p>WebSocket 握手虽然也是 GET，但必须带 `Connection: Upgrade` 与 `Upgrade: websocket`。
     * 这里提前拒绝可以把“调用方式错误”留在 gateway 层，而不是让请求进入下游后变成难读的协议错误。
     */
    @Test
    void nonWebSocketRequestToEventPathShouldReturnBadRequest() {
        GatewayAgentEventWebSocketGuardFilter filter = filter(defaultProperties());
        RecordingGatewayFilterChain chain = new RecordingGatewayFilterChain(Mono.empty());
        MockServerWebExchange exchange = exchange("/api/agent/events/ws", false, "10", "1001");

        filter.filter(exchange, chain).block();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(400);
    }

    /**
     * 合法 WebSocket Upgrade 请求应进入后续网关链路。
     *
     * <p>该测试同时验证连接结束后计数会被释放：我们把 actor 上限设为 1，连续执行两次短连接，
     * 如果第一次没有释放，第二次会被错误拒绝为 ACTOR_LIMIT。
     */
    @Test
    void validWebSocketRequestShouldPassAndReleaseCounterAfterChainCompletes() {
        GatewayAgentEventWebSocketProperties properties = defaultProperties();
        properties.setMaxConnectionsPerActor(1);
        GatewayAgentEventWebSocketGuardFilter filter = filter(properties);

        RecordingGatewayFilterChain firstChain = new RecordingGatewayFilterChain(Mono.empty());
        filter.filter(exchange("/api/agent/events/ws", true, "10", "1001"), firstChain).block();
        RecordingGatewayFilterChain secondChain = new RecordingGatewayFilterChain(Mono.empty());
        MockServerWebExchange secondExchange = exchange("/api/agent/events/ws", true, "10", "1001");
        filter.filter(secondExchange, secondChain).block();

        assertThat(firstChain.called()).isTrue();
        assertThat(secondChain.called()).isTrue();
        assertThat(secondExchange.getResponse().getStatusCode()).isNull();
    }

    /**
     * 单个用户超过连接上限时应返回 429。
     *
     * <p>这是最常见的商业化保护场景：用户刷新页面、开多个标签页或前端 SDK 重连逻辑异常时，
     * 不能让同一 actor 无限制占用实时事件连接。
     */
    @Test
    void actorConnectionLimitShouldReturnTooManyRequests() {
        GatewayAgentEventWebSocketProperties properties = defaultProperties();
        properties.setMaxConnectionsPerActor(1);
        GatewayAgentEventWebSocketGuardFilter filter = filter(properties);
        Disposable firstConnection = filter.filter(
                exchange("/api/agent/events/ws", true, "10", "1001"),
                new RecordingGatewayFilterChain(Mono.never())
        ).subscribe();

        MockServerWebExchange rejectedExchange = exchange("/api/agent/events/ws", true, "10", "1001");
        RecordingGatewayFilterChain rejectedChain = new RecordingGatewayFilterChain(Mono.empty());
        filter.filter(rejectedExchange, rejectedChain).block();
        firstConnection.dispose();

        assertThat(rejectedChain.called()).isFalse();
        assertThat(rejectedExchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(rejectedExchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    /**
     * 单个租户超过连接上限时应返回 429。
     *
     * <p>租户级配额保护的是多租户公平性：一个租户内可以有多个用户，但整体不能把共享 gateway 实例占满。
     */
    @Test
    void tenantConnectionLimitShouldReturnTooManyRequests() {
        GatewayAgentEventWebSocketProperties properties = defaultProperties();
        properties.setMaxConnectionsPerTenant(1);
        GatewayAgentEventWebSocketGuardFilter filter = filter(properties);
        Disposable firstConnection = filter.filter(
                exchange("/api/agent/events/ws", true, "10", "1001"),
                new RecordingGatewayFilterChain(Mono.never())
        ).subscribe();

        MockServerWebExchange rejectedExchange = exchange("/api/agent/events/ws", true, "10", "1002");
        RecordingGatewayFilterChain rejectedChain = new RecordingGatewayFilterChain(Mono.empty());
        filter.filter(rejectedExchange, rejectedChain).block();
        firstConnection.dispose();

        assertThat(rejectedChain.called()).isFalse();
        assertThat(rejectedExchange.getResponse().getStatusCode().value()).isEqualTo(429);
    }

    /**
     * gateway 实例全局连接超过上限时应返回 429。
     *
     * <p>全局配额是最后一道自我保护：当整体连接数过高时，即使来自不同租户和不同用户，也要先保护 gateway
     * 与 Python Runtime 不被长连接资源拖垮。
     */
    @Test
    void globalConnectionLimitShouldReturnTooManyRequests() {
        GatewayAgentEventWebSocketProperties properties = defaultProperties();
        properties.setMaxActiveConnections(1);
        GatewayAgentEventWebSocketGuardFilter filter = filter(properties);
        Disposable firstConnection = filter.filter(
                exchange("/api/agent/events/ws", true, "10", "1001"),
                new RecordingGatewayFilterChain(Mono.never())
        ).subscribe();

        MockServerWebExchange rejectedExchange = exchange("/api/agent/events/ws", true, "20", "2001");
        RecordingGatewayFilterChain rejectedChain = new RecordingGatewayFilterChain(Mono.empty());
        filter.filter(rejectedExchange, rejectedChain).block();
        firstConnection.dispose();

        assertThat(rejectedChain.called()).isFalse();
        assertThat(rejectedExchange.getResponse().getStatusCode().value()).isEqualTo(429);
    }

    /**
     * 构造默认治理配置。
     */
    private GatewayAgentEventWebSocketProperties defaultProperties() {
        return new GatewayAgentEventWebSocketProperties();
    }

    /**
     * 构造被测过滤器。
     */
    private GatewayAgentEventWebSocketGuardFilter filter(GatewayAgentEventWebSocketProperties properties) {
        return new GatewayAgentEventWebSocketGuardFilter(
                properties,
                new GatewayAgentEventWebSocketMetrics(new SimpleMeterRegistry()),
                new ObjectMapper()
        );
    }

    /**
     * 构造带平台上下文的 mock 请求。
     *
     * @param websocket 是否添加 WebSocket Upgrade 握手头。
     */
    private MockServerWebExchange exchange(String path, boolean websocket, String tenantId, String actorId) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path)
                .header(PlatformContextHeaders.TRACE_ID, "trace-test-001")
                .header(PlatformContextHeaders.TENANT_ID, tenantId)
                .header(PlatformContextHeaders.ACTOR_ID, actorId);
        if (websocket) {
            builder.header(HttpHeaders.CONNECTION, "Upgrade");
            builder.header(HttpHeaders.UPGRADE, "websocket");
        }
        return MockServerWebExchange.from(builder.build());
    }

    /**
     * 记录是否进入下游链路的测试用 chain。
     */
    private static class RecordingGatewayFilterChain implements GatewayFilterChain {

        private final Mono<Void> result;
        private final AtomicBoolean called = new AtomicBoolean(false);
        private final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();

        private RecordingGatewayFilterChain(Mono<Void> result) {
            this.result = result;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called.set(true);
            this.exchange.set(exchange);
            return result;
        }

        private boolean called() {
            return called.get();
        }

        private ServerWebExchange exchange() {
            return exchange.get();
        }
    }
}
