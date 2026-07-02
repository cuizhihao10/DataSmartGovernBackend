/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmart Govern Backend - AgentExecutionSessionProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 受控多 Agent 执行会话 runtime event 的 Java 控制面视图。
 *
 * <p>该视图固定来自 `agent_execution_session_recorded` 事件。它服务的是 Agent 运行详情页、审计回放、
 * 控制面恢复和后续真实 runner 的前置查询，而不是重新执行 Python 里的多 Agent 规划逻辑。</p>
 *
 * <p>字段设计分为四组：</p>
 * <p>1. 投影元信息：identityKey、schemaVersion、tenant/project/run/session、sequence/replaySequence；</p>
 * <p>2. 会话状态：status、durablePhase、executionPlanStatus、source；</p>
 * <p>3. Agent 覆盖：active/standby/deferred roster，以及 work item 状态/层级/恢复动作分布；</p>
 * <p>4. 安全边界：Python 是否执行工具、是否写 outbox、是否创建审批、是否需要 Java 控制面等。</p>
 *
 * <p>安全边界：本 DTO 不返回 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint、memoryId、
 * memory namespace 或异常堆栈。它只表达“执行会话可恢复状态”，不表达“工具执行结果正文”。</p>
 */
public record AgentExecutionSessionProjectionView(
        String identityKey,
        String schemaVersion,
        String source,
        String eventType,
        String severity,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        Long sequence,
        Long replaySequence,
        Instant createdAt,
        Instant consumedAt,
        String eventPayloadVersion,
        String snapshotType,
        Boolean available,
        String status,
        String durablePhase,
        String durableResumeAction,
        String executionPlanStatus,
        String executionSessionSource,
        Integer workItemCount,
        List<String> activeRoles,
        Integer activeRolesTruncatedCount,
        List<AgentExecutionSessionWorkItemProjectionView> workItems,
        Integer workItemsTruncatedCount,
        Map<String, Integer> workItemStatusCounts,
        Map<String, Integer> deliveryTierCounts,
        Map<String, Integer> resumeActionCounts,
        Map<String, Integer> sourceStatusCounts,
        Integer handoffRequiredWorkItemCount,
        List<String> waitingReasonCodes,
        List<String> blockedByCodes,
        List<String> activeMustDoRoles,
        List<String> standbyMustDoRoles,
        List<String> activeControlledScopeRoles,
        List<String> standbyControlledScopeRoles,
        List<String> deferredLightweightRoles,
        Integer activeRoleCount,
        Integer mustDoRoleCount,
        Integer activeMustDoRoleCount,
        String coveragePolicy,
        Integer collaborationEdgeCount,
        Integer handoffContractCount,
        List<String> nextActions,
        Boolean toolExecutedByPython,
        Boolean modelCalledByExecutionSession,
        Boolean outboxWrittenByPython,
        Boolean approvalCreatedByPython,
        Boolean workerDispatchedByPython,
        Boolean checkpointMutatedByExecutionSession,
        Boolean javaControlPlaneRequiredForSideEffects,
        Boolean workerReceiptRequiredForSideEffects,
        String executionBoundary,
        String payloadPolicy
) {
}
