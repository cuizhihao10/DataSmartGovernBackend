/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionDryRunResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Run 级 DAG-aware 执行干运行响应。
 *
 * <p>响应描述的是“一次拟执行批次”的结果，而不是工具真实执行结果。它会告诉调用方：
 * 哪些节点会进入同步 {@code auto-execute-sync dryRun=true} 二次确认，哪些节点会进入异步 outbox enqueue 预案，
 * 哪些节点被 preview 阻断，哪些请求目标不存在，哪些因为本次批量上限被暂缓。</p>
 *
 * <p>为什么要把统计字段放在 Run 级，而不是让调用方自己遍历 items 计算？
 * 真实商业产品里，前端面板、审计报表、Agent loop 策略和运维告警往往需要直接读取摘要。
 * 服务端输出稳定摘要，可以减少多端重复实现，也能保证“可执行候选数”“阻断数”“未命中数”的口径一致。</p>
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param dryRunOnly 恒为 true，强调当前接口只做预演，不产生副作用。
 * @param requestedNodeIds 调用方提交的节点 ID 选择器，已做去空和去重处理。
 * @param requestedAuditIds 调用方提交的审计 ID 选择器，已做去空和去重处理。
 * @param requestedMaxNodes 调用方请求的批量上限，可能为空。
 * @param effectiveMaxNodes 服务端最终采用的批量上限，会受默认值和硬上限保护。
 * @param selectionFingerprint 服务端根据会话、Run、选择器和节点安全结论生成的稳定指纹；真实入箱时必须原样带回。
 * @param selectedCount 实际进入本次 dry-run 有效处理范围的节点数，不包含超过批量上限的节点。
 * @param syncDryRunCandidateCount 将进入同步自动执行 dry-run 二次确认的节点数。
 * @param asyncEnqueuePreviewCount 将进入异步 outbox enqueue 预案的节点数。
 * @param blockedCount 被 preview、权限、依赖、参数或审批状态阻断的节点数。
 * @param notSelectedCount preview 中存在但本次没有选中的节点数。
 * @param notFoundCount 调用方显式请求但 preview 中没有找到的 nodeId/auditId 数量。
 * @param batchLimitReachedCount 因本次 maxNodes 限制而未纳入有效 dry-run 批次的节点数。
 * @param summaryReasons Run 级解释，适合前端顶部提示或 Agent loop 策略日志。
 * @param recommendedActions Run 级下一步建议，说明如何从 dry-run 进入受控执行。
 * @param items 节点级 dry-run 明细。
 */
public record AgentRunToolDagExecutionDryRunResponse(
        String sessionId,
        String runId,
        Boolean dryRunOnly,
        List<String> requestedNodeIds,
        List<String> requestedAuditIds,
        Integer requestedMaxNodes,
        Integer effectiveMaxNodes,
        String selectionFingerprint,
        Integer selectedCount,
        Integer syncDryRunCandidateCount,
        Integer asyncEnqueuePreviewCount,
        Integer blockedCount,
        Integer notSelectedCount,
        Integer notFoundCount,
        Integer batchLimitReachedCount,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentToolDagExecutionDryRunItemView> items
) {
}
