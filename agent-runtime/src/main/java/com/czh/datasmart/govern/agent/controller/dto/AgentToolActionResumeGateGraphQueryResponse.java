/**
 * @Author : Cui
 * @Date: 2026/06/18 01:39
 * @Description DataSmart Govern Backend - AgentToolActionResumeGateGraphQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具动作恢复门控图查询响应。
 *
 * <p>该响应是恢复事实包的图形化上层视图：事实包回答“哪些事实可用/缺失/拒绝”，恢复门控图进一步回答
 * “这些事实在 resume 流程中分别卡在哪个条件节点”。这能帮助后续实现更像 Codex、Claude Code、OpenAI Agents
 * 或 LangGraph 的 Agent Host：运行时可以暂停、收集事实、回查控制面、再以明确条件恢复，而不是依赖用户请求里自报的字段。</p>
 *
 * @param schemaVersion 响应契约版本。
 * @param previewOnly true 表示本接口只查询并解释恢复门控，不执行工具。
 * @param queryBoundary 查询边界说明，强调这是低敏控制面预览。
 * @param payloadPolicy 敏感信息策略。
 * @param graphState 整张图状态。
 * @param terminalState 当前等待态、拒绝态或可恢复预览态。
 * @param resumePreviewReady 是否满足恢复预览条件。
 * @param requiredFactCount 本次要求检查的事实类型数量。
 * @param availableFactCount 已采信事实类型数量。
 * @param missingFactCount 缺失事实类型数量。
 * @param rejectedFactCount 被拒绝事实类型数量。
 * @param nodeCount 图节点数量。
 * @param edgeCount 图边数量。
 * @param nodeTypeCounts 按节点类型聚合的低基数计数，便于后续 Prometheus/运营台复用。
 * @param nodeStatusCounts 按节点状态聚合的低基数计数，便于观察等待、拒绝和可继续节点分布。
 * @param summaryReasons 中文解释，帮助学习和排障。
 * @param recommendedActions 下一步建议。
 * @param productionReadiness 当前恢复链路距离生产可执行还缺哪些治理能力。
 * @param graph 单张恢复门控图。
 * @param generatedAt 响应生成时间。
 */
public record AgentToolActionResumeGateGraphQueryResponse(
        String schemaVersion,
        Boolean previewOnly,
        String queryBoundary,
        String payloadPolicy,
        String graphState,
        String terminalState,
        Boolean resumePreviewReady,
        Integer requiredFactCount,
        Integer availableFactCount,
        Integer missingFactCount,
        Integer rejectedFactCount,
        Integer nodeCount,
        Integer edgeCount,
        Map<String, Long> nodeTypeCounts,
        Map<String, Long> nodeStatusCounts,
        List<String> summaryReasons,
        List<String> recommendedActions,
        Map<String, Object> productionReadiness,
        AgentToolActionResumeGateGraphView graph,
        Instant generatedAt
) {

    public AgentToolActionResumeGateGraphQueryResponse {
        nodeTypeCounts = nodeTypeCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(nodeTypeCounts));
        nodeStatusCounts = nodeStatusCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(nodeStatusCounts));
        summaryReasons = summaryReasons == null ? List.of() : List.copyOf(summaryReasons);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        productionReadiness = productionReadiness == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(productionReadiness));
    }
}
