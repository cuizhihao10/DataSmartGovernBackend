/**
 * @Author : Cui
 * @Date: 2026/07/01 10:59
 * @Description DataSmartGovernBackend - PlatformEndpointProbeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service.probe;

import java.net.URI;
import java.time.Duration;

/**
 * 平台端点探针客户端抽象。
 *
 * <p>为什么不把 JDK HttpClient 直接写死在业务服务里：
 * 1. observability 的健康聚合是一个产品能力，后续可能切换为 Prometheus API、服务发现实例、
 *    OpenTelemetry collector 或 service mesh health API；
 * 2. 用接口隔离后，单元测试可以注入 fake client，不需要真的启动 9 个微服务；
 * 3. 真实实现可以统一处理超时、异常分类和低敏日志，避免各处重复写 HTTP 调用。</p>
 */
public interface PlatformEndpointProbeClient {

    /**
     * 对目标 URI 发起一次 GET 探针。
     *
     * @param uri 目标地址。
     * @param timeout 单次探针超时时间。
     * @return 低敏探针结果。
     */
    PlatformEndpointProbeResult probe(URI uri, Duration timeout);
}
