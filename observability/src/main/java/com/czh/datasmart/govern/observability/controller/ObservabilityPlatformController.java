/**
 * @Author : Cui
 * @Date: 2026/07/01 10:49
 * @Description DataSmartGovernBackend - ObservabilityPlatformController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.observability.controller.dto.PlatformAlertCoverageResponse;
import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureReadinessResponse;
import com.czh.datasmart.govern.observability.controller.dto.PlatformServiceHealthSnapshotResponse;
import com.czh.datasmart.govern.observability.service.PlatformAlertCoverageService;
import com.czh.datasmart.govern.observability.service.PlatformClosureReadinessService;
import com.czh.datasmart.govern.observability.service.PlatformServiceHealthSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 平台可观测性控制面接口。
 *
 * <p>observability 不应该只停留在“Spring Boot 能启动 + /actuator/health 可访问”的层面。
 * 对商业化项目来说，它还需要回答更高层的问题：当前平台到底有哪些运行时、哪些模块已经纳入
 * gateway/Prometheus/smoke 闭环、哪些只是共享库、哪些能力还需要收尾。</p>
 *
 * <p>路由设计：
 * - 直连服务时使用 /observability/platform/closure-readiness；
 * - 通过 gateway 时外部使用 /api/observability/platform/closure-readiness；
 * - 为了兼容当前 gateway 迁移期，这里同时接受两个前缀。后续网关 RewritePath 稳定后，
 *   仍可保留兼容路径，避免本地脚本或旧文档短期失效。</p>
 *
 * <p>权限建议：
 * 当前接口只返回低敏平台结构和闭环状态，适合绑定 VIEW/DIAGNOSE 类权限。
 * 生产环境仍应经过 gateway + permission-admin，不应把 8084 直接暴露到公网。</p>
 */
@RestController
@RequestMapping({"/observability/platform", "/api/observability/platform"})
@RequiredArgsConstructor
public class ObservabilityPlatformController {

    private final PlatformClosureReadinessService platformClosureReadinessService;
    private final PlatformServiceHealthSnapshotService platformServiceHealthSnapshotService;
    private final PlatformAlertCoverageService platformAlertCoverageService;

    /**
     * 查询平台闭环 readiness。
     *
     * <p>该接口用于回答“data-quality、observability、platform-common 是否漏做成微服务”这类
     * 产品架构验收问题。它不会实时探测每个下游端口，也不会读取 Prometheus 指标正文；
     * 实时探活继续由 Actuator、Prometheus 和本地 smoke 脚本负责。</p>
     *
     * <p>返回内容遵循低敏原则：只包含模块编码、职责、默认端口、网关前缀、探活路径、指标路径、
     * 闭环证据和下一步建议，不包含 token、密码、SQL、prompt、工具参数、样本数据、模型输出或内部响应正文。</p>
     *
     * @param traceId gateway 透传的链路 ID；直连本服务时如果没有该 Header，会生成本地低敏 traceId。
     * @return 平台闭环 readiness 响应。
     */
    @GetMapping("/closure-readiness")
    public ResponseEntity<PlatformApiResponse<PlatformClosureReadinessResponse>> closureReadiness(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        String resolvedTraceId = resolveTraceId(traceId);
        return ResponseEntity.ok(PlatformApiResponse.success(
                "平台闭环 readiness 诊断生成完成",
                platformClosureReadinessService.buildClosureReadiness(),
                resolvedTraceId));
    }

    /**
     * 查询平台服务健康快照。
     *
     * <p>这个接口是 observability 的真实运行态聚合能力：
     * 它会按平台服务目录访问每个可部署运行时的 health 端点，并可选访问 metrics 端点。
     * 与 closure-readiness 不同，closure-readiness 回答“架构上应该有哪些模块”，
     * service-health-snapshots 回答“当前这一刻这些运行时是否真的可达”。</p>
     *
     * <p>安全边界：
     * - 只做 GET 探针，不创建任务、不触发 worker、不执行 SQL；
     * - 不读取响应正文，只保留状态码、耗时和 issueCode；
     * - 不输出 token、密码、内部异常堆栈、Prometheus 指标正文或业务数据。</p>
     *
     * @param includeMetricsProbe 是否同时探测 metrics 端点；为空时使用配置默认值。
     * @param traceId gateway 透传的链路 ID。
     * @return 平台服务健康快照。
     */
    @GetMapping("/service-health-snapshots")
    public ResponseEntity<PlatformApiResponse<PlatformServiceHealthSnapshotResponse>> serviceHealthSnapshots(
            @RequestParam(required = false) Boolean includeMetricsProbe,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        String resolvedTraceId = resolveTraceId(traceId);
        return ResponseEntity.ok(PlatformApiResponse.success(
                "平台服务健康快照生成完成",
                platformServiceHealthSnapshotService.buildSnapshot(includeMetricsProbe),
                resolvedTraceId));
    }

    /**
     * 查询平台基础告警覆盖。
     *
     * <p>该接口把“服务目录”和“当前健康快照”组合成一个告警视图：
     * - rules 描述每个运行时默认应具备的基础告警规则；
     * - activeCandidates 描述当前探针结果推导出的告警候选；
     * - nextActions 描述为了完成商业化运维闭环还需要做什么。</p>
     *
     * <p>为什么不直接返回 Prometheus 原始规则或指标：
     * Prometheus rule 与指标正文可能包含内部 job、instance、label、路径等部署细节；
     * 当前接口只输出产品语义和低敏排障建议，真实规则文件和 Grafana 面板继续作为基础设施资产管理。</p>
     *
     * @param includeMetricsProbe 是否同时探测 metrics 并把 metrics 结果纳入告警候选。
     * @param traceId gateway 透传的链路 ID。
     * @return 平台告警覆盖响应。
     */
    @GetMapping("/alert-coverage")
    public ResponseEntity<PlatformApiResponse<PlatformAlertCoverageResponse>> alertCoverage(
            @RequestParam(required = false) Boolean includeMetricsProbe,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        String resolvedTraceId = resolveTraceId(traceId);
        return ResponseEntity.ok(PlatformApiResponse.success(
                "平台基础告警覆盖视图生成完成",
                platformAlertCoverageService.buildCoverage(includeMetricsProbe),
                resolvedTraceId));
    }

    /**
     * 解析 traceId。
     *
     * <p>traceId 是排障和审计串联的基础字段。gateway 场景下应由上游统一生成；
     * 直连 observability 做本地学习或 smoke 时没有 gateway，因此这里生成一个前缀明确的本地 traceId，
     * 方便日志里区分“真实网关请求”和“本地直接诊断请求”。</p>
     */
    private String resolveTraceId(String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return "observability-local-" + UUID.randomUUID();
        }
        return traceId.trim();
    }
}
