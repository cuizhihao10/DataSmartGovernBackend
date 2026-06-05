/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagExecutionBridgePreviewResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Master Agent handoff DAG 执行桥接预检响应。
 *
 * <p>该响应把“handoff DAG 上选中了哪个控制面节点”和“现有 Tool DAG dry-run 会如何解释工具节点”
 * 放在同一个低敏结果里。它面向前端执行面板、Agent Host 策略层和审计排障场景：调用方可以先展示
 * 本次 handoff 是否能进入工具控制、哪些工具会进入异步 outbox 预案、如果要继续确认应携带什么指纹和策略版本。</p>
 *
 * <p>安全边界：响应中的 requestTemplate 只包含 nodeId/auditId、selectionFingerprint、policyVersion 和
 * confirmed=false。它不包含 targetEndpoint、Kafka topic、工具参数、SQL、prompt 或样本数据；真实入箱仍必须
 * 调用 selected-node outbox endpoint，并由服务端重新 dry-run、重新校验指纹和策略版本。</p>
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param bridgeReady 当前 handoff 选择是否已经成功映射到工具 dry-run。
 * @param bridgeAction 桥接动作分类，例如 TOOL_CONTROL_DRY_RUN、HANDOFF_NODE_NOT_EXECUTABLE、NO_TOOL_CANDIDATE。
 * @param handoffNodeIds 本次有效 handoff 节点选择。
 * @param mappedToolNodeIds 传递给工具 dry-run 的 nodeId 选择器。
 * @param mappedToolAuditIds 传递给工具 dry-run 的 auditId 选择器。
 * @param dryRun 现有 Tool DAG dry-run 响应，仍然是本响应的核心执行预案事实来源。
 * @param selectedNodeOutboxRequestTemplate 可选的下一步 selected-node outbox 请求模板；模板默认 confirmed=false。
 * @param summaryReasons 桥接级解释。
 * @param recommendedActions 下一步推荐动作。
 */
public record AgentSessionHandoffDagExecutionBridgePreviewResponse(
        String sessionId,
        String runId,
        Boolean bridgeReady,
        String bridgeAction,
        List<String> handoffNodeIds,
        List<String> mappedToolNodeIds,
        List<String> mappedToolAuditIds,
        AgentRunToolDagExecutionDryRunResponse dryRun,
        Map<String, Object> selectedNodeOutboxRequestTemplate,
        List<String> summaryReasons,
        List<String> recommendedActions
) {
}
