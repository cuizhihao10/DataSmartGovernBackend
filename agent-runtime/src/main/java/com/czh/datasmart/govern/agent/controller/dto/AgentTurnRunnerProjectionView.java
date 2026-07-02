/**
 * @Author : Cui
 * @Date: 2026/07/02 18:08
 * @Description DataSmart Govern Backend - AgentTurnRunnerProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 受控多 Agent Turn Runner runtime event 的强类型投影视图。
 *
 * <p>`agentExecutionSession` 解决“有哪些 Agent work item 与当前请求有关”，`agentTurnRunner` 进一步解决
 * “下一轮 turn 具体能否推进、缺哪些控制面证据、manager-as-tools 是否只是描述而不是执行”。因此本视图
 * 主要服务 Agent 运行详情页、审计回放、预生产验收和后续真实多 Agent runner 的恢复前检查。</p>
 *
 * <p>安全边界非常关键：该视图不触发模型调用、不执行工具、不写 outbox、不创建审批、不派发 worker，也不修改
 * Durable Loop checkpoint。它只是把 Python Runtime 已经产生的低敏事实解释成 Java 管理台可读的结构。</p>
 */
public record AgentTurnRunnerProjectionView(
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
        String turnRunnerSchemaVersion,
        String status,
        String runStatus,
        String sessionStatus,
        String durablePhase,
        Integer currentTurnDepth,
        Integer maxTurnDepth,
        Integer maxConcurrentAgentTurns,
        Integer turnAttemptCount,
        Integer waitingAttemptCount,
        Integer blockedAttemptCount,
        Integer controlPlaneHandoffCount,
        Integer managerAsToolsCount,
        List<AgentTurnRunnerAttemptProjectionView> turnAttempts,
        Integer turnAttemptsTruncatedCount,
        Map<String, Integer> turnStatusCounts,
        Map<String, Integer> deliveryTierCounts,
        Map<String, Integer> resumeActionCounts,
        Map<String, Integer> requiredEvidenceCounts,
        List<String> nextActions,
        Boolean toolExecutedByPython,
        Boolean modelCalledByTurnRunner,
        Boolean outboxWrittenByPython,
        Boolean approvalCreatedByPython,
        Boolean workerDispatchedByPython,
        Boolean javaControlPlaneRequiredForSideEffects,
        Boolean workerReceiptRequiredForSideEffects,
        String executionBoundary,
        String payloadPolicy
) {
}
