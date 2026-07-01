/**
 * @Author : Cui
 * @Date: 2026/07/02 02:35
 * @Description DataSmart Govern Backend - RecordingGatewayFilterChain.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 记录下游转发事实的 Gateway 测试夹具。
 *
 * <p>真实 GatewayFilterChain 会继续执行过滤器和路由转发；授权单元测试只需要验证是否继续调用 chain，
 * 以及传入的 exchange 是否已经携带可信数据范围 Header。独立夹具可以被后续授权、签名或上下文过滤器
 * 测试复用，同时避免每个测试类重复实现一套原子状态记录逻辑。
 */
final class RecordingGatewayFilterChain implements GatewayFilterChain {

    private final AtomicBoolean called = new AtomicBoolean(false);
    private final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange) {
        this.called.set(true);
        this.exchange.set(exchange);
        return Mono.empty();
    }

    boolean called() {
        return called.get();
    }

    ServerWebExchange exchange() {
        return exchange.get();
    }
}
