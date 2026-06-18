/**
 * @Author : Cui
 * @Date: 2026/06/18 01:39
 * @Description DataSmart Govern Backend - AgentToolActionResumeGateGraphView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具动作恢复门控图。
 *
 * <p>本视图描述的是“暂停后的工具动作是否具备恢复预览条件”，而不是“工具已经被恢复执行”。
 * 它会把 checkpoint/thread 定位、locator index 补全、审批事实、澄清事实、outbox 写入事实、worker receipt
 * 事实和最终 resume gate 串成一张低敏条件图。这样后续 Python Runtime、智能网关、前端确认页和审计台可以用同一张图
 * 理解当前动作为什么能继续、为什么必须等待、或者为什么被 Java 控制面拒绝。</p>
 *
 * <p>安全边界：本图只展示事实类型、状态、低敏证据码和问题码。它不展示 approvalFactId、clarificationFactId、
 * outboxId 原文、payloadReference、payloadJson、targetEndpoint、prompt、SQL、工具 arguments、样本数据、模型输出、
 * 凭证或内部服务响应正文。</p>
 *
 * @param graphId 控制面生成的低敏图 ID，用于前端渲染和排障关联。
 * @param schemaVersion 图契约版本。
 * @param previewOnly true 表示本图只用于恢复预览，不触发真实副作用。
 * @param graphState 整张恢复门控图的当前状态。
 * @param terminalState 当前图停留的等待态、拒绝态或可恢复预览态。
 * @param resumePreviewReady 是否满足“允许 Python/OpenClaw/LangGraph 做 resume-preview”的最低条件。
 * @param payloadPolicy 本图采用的敏感信息策略。
 * @param requestedLocator 低敏定位摘要，只展示定位字段是否存在和安全范围，不展示事实 ID 原文。
 * @param nodeCount 节点数量。
 * @param edgeCount 边数量。
 * @param blockedNodeCount 当前不可继续的节点数量。
 * @param executableNodeCount 当前已满足条件的节点数量。
 * @param requiredFactTypes 本次恢复预览要求检查的事实类型。
 * @param availableFactTypes 已由 Java 控制面采信的事实类型。
 * @param missingFactTypes 缺失或未物化的事实类型。
 * @param rejectedFactTypes 被 Java 控制面明确拒绝的事实类型。
 * @param summaryReasons 面向学习、排障和审计的中文解释。
 * @param recommendedActions 下一步建议，供 Python Runtime、确认页或运营台使用。
 * @param nodes 图节点列表。
 * @param edges 图有向边列表。
 */
public record AgentToolActionResumeGateGraphView(
        String graphId,
        String schemaVersion,
        Boolean previewOnly,
        String graphState,
        String terminalState,
        Boolean resumePreviewReady,
        String payloadPolicy,
        Map<String, Object> requestedLocator,
        Integer nodeCount,
        Integer edgeCount,
        Integer blockedNodeCount,
        Integer executableNodeCount,
        List<String> requiredFactTypes,
        List<String> availableFactTypes,
        List<String> missingFactTypes,
        List<String> rejectedFactTypes,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentToolActionExecutionGraphNodeView> nodes,
        List<AgentToolActionExecutionGraphEdgeView> edges
) {

    public AgentToolActionResumeGateGraphView {
        /*
         * requestedLocator 允许包含 null value，例如调用方没有传 runId/sessionId。
         * JDK 的 Map.copyOf 不允许 null value，因此这里保留 LinkedHashMap 顺序并包装为只读 Map。
         */
        requestedLocator = requestedLocator == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(requestedLocator));
        requiredFactTypes = requiredFactTypes == null ? List.of() : List.copyOf(requiredFactTypes);
        availableFactTypes = availableFactTypes == null ? List.of() : List.copyOf(availableFactTypes);
        missingFactTypes = missingFactTypes == null ? List.of() : List.copyOf(missingFactTypes);
        rejectedFactTypes = rejectedFactTypes == null ? List.of() : List.copyOf(rejectedFactTypes);
        summaryReasons = summaryReasons == null ? List.of() : List.copyOf(summaryReasons);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
