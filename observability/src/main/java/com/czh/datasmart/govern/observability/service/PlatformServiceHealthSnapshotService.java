/**
 * @Author : Cui
 * @Date: 2026/07/01 11:03
 * @Description DataSmartGovernBackend - PlatformServiceHealthSnapshotService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service;

import com.czh.datasmart.govern.observability.config.PlatformHealthProbeProperties;
import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureModuleView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureReadinessResponse;
import com.czh.datasmart.govern.observability.controller.dto.PlatformServiceHealthProbeView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformServiceHealthSnapshotResponse;
import com.czh.datasmart.govern.observability.service.probe.PlatformEndpointProbeClient;
import com.czh.datasmart.govern.observability.service.probe.PlatformEndpointProbeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台服务健康快照服务。
 *
 * <p>这是 observability 从“只有 Actuator 壳”走向“真正可观测控制面”的关键能力之一：
 * 它不再只报告自己是否活着，而是按服务清单主动访问各运行时的 health/metrics 端点，
 * 给出一次平台级健康快照。</p>
 *
 * <p>设计边界：
 * 1. 该服务做实时轻量探针，不做历史时序存储；历史趋势仍交给 Prometheus；
 * 2. 该服务只读，不创建任务、不触发 worker、不执行 SQL、不读取业务数据；
 * 3. 该服务不读取响应正文，只根据状态码和网络异常分类生成低敏状态；
 * 4. 若所有服务都不可达，observability 自己仍应尽快返回诊断结果，而不是长时间卡住。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformServiceHealthSnapshotService {

    private static final String SNAPSHOT_VERSION = "platform-service-health-snapshot.v1";
    private static final String HEALTH = "HEALTH";
    private static final String METRICS = "METRICS";
    private static final String UP = "UP";
    private static final String DEGRADED = "DEGRADED";
    private static final String DOWN = "DOWN";
    private static final String SKIPPED = "SKIPPED";

    private final PlatformClosureReadinessService closureReadinessService;
    private final PlatformEndpointProbeClient endpointProbeClient;
    private final PlatformHealthProbeProperties properties;

    /**
     * 构建平台服务健康快照。
     *
     * @param includeMetricsProbe 请求方是否要求同时探测 metrics 端点；为空时使用配置默认值。
     * @return 当前时刻的平台服务健康快照。
     */
    public PlatformServiceHealthSnapshotResponse buildSnapshot(Boolean includeMetricsProbe) {
        boolean resolvedIncludeMetricsProbe = includeMetricsProbe == null
                ? properties.isIncludeMetricsProbeByDefault()
                : includeMetricsProbe;
        Duration timeout = Duration.ofMillis(Math.max(100, properties.getTimeoutMillis()));
        PlatformClosureReadinessResponse readiness = closureReadinessService.buildClosureReadiness();
        List<PlatformClosureModuleView> runtimeModules = readiness.modules().stream()
                .filter(PlatformClosureModuleView::deployableRuntime)
                .limit(Math.max(1, properties.getMaxProbeTargets()))
                .toList();

        List<PlatformServiceHealthProbeView> probes = new ArrayList<>();
        for (PlatformClosureModuleView module : runtimeModules) {
            PlatformServiceHealthProbeView healthProbe = probe(module, HEALTH, module.healthProbePath(), timeout);
            probes.add(healthProbe);
            if (resolvedIncludeMetricsProbe && module.metricsProbePath() != null) {
                probes.add(probe(module, METRICS, module.metricsProbePath(), timeout));
            }
        }

        Map<String, String> runtimeStatus = aggregateRuntimeStatus(runtimeModules, probes, resolvedIncludeMetricsProbe);
        Map<String, Long> statusCounts = runtimeStatus.values().stream()
                .collect(Collectors.groupingBy(status -> status, LinkedHashMap::new, Collectors.counting()));
        int upCount = statusCounts.getOrDefault(UP, 0L).intValue();
        int degradedCount = statusCounts.getOrDefault(DEGRADED, 0L).intValue();
        int downCount = statusCounts.getOrDefault(DOWN, 0L).intValue();

        return new PlatformServiceHealthSnapshotResponse(
                SNAPSHOT_VERSION,
                resolvedIncludeMetricsProbe,
                runtimeModules.size(),
                upCount,
                degradedCount,
                downCount,
                statusCounts,
                probes,
                buildNextActions(downCount, degradedCount, resolvedIncludeMetricsProbe),
                LocalDateTime.now());
    }

    /**
     * 对某个模块的单个端点进行探测。
     *
     * <p>如果模块没有端口或探针路径，返回 SKIPPED。platform-common 这类共享库在上游已经被过滤，
     * 但这里仍保留兜底判断，防止未来配置错误导致空 URL 被访问。</p>
     */
    private PlatformServiceHealthProbeView probe(
            PlatformClosureModuleView module,
            String probeType,
            String path,
            Duration timeout) {
        if (module.defaultPort() == null || path == null || path.isBlank()) {
            return new PlatformServiceHealthProbeView(
                    module.moduleCode(),
                    module.displayName(),
                    module.moduleKind(),
                    null,
                    probeType,
                    SKIPPED,
                    null,
                    0L,
                    "NO_PROBE_PATH");
        }
        String targetUrl = "http://localhost:" + module.defaultPort() + path;
        PlatformEndpointProbeResult result = endpointProbeClient.probe(URI.create(targetUrl), timeout);
        return new PlatformServiceHealthProbeView(
                module.moduleCode(),
                module.displayName(),
                module.moduleKind(),
                targetUrl,
                probeType,
                resolveProbeStatus(result),
                result.statusCode(),
                result.durationMs(),
                result.issueCode());
    }

    private String resolveProbeStatus(PlatformEndpointProbeResult result) {
        if (result.reachable() && result.statusCode() != null && result.statusCode() >= 200 && result.statusCode() < 300) {
            return UP;
        }
        return DOWN;
    }

    /**
     * 按运行时聚合健康状态。
     *
     * <p>健康端点优先级高于 metrics：health DOWN 表示业务服务不可用或尚未启动；
     * health UP 但 metrics DOWN 表示服务可用但监控出口不完整，因此标记为 DEGRADED。</p>
     */
    private Map<String, String> aggregateRuntimeStatus(
            List<PlatformClosureModuleView> runtimeModules,
            List<PlatformServiceHealthProbeView> probes,
            boolean includeMetricsProbe) {
        Map<String, String> runtimeStatus = new LinkedHashMap<>();
        for (PlatformClosureModuleView module : runtimeModules) {
            PlatformServiceHealthProbeView health = findProbe(probes, module.moduleCode(), HEALTH);
            PlatformServiceHealthProbeView metrics = findProbe(probes, module.moduleCode(), METRICS);
            if (health == null || !UP.equals(health.status())) {
                runtimeStatus.put(module.moduleCode(), DOWN);
            } else if (includeMetricsProbe && metrics != null && !UP.equals(metrics.status())) {
                runtimeStatus.put(module.moduleCode(), DEGRADED);
            } else {
                runtimeStatus.put(module.moduleCode(), UP);
            }
        }
        return runtimeStatus;
    }

    private PlatformServiceHealthProbeView findProbe(
            List<PlatformServiceHealthProbeView> probes,
            String moduleCode,
            String probeType) {
        return probes.stream()
                .filter(probe -> moduleCode.equals(probe.moduleCode()))
                .filter(probe -> probeType.equals(probe.probeType()))
                .findFirst()
                .orElse(null);
    }

    private List<String> buildNextActions(int downCount, int degradedCount, boolean includeMetricsProbe) {
        List<String> actions = new ArrayList<>();
        if (downCount > 0) {
            actions.add("存在 DOWN 服务：优先检查对应端口、Spring Boot 启动日志、Docker 中间件、MySQL/Nacos/Kafka 连接和 JDK 21 环境。");
        }
        if (degradedCount > 0) {
            actions.add("存在 DEGRADED 服务：业务 health 可达但 metrics 不完整，优先检查 Actuator exposure、micrometer registry 和 Prometheus 抓取路径。");
        }
        if (!includeMetricsProbe) {
            actions.add("当前未开启 metrics 探针；如需同时确认 Prometheus 出口，可传 includeMetricsProbe=true。");
        }
        if (actions.isEmpty()) {
            actions.add("当前探针均为 UP；下一步应结合 Prometheus target 状态和告警规则确认持续可观测性。");
        }
        return actions;
    }
}
