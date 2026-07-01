/**
 * @Author : Cui
 * @Date: 2026/07/01 11:06
 * @Description DataSmartGovernBackend - PlatformServiceHealthSnapshotServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service;

import com.czh.datasmart.govern.observability.config.PlatformHealthProbeProperties;
import com.czh.datasmart.govern.observability.controller.dto.PlatformServiceHealthSnapshotResponse;
import com.czh.datasmart.govern.observability.service.probe.PlatformEndpointProbeClient;
import com.czh.datasmart.govern.observability.service.probe.PlatformEndpointProbeResult;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台服务健康快照测试。
 *
 * <p>这里使用 fake probe client，而不是在测试里真的启动 gateway、data-quality、Python Runtime 等服务。
 * 这样测试验证的是 observability 的聚合规则，而不是本机环境是否刚好有端口在监听。</p>
 */
class PlatformServiceHealthSnapshotServiceTest {

    @Test
    void shouldAggregateRuntimeHealthWithoutReadingResponseBody() {
        PlatformHealthProbeProperties properties = new PlatformHealthProbeProperties();
        properties.setTimeoutMillis(200);
        PlatformServiceHealthSnapshotService service = new PlatformServiceHealthSnapshotService(
                new PlatformClosureReadinessService(),
                new FakeProbeClient(),
                properties);

        PlatformServiceHealthSnapshotResponse response = service.buildSnapshot(false);

        assertThat(response.snapshotVersion()).isEqualTo("platform-service-health-snapshot.v1");
        assertThat(response.totalRuntimeCount()).isEqualTo(9);
        assertThat(response.upRuntimeCount()).isEqualTo(8);
        assertThat(response.downRuntimeCount()).isEqualTo(1);
        assertThat(response.probes())
                .filteredOn(probe -> "data-quality".equals(probe.moduleCode()))
                .singleElement()
                .satisfies(probe -> {
                    assertThat(probe.status()).isEqualTo("DOWN");
                    assertThat(probe.issueCode()).isEqualTo("CONNECTION_REFUSED");
                    assertThat(probe.statusCode()).isNull();
                });
        assertThat(response.nextActions())
                .anySatisfy(action -> assertThat(action).contains("存在 DOWN 服务"));
    }

    @Test
    void shouldMarkRuntimeDegradedWhenMetricsProbeFails() {
        PlatformHealthProbeProperties properties = new PlatformHealthProbeProperties();
        properties.setTimeoutMillis(200);
        PlatformServiceHealthSnapshotService service = new PlatformServiceHealthSnapshotService(
                new PlatformClosureReadinessService(),
                new FakeProbeClient(),
                properties);

        PlatformServiceHealthSnapshotResponse response = service.buildSnapshot(true);

        assertThat(response.includeMetricsProbe()).isTrue();
        assertThat(response.degradedRuntimeCount()).isEqualTo(1);
        assertThat(response.probes())
                .filteredOn(probe -> "agent-runtime".equals(probe.moduleCode()))
                .filteredOn(probe -> "METRICS".equals(probe.probeType()))
                .singleElement()
                .satisfies(probe -> {
                    assertThat(probe.status()).isEqualTo("DOWN");
                    assertThat(probe.statusCode()).isEqualTo(404);
                    assertThat(probe.issueCode()).isEqualTo("HTTP_404");
                });
    }

    @Test
    void shouldUseConfiguredContainerServiceBaseUrlAndKeepLocalFallback() {
        PlatformHealthProbeProperties properties = new PlatformHealthProbeProperties();
        properties.setServiceBaseUrls(new LinkedHashMap<>(Map.of(
                "data-quality", "http://data-quality:8083/",
                "python-ai-runtime", "http://python-ai-runtime:8090")));
        RecordingProbeClient probeClient = new RecordingProbeClient();
        PlatformServiceHealthSnapshotService service = new PlatformServiceHealthSnapshotService(
                new PlatformClosureReadinessService(),
                probeClient,
                properties);

        service.buildSnapshot(false);

        /*
         * data-quality 使用容器 DNS，且配置末尾的斜杠不会造成 "//actuator"；
         * gateway 未配置覆盖地址，因此继续使用 localhost，证明本地运行方式没有被破坏。
         */
        assertThat(probeClient.requestedUris)
                .contains(
                        URI.create("http://data-quality:8083/actuator/health"),
                        URI.create("http://python-ai-runtime:8090/agent/capabilities/closure-readiness"),
                        URI.create("http://localhost:8080/actuator/health"));
    }

    /**
     * 测试专用 fake 探针客户端。
     *
     * <p>模拟规则：
     * - data-quality health 不可达，用来验证 DOWN 聚合；
     * - agent-runtime metrics 返回 404，用来验证 DEGRADED 聚合；
     * - 其他端点返回 200。</p>
     */
    private static class FakeProbeClient implements PlatformEndpointProbeClient {
        @Override
        public PlatformEndpointProbeResult probe(URI uri, Duration timeout) {
            String url = uri.toString();
            if (url.contains(":8083/actuator/health")) {
                return new PlatformEndpointProbeResult(false, null, 12, "CONNECTION_REFUSED");
            }
            if (url.contains(":8091/actuator/prometheus")) {
                return new PlatformEndpointProbeResult(true, 404, 8, "HTTP_404");
            }
            return new PlatformEndpointProbeResult(true, 200, 5, "OK");
        }
    }

    /**
     * 只记录请求目标的探针客户端，用来验证 URL 解析规则，不发出真实网络请求。
     */
    private static class RecordingProbeClient implements PlatformEndpointProbeClient {
        private final java.util.List<URI> requestedUris = new java.util.ArrayList<>();

        @Override
        public PlatformEndpointProbeResult probe(URI uri, Duration timeout) {
            requestedUris.add(uri);
            return new PlatformEndpointProbeResult(true, 200, 1, "OK");
        }
    }
}
