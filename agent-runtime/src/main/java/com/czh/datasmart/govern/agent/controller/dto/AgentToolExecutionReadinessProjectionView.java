/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionReadinessProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工具执行准备度 runtime event 的 Java 控制面视图。
 *
 * <p>Python Runtime 负责在 `/agent/plans` 中生成 `tool_execution_readiness_recorded` 事件；Java
 * agent-runtime 的职责不是重新判断工具是否可执行，而是把这条已经发生的执行前治理事实，转换为
 * 前端、审计台、WebSocket replay 和运营报表可以稳定消费的强类型视图。</p>
 *
 * <p>为什么需要专用视图：通用 runtime event attributes 是跨语言自由 Map，字段类型和默认值都不适合
 * 直接暴露给前端。服务层在这里做类型兜底、字段白名单和聚合，后续如果迁移到 MySQL/ClickHouse
 * dedicated index，也可以保持 API 契约不变。</p>
 *
 * <p>安全边界：本视图只返回低敏控制面事实，包括工具名、决策分布、风险分布、issue/reason code 数量
 * 和决策摘要。它不会返回参数真实值、SQL、prompt、样本数据、payload 明细、模型输出、凭证或内部端点。</p>
 */
public record AgentToolExecutionReadinessProjectionView(
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
        String payloadPolicy,
        Integer totalCount,
        Integer executableCount,
        Integer approvalRequiredCount,
        Integer clarificationRequiredCount,
        Integer draftOnlyCount,
        Integer queuedAsyncCount,
        Integer throttledCount,
        Integer blockedCount,
        Boolean hasBlockingDecision,
        List<String> nextActions,
        Map<String, Integer> decisionCounts,
        Map<String, Integer> riskLevelCounts,
        Map<String, Integer> executionModeCounts,
        List<String> toolNames,
        Integer toolNamesTruncatedCount,
        List<AgentToolExecutionReadinessDecisionSummaryView> decisionSummaries
) {
}
