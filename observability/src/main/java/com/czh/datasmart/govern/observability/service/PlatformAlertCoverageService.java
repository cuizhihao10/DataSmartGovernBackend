/**
 * @Author : Cui
 * @Date: 2026/07/01 16:44
 * @Description DataSmartGovernBackend - PlatformAlertCoverageService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service;

import com.czh.datasmart.govern.observability.controller.dto.PlatformAlertCandidateView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformAlertCoverageResponse;
import com.czh.datasmart.govern.observability.controller.dto.PlatformAlertRuleView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureModuleView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureReadinessResponse;
import com.czh.datasmart.govern.observability.controller.dto.PlatformServiceHealthProbeView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformServiceHealthSnapshotResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台告警覆盖服务。
 *
 * <p>本服务的定位是“告警规则目录 + 当前告警候选”的闭环控制面。
 * 它不替代 Prometheus/Alertmanager 的持续采集、表达式计算、静默、抑制、升级和通知能力；
 * 但它会把本项目最小商用闭环必须具备的规则覆盖关系显式化，避免 observability 只停留在能启动的空壳。</p>
 *
 * <p>为什么此处先使用派生规则而不是直接写数据库：
 * 1. 当前收敛目标是尽快完成可验收闭环，基础服务可用性与 metrics 出口覆盖应当随平台服务目录自动生成；
 * 2. 这些规则属于“平台基础规则”，不适合让租户随意删除，否则会破坏交付验收；
 * 3. 后续再增加租户自定义规则、业务指标规则、通知订阅和静默窗口时，可以把本服务输出作为默认模板源。</p>
 *
 * <p>敏感信息边界：
 * 服务只读取 closure readiness 的低敏模块目录和 health snapshot 的低敏探针结果。
 * 它不会读取 HTTP 响应正文、Prometheus 指标正文、日志正文、token、密码、SQL、业务样本、prompt 或模型输出。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformAlertCoverageService {

    private static final String SCHEMA_VERSION = "platform-alert-coverage.v1";
    private static final String HEALTH = "HEALTH";
    private static final String METRICS = "METRICS";
    private static final String UP = "UP";

    private final PlatformClosureReadinessService closureReadinessService;
    private final PlatformServiceHealthSnapshotService healthSnapshotService;

    /**
     * 构建平台告警覆盖响应。
     *
     * @param includeMetricsProbe 是否把 metrics 探针纳入候选告警评估。
     *                            为空时由健康快照服务继续使用自身默认配置，保持两个接口的行为一致。
     * @return 平台告警覆盖视图。
     */
    public PlatformAlertCoverageResponse buildCoverage(Boolean includeMetricsProbe) {
        PlatformClosureReadinessResponse readiness = closureReadinessService.buildClosureReadiness();
        PlatformServiceHealthSnapshotResponse snapshot = healthSnapshotService.buildSnapshot(includeMetricsProbe);
        List<PlatformClosureModuleView> runtimeModules = readiness.modules().stream()
                .filter(PlatformClosureModuleView::deployableRuntime)
                .toList();
        List<PlatformAlertRuleView> rules = buildDefaultRules(runtimeModules);
        List<PlatformAlertCandidateView> candidates = buildCandidates(snapshot.probes());

        Set<String> coveredModules = rules.stream()
                .map(PlatformAlertRuleView::moduleCode)
                .collect(Collectors.toSet());
        Map<String, Long> ruleCountsByModule = rules.stream()
                .collect(Collectors.groupingBy(
                        PlatformAlertRuleView::moduleCode,
                        LinkedHashMap::new,
                        Collectors.counting()));

        int coveredRuntimeCount = (int) runtimeModules.stream()
                .filter(module -> coveredModules.contains(module.moduleCode()))
                .count();
        int enabledRuleCount = (int) rules.stream()
                .filter(PlatformAlertRuleView::enabledByDefault)
                .count();

        return new PlatformAlertCoverageResponse(
                SCHEMA_VERSION,
                snapshot.includeMetricsProbe(),
                runtimeModules.size(),
                coveredRuntimeCount,
                runtimeModules.size() - coveredRuntimeCount,
                rules.size(),
                enabledRuleCount,
                candidates.size(),
                ruleCountsByModule,
                rules,
                candidates,
                buildNextActions(runtimeModules.size(), coveredRuntimeCount, candidates, snapshot.includeMetricsProbe()),
                LocalDateTime.now());
    }

    /**
     * 根据平台服务目录生成默认基础告警规则。
     *
     * <p>每个可部署运行时至少拥有两类基础规则：
     * 1. 可用性规则：health 探针不可达时产生 CRITICAL 候选；
     * 2. 指标出口规则：metrics 探针不可达时产生 WARNING 候选。
     * 这两类规则覆盖的是“平台能不能被发现、能不能被持续观测”，属于商业交付最小闭环。</p>
     */
    private List<PlatformAlertRuleView> buildDefaultRules(List<PlatformClosureModuleView> runtimeModules) {
        List<PlatformAlertRuleView> rules = new ArrayList<>();
        for (PlatformClosureModuleView module : runtimeModules) {
            rules.add(new PlatformAlertRuleView(
                    module.moduleCode() + ".service-down",
                    module.moduleCode(),
                    module.displayName() + "服务不可用",
                    "AVAILABILITY",
                    "CRITICAL",
                    "HEALTH_PROBE",
                    "health 探针不可达、返回非 2xx 或发生连接/超时异常时触发。",
                    "OPERATOR",
                    "优先检查端口、进程日志、JDK 21、Nacos 注册、MySQL/Kafka/Redis/Neo4j 等依赖。",
                    true));
            rules.add(new PlatformAlertRuleView(
                    module.moduleCode() + ".metrics-endpoint-down",
                    module.moduleCode(),
                    module.displayName() + "指标出口不可用",
                    "METRICS_EXPORT",
                    "WARNING",
                    "METRICS_PROBE",
                    "业务 health 可达但 metrics 探针不可达、返回非 2xx 或发生连接/超时异常时触发。",
                    "OPERATOR",
                    "检查 Actuator exposure、micrometer-registry-prometheus、gateway/Prometheus 抓取路径和网络策略。",
                    true));
        }
        return rules;
    }

    /**
     * 把健康快照中的 DOWN 探针转换为告警候选。
     *
     * <p>只根据探针元数据生成候选，不读取下游响应正文。
     * health DOWN 与 metrics DOWN 的严重级别不同：前者表示运行时不可用，后者表示业务可能可用但观测链路不完整。</p>
     */
    private List<PlatformAlertCandidateView> buildCandidates(List<PlatformServiceHealthProbeView> probes) {
        List<PlatformAlertCandidateView> candidates = new ArrayList<>();
        for (PlatformServiceHealthProbeView probe : probes) {
            if (UP.equals(probe.status())) {
                continue;
            }
            if (HEALTH.equals(probe.probeType())) {
                candidates.add(new PlatformAlertCandidateView(
                        probe.moduleCode() + ".service-down",
                        probe.moduleCode(),
                        probe.displayName(),
                        "CRITICAL",
                        probe.status(),
                        probe.issueCode(),
                        probe.displayName() + " health 探针当前不可用，平台闭环验收不能认为该运行时在线。",
                        "检查服务进程、端口监听、启动日志、依赖中间件和本地环境变量。"));
            } else if (METRICS.equals(probe.probeType())) {
                candidates.add(new PlatformAlertCandidateView(
                        probe.moduleCode() + ".metrics-endpoint-down",
                        probe.moduleCode(),
                        probe.displayName(),
                        "WARNING",
                        probe.status(),
                        probe.issueCode(),
                        probe.displayName() + " metrics 探针当前不可用，Prometheus 持续抓取闭环不完整。",
                        "检查 Actuator 暴露配置、Prometheus registry 依赖和 /actuator/prometheus 或 /agent/metrics 路径。"));
            }
        }
        return candidates;
    }

    private List<String> buildNextActions(
            int runtimeCount,
            int coveredRuntimeCount,
            List<PlatformAlertCandidateView> candidates,
            boolean includeMetricsProbe) {
        List<String> actions = new ArrayList<>();
        if (coveredRuntimeCount < runtimeCount) {
            actions.add("存在运行时没有默认基础告警覆盖，请先补齐 service-down 与 metrics-endpoint-down 规则。");
        }
        if (!candidates.isEmpty()) {
            actions.add("当前健康快照已产生告警候选，请优先处理 CRITICAL 候选，再处理 WARNING 级指标出口问题。");
        }
        if (!includeMetricsProbe) {
            actions.add("本次未纳入 metrics 探针；发布验收或 Prometheus 接入排障时建议传 includeMetricsProbe=true。");
        }
        actions.add("后续生产化应把该规则目录物化到 Prometheus rule 文件、Alertmanager 路由和 Grafana dashboard 清单。");
        actions.add("业务级质量告警应继续补充 data-quality 执行失败率、异常数量突增、长时间 RUNNING 和报告导出审计。");
        return actions;
    }
}
