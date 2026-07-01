/**
 * @Author : Cui
 * @Date: 2026/07/01 16:43
 * @Description DataSmartGovernBackend - PlatformAlertCoverageResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台告警覆盖响应。
 *
 * <p>该响应把“应该有哪些告警规则”和“当前快照触发了哪些告警候选”放在一个只读视图中。
 * 对商业化交付来说，这比单纯暴露 `/actuator/health` 更接近完整可观测闭环：
 * 运维人员不只知道服务挂了，还能知道规则是否覆盖、严重级别是什么、应该由谁处理。</p>
 *
 * @param schemaVersion 响应契约版本。
 * @param includeMetricsProbe 本次是否把 metrics 探针纳入告警候选评估。
 * @param deployableRuntimeCount 需要独立探活的运行时数量。
 * @param coveredRuntimeCount 已经拥有默认基础告警规则覆盖的运行时数量。
 * @param missingRuntimeCount 未被基础规则覆盖的运行时数量。
 * @param totalRuleCount 规则总数。
 * @param enabledRuleCount 默认启用的规则数量。
 * @param activeCandidateCount 当前健康快照推导出的告警候选数量。
 * @param ruleCountsByModule 按模块统计规则数量。
 * @param rules 默认基础规则目录。
 * @param activeCandidates 当前快照下的低敏告警候选。
 * @param nextActions 面向收敛验收和运维排障的下一步建议。
 * @param generatedAt 响应生成时间。
 */
public record PlatformAlertCoverageResponse(
        String schemaVersion,
        boolean includeMetricsProbe,
        int deployableRuntimeCount,
        int coveredRuntimeCount,
        int missingRuntimeCount,
        int totalRuleCount,
        int enabledRuleCount,
        int activeCandidateCount,
        Map<String, Long> ruleCountsByModule,
        List<PlatformAlertRuleView> rules,
        List<PlatformAlertCandidateView> activeCandidates,
        List<String> nextActions,
        LocalDateTime generatedAt) {
}
