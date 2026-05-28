/**
 * @Author : Cui
 * @Date: 2026/05/25 01:37
 * @Description DataSmart Govern Backend - GatewayAgentEventWebSocketMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 实时事件 WebSocket 网关指标记录器。
 *
 * <p>实时事件连接与普通 REST 请求不同：REST 请求通常几百毫秒结束，而 WebSocket 连接可能持续几分钟甚至更久。
 * 因此只统计“请求数”并不能表达入口压力，必须至少观察：
 * 1. 当前活跃连接数；
 * 2. 握手接受次数；
 * 3. 因非法握手、全局配额、租户配额、用户配额而拒绝的次数；
 * 4. 连接关闭次数。
 *
 * <p>本类不参与任何限流决策，只负责把网关守卫结果转成 Micrometer 指标。这样后续接 Prometheus/Grafana 时，
 * 可以围绕 `datasmart.gateway.agent_events.websocket.*` 指标建立告警和容量面板。
 */
@Component
@RequiredArgsConstructor
public class GatewayAgentEventWebSocketMetrics {

    private static final String METRIC_PREFIX = "datasmart.gateway.agent_events.websocket";

    private final MeterRegistry meterRegistry;

    /**
     * 注册当前活跃连接数 gauge。
     *
     * <p>gauge 读取的是过滤器中的 AtomicInteger，而不是复制一份值。这样连接打开/关闭时只维护一处计数，
     * 指标系统按需读取即可，避免指标值与真实状态漂移。
     */
    public void bindActiveConnectionsGauge(AtomicInteger activeConnections) {
        Gauge.builder(metric("active_connections"), activeConnections, AtomicInteger::get)
                .description("Current active Agent event WebSocket connections on this gateway instance")
                .register(meterRegistry);
    }

    /**
     * 记录一次连接握手被接受。
     *
     * <p>这里的 accepted 表示 gateway 已经允许请求进入后续路由链，不代表 Python Runtime 最终订阅成功。
     * Python Runtime 仍可能因为订阅权限、session 不存在或协议参数错误返回控制帧错误。
     */
    public void recordAccepted() {
        increment("handshake", "outcome", "ACCEPTED", "reason", "OK");
    }

    /**
     * 记录一次连接握手被拒绝。
     *
     * @param reason 拒绝原因，例如 INVALID_UPGRADE、GLOBAL_LIMIT、TENANT_LIMIT、ACTOR_LIMIT。
     */
    public void recordRejected(String reason) {
        increment("handshake", "outcome", "REJECTED", "reason", normalize(reason));
    }

    /**
     * 记录连接关闭。
     *
     * <p>关闭不代表错误，正常用户离开页面、浏览器刷新、网络断开、服务端主动关闭都会进入这里。
     * 该指标主要用于观察连接生命周期和异常重连风暴。
     */
    public void recordClosed(String reason) {
        increment("connection.closed", "reason", normalize(reason));
    }

    private void increment(String metricSuffix, String... tags) {
        Counter.builder(metric(metricSuffix))
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    private String metric(String suffix) {
        return METRIC_PREFIX + "." + suffix;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
