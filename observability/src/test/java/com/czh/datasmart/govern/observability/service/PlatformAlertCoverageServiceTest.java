/**
 * @Author : Cui
 * @Date: 2026/07/01 16:45
 * @Description DataSmartGovernBackend - PlatformAlertCoverageServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service;

import com.czh.datasmart.govern.observability.config.PlatformHealthProbeProperties;
import com.czh.datasmart.govern.observability.controller.dto.PlatformAlertCoverageResponse;
import com.czh.datasmart.govern.observability.service.probe.PlatformEndpointProbeClient;
import com.czh.datasmart.govern.observability.service.probe.PlatformEndpointProbeResult;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台告警覆盖服务测试。
 *
 * <p>测试目标不是验证网络环境，而是锁定产品闭环语义：
 * 1. 每个可部署运行时都至少有 service-down 与 metrics-endpoint-down 两条基础规则；
 * 2. health 不可达应生成 CRITICAL 候选；
 * 3. metrics 不可达应生成 WARNING 候选；
 * 4. 候选内容不得依赖 HTTP 响应正文或 Prometheus 指标正文。</p>
 */
class PlatformAlertCoverageServiceTest {

    @Test
    void shouldBuildDefaultAlertRulesForEveryDeployableRuntime() {
        PlatformAlertCoverageService service = newService();

        PlatformAlertCoverageResponse response = service.buildCoverage(false);

        assertThat(response.schemaVersion()).isEqualTo("platform-alert-coverage.v1");
        assertThat(response.deployableRuntimeCount()).isEqualTo(9);
        assertThat(response.coveredRuntimeCount()).isEqualTo(9);
        assertThat(response.missingRuntimeCount()).isZero();
        assertThat(response.totalRuleCount()).isEqualTo(18);
        assertThat(response.enabledRuleCount()).isEqualTo(18);
        assertThat(response.ruleCountsByModule().get("data-quality")).isEqualTo(2L);
        assertThat(response.rules())
                .anySatisfy(rule -> {
                    assertThat(rule.ruleCode()).isEqualTo("observability.service-down");
                    assertThat(rule.severity()).isEqualTo("CRITICAL");
                    assertThat(rule.enabledByDefault()).isTrue();
                });
    }

    @Test
    void shouldConvertHealthAndMetricsFailuresToLowSensitiveCandidates() {
        PlatformAlertCoverageService service = newService();

        PlatformAlertCoverageResponse response = service.buildCoverage(true);

        assertThat(response.includeMetricsProbe()).isTrue();
        assertThat(response.activeCandidates()).hasSize(2);
        assertThat(response.activeCandidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.ruleCode()).isEqualTo("data-quality.service-down");
                    assertThat(candidate.severity()).isEqualTo("CRITICAL");
                    assertThat(candidate.issueCode()).isEqualTo("CONNECTION_REFUSED");
                })
                .anySatisfy(candidate -> {
                    assertThat(candidate.ruleCode()).isEqualTo("agent-runtime.metrics-endpoint-down");
                    assertThat(candidate.severity()).isEqualTo("WARNING");
                    assertThat(candidate.issueCode()).isEqualTo("HTTP_404");
                });
        assertThat(response.nextActions())
                .anySatisfy(action -> assertThat(action).contains("告警候选"));
    }

    private PlatformAlertCoverageService newService() {
        PlatformHealthProbeProperties properties = new PlatformHealthProbeProperties();
        properties.setTimeoutMillis(200);
        PlatformClosureReadinessService readinessService = new PlatformClosureReadinessService();
        PlatformServiceHealthSnapshotService healthSnapshotService = new PlatformServiceHealthSnapshotService(
                readinessService,
                new FakeProbeClient(),
                properties);
        return new PlatformAlertCoverageService(readinessService, healthSnapshotService);
    }

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
}
