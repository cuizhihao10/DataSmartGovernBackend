/**
 * @Author : Cui
 * @Date: 2026/05/23 18:10
 * @Description DataSmart Govern Backend - GatewayAuthorizationMetricsTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关授权指标测试。
 *
 * <p>这个测试不关心授权是否允许或拒绝，而是确认 gateway 入口层的可观测信号能够被正确记账。
 * 对商业化产品来说，指标不是“附带装饰”，而是判断授权链路是否健康、缓存是否有效、权限中心是否慢的重要依据。
 */
class GatewayAuthorizationMetricsTest {

    /**
     * 授权链路的几个典型事件都应该写入对应指标。
     *
     * <p>这里覆盖公开路径绕过、缓存命中、远程判定结果和远程判定耗时，
     * 用来保证后续接 Prometheus/Grafana 时不会出现“面板有了但数据全是空”的情况。
     */
    @Test
    void recordsBypassCacheAndDecisionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthorizationMetrics metrics = new GatewayAuthorizationMetrics(registry);

        metrics.recordBypass("PUBLIC_PATH");
        metrics.recordCacheAccess(true);
        metrics.recordCacheAccess(false);
        metrics.recordDecisionOutcome("REMOTE", "ALLOW");
        metrics.recordDecisionOutcome("CACHE", "DENY");
        metrics.recordDecisionLatency("REMOTE", Duration.ofMillis(123));

        assertThat(registry.find("datasmart.gateway.authorization.requests")
                .tag("source", "BYPASS")
                .tag("outcome", "BYPASS")
                .tag("reason", "PUBLIC_PATH")
                .counter().count()).isEqualTo(1.0d);

        assertThat(registry.find("datasmart.gateway.authorization.cache.access")
                .tag("result", "HIT")
                .counter().count()).isEqualTo(1.0d);

        assertThat(registry.find("datasmart.gateway.authorization.cache.access")
                .tag("result", "MISS")
                .counter().count()).isEqualTo(1.0d);

        assertThat(registry.find("datasmart.gateway.authorization.decision.outcome")
                .tag("source", "REMOTE")
                .tag("outcome", "ALLOW")
                .counter().count()).isEqualTo(1.0d);

        assertThat(registry.find("datasmart.gateway.authorization.decision.outcome")
                .tag("source", "CACHE")
                .tag("outcome", "DENY")
                .counter().count()).isEqualTo(1.0d);

        assertThat(registry.find("datasmart.gateway.authorization.decision.latency")
                .tag("source", "REMOTE")
                .timer().count()).isEqualTo(1L);
    }
}
