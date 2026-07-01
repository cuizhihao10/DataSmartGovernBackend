/**
 * @Author : Cui
 * @Date: 2026/07/01 11:02
 * @Description DataSmartGovernBackend - PlatformServiceHealthSnapshotResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台服务健康快照响应。
 *
 * <p>该响应用于把多个微服务的只读探针结果聚合成一次平台级快照。
 * 它不是长期时序指标存储，Prometheus 仍然负责持续抓取和历史查询；
 * 它也不是告警规则引擎，后续 Alertmanager/告警模块负责通知和升级。
 * 本接口的价值是让运维人员和 E2E 脚本能快速看到“当前这一刻哪些服务可达”。</p>
 *
 * @param snapshotVersion 快照契约版本。
 * @param includeMetricsProbe 本次是否包含 metrics 端点探针。
 * @param totalRuntimeCount 参与健康聚合的运行时数量。
 * @param upRuntimeCount 健康状态为 UP 的运行时数量。
 * @param degradedRuntimeCount 健康状态为 DEGRADED 的运行时数量。
 * @param downRuntimeCount 健康状态为 DOWN 的运行时数量。
 * @param statusCounts 按 UP/DEGRADED/DOWN 聚合的计数。
 * @param probes 具体探针明细。
 * @param nextActions 根据当前状态给出的低敏排障建议。
 * @param generatedAt 响应生成时间。
 */
public record PlatformServiceHealthSnapshotResponse(
        String snapshotVersion,
        boolean includeMetricsProbe,
        int totalRuntimeCount,
        int upRuntimeCount,
        int degradedRuntimeCount,
        int downRuntimeCount,
        Map<String, Long> statusCounts,
        List<PlatformServiceHealthProbeView> probes,
        List<String> nextActions,
        LocalDateTime generatedAt) {
}
