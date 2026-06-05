/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentModelGatewayRoutingProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 模型网关路由 runtime event 的 Java 控制面视图。
 *
 * <p>Python Runtime 会把每次模型路由决策写成 `model_gateway_routed` 事件。该 DTO 的职责是把自由
 * attributes 转成稳定字段，让前端、审计台和运维台不用直接解析跨语言 Map。</p>
 *
 * <p>本视图只表达“路由治理事实”：选中了哪个 Provider/Model、是否 fallback、预算是否允许、缓存计划
 * 是否启用、候选评分摘要是什么。它不表达用户 prompt，也不表达模型请求正文、工具参数、SQL、URL、
 * API Key、模型响应或真实 KV cache 内容。</p>
 *
 * <p>为什么需要专用视图：模型网关是商业化 Agent 平台的关键控制点。用户看到“回答变慢/模型切换/能力降级”
 * 时，管理台需要解释原因；但如果前端直接读 attributes，事件版本升级、字段空值和脱敏规则都会变成前端负担。
 * Java 控制面在这里做类型兜底和字段裁剪，后续切 MySQL/ClickHouse 索引时也能保持 API 不变。</p>
 */
public record AgentModelGatewayRoutingProjectionView(
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
        /**
         * Python 侧模型网关事件 payload 版本，例如 `datasmart.ai-runtime.model-gateway-routed.v2`。
         */
        String routingSchemaVersion,
        String eventPayloadPolicy,
        String selectedProvider,
        String selectedModel,
        String selectedHealthStatus,
        String configuredPrimaryProvider,
        List<String> orderedCandidateProviders,
        Integer candidateCount,
        Boolean fallbackUsed,
        Boolean budgetAllowed,
        Boolean budgetWarning,
        Boolean cacheAwareRouting,
        String cacheKeyScope,
        Boolean cachePlanEnabled,
        String cachePlanScope,
        /**
         * 只说明 namespace 是否存在，不返回 namespace 原文，避免把租户/项目/cache key 结构扩散到列表页。
         */
        Boolean cachePlanNamespacePresent,
        Integer cachePlanTtlSeconds,
        List<String> cachePlanIssues,
        Integer cachePlanIssueCount,
        Integer routeScoringCount,
        Boolean routeScoringTruncated,
        List<AgentModelGatewayRouteScoringView> routeScoring
) {
}
