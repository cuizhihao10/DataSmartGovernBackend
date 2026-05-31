/**
 * @Author : Cui
 * @Date: 2026/06/01 10:32
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectedNodeOutboxEnqueueResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * DAG 选中节点异步 outbox 确认入箱响应。
 *
 * <p>响应同时返回服务端重新计算的 dry-run 和最终 outbox 入箱结果。这样前端审批面板、Python Runtime
 * 和审计台能够完整回答三个问题：确认的是哪一版预案、哪些节点被允许入箱、是否因为重试而复用了已有命令。</p>
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param selectionFingerprint 服务端在确认瞬间重新计算的选择指纹。
 * @param confirmed 恒为 true，表示已经通过显式确认检查。
 * @param dryRun 确认瞬间重新生成的无副作用预案。
 * @param outbox 指定异步候选的 outbox 入箱结果。
 * @param summaryReasons 本次确认入箱的服务端解释。
 * @param recommendedActions 后续 dispatcher、worker 与生产治理建议。
 */
public record AgentRunToolDagSelectedNodeOutboxEnqueueResponse(
        String sessionId,
        String runId,
        String selectionFingerprint,
        String confirmationId,
        Instant confirmationExpiresAt,
        List<String> selectedAuditIds,
        Boolean confirmed,
        AgentRunToolDagExecutionDryRunResponse dryRun,
        AgentRunAsyncTaskCommandOutboxEnqueueResponse outbox,
        List<String> summaryReasons,
        List<String> recommendedActions
) {
}
