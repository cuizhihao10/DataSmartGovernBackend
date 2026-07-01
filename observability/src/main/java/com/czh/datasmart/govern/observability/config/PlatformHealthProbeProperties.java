/**
 * @Author : Cui
 * @Date: 2026/07/01 10:58
 * @Description DataSmartGovernBackend - PlatformHealthProbeProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台健康探针配置。
 *
 * <p>observability 的健康聚合接口会主动访问各微服务的 health/metrics 端点。
 * 这类探针必须有明确超时和数量边界，否则当多个下游服务不可用时，监控接口本身会被拖慢，
 * 甚至出现“为了排障而访问 observability，结果 observability 被故障服务拖死”的反效果。</p>
 *
 * <p>本配置当前只控制通用探针行为，具体服务清单仍来自 PlatformClosureReadinessService。
 * 后续如果进入生产部署，可以把服务清单也迁移到配置中心/Nacos/数据库，支持不同环境有不同目标。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.observability.platform-health")
public class PlatformHealthProbeProperties {

    /**
     * 单个 HTTP 探针的超时时间，单位毫秒。
     *
     * <p>本地默认 800ms 是为了快速给出“服务未启动/端口不通”的诊断；
     * 生产环境可按网络拓扑、服务数量和 SLA 调整，但不建议超过几秒。</p>
     */
    private int timeoutMillis = 800;

    /**
     * 是否默认同时探测 metrics 端点。
     *
     * <p>health 能说明服务是否存活，metrics 能说明 Prometheus 是否有抓取入口。
     * metrics 探针会额外产生 HTTP 请求，因此默认关闭；调用接口时可以用 query 参数临时打开。</p>
     */
    private boolean includeMetricsProbeByDefault = false;

    /**
     * 单次请求最多探测多少个运行时。
     *
     * <p>这是一个防御性边界。当前服务数量很少，但未来如果接入更多 worker、connector 或租户级实例，
     * 需要防止一次诊断请求扩散成大量下游 HTTP 调用。</p>
     */
    private int maxProbeTargets = 32;
}
