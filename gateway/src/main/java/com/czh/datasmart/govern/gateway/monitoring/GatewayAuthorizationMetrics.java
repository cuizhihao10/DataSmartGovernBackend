/**
 * @Author : Cui
 * @Date: 2026/05/23 18:08
 * @Description DataSmart Govern Backend - GatewayAuthorizationMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 网关授权链路指标记录器。
 *
 * <p>这个组件不负责业务决策，只负责把 gateway 入口层最重要的几类行为写成指标：
 * 1. 本次请求是公开路径绕过、缓存命中、缓存未命中，还是走了远程权限中心；
 * 2. 最终判定是允许、拒绝、影子拒绝还是异常；
 * 3. 远程权限中心调用耗时是多少。
 *
 * <p>为什么要单独抽出来，而不是在过滤器里直接散写 meterRegistry：
 * 1. 过滤器已经承担了路由上下文、权限判定、Header 透传和失败策略，继续塞指标逻辑会让职责过重；
 * 2. 指标命名、标签和值规整可以集中维护，后续要接 Prometheus、Grafana 或告警规则时更稳定；
 * 3. 单测可以只验证“指标有没有被正确记账”，不用把整个网关过滤器链路都拉起来。
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthorizationMetrics {

    private static final String METRIC_PREFIX = "datasmart.gateway.authorization";

    private final MeterRegistry meterRegistry;

    /**
     * 记录本次请求绕过了授权链路。
     *
     * <p>公开路径、授权功能关闭等场景不会调用 permission-admin，但它们仍然是入口层非常重要的运行状态。
     * 用单独的 outcome 标签可以让我们在 Grafana 上直接看见“为什么这段时间远程授权调用变少了”。
     */
    public void recordBypass(String reason) {
        increment("requests", "source", "BYPASS", "outcome", "BYPASS", "reason", normalize(reason));
    }

    /**
     * 记录缓存命中或未命中。
     *
     * <p>cache.hit/cache.miss 不是纯性能指标，它直接决定 gateway 是否在做重复远程判定。
     * 命中率过低通常意味着缓存策略太短、键过细，或者权限中心策略变化太频繁。
     */
    public void recordCacheAccess(boolean hit) {
        increment("cache.access", "result", hit ? "HIT" : "MISS");
    }

    /**
     * 记录最终判定结果。
     *
     * <p>source 用来区分结果来自本地缓存还是远程权限中心；
     * outcome 则区分 ALLOW、DENY、SHADOW_DENY、ERROR 等最终语义。
     */
    public void recordDecisionOutcome(String source, String outcome) {
        increment("decision.outcome", "source", normalize(source), "outcome", normalize(outcome));
    }

    /**
     * 记录内部服务端点保护结果。
     *
     * <p>AgentPlan 接入口、执行器回调、usage 写回这类端点通常由机器身份高频调用。
     * 它们一旦被普通用户误调、被异常重试打爆，或者被恶意 actor 探测，应该能在网关指标上直接看见。
     *
     * @param endpointName 内部端点规则名称。
     * @param outcome 保护结果，例如 ALLOW、ROLE_DENY、RATE_LIMITED。
     */
    public void recordInternalEndpointGuard(String endpointName, String outcome) {
        increment("internal_endpoint.guard",
                "endpoint", normalize(endpointName),
                "outcome", normalize(outcome));
    }

    /**
     * 记录远程授权判定耗时。
     *
     * <p>只有远程调用才适合计入这条 timer。缓存命中本身已经被 cache.access 指标表达，
     * 没必要让 0ms 的缓存分支污染远程链路时延分布。
     */
    public void recordDecisionLatency(String source, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        Timer.builder(metric("decision.latency"))
                .description("Gateway authorization decision latency")
                .tag("source", normalize(source))
                .register(meterRegistry)
                .record(duration);
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
