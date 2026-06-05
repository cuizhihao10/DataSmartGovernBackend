/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentModelGatewayRouteScoringView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 模型网关候选路由评分摘要视图。
 *
 * <p>Python Runtime 的 `MODEL_GATEWAY_ROUTED` 事件中会携带 `routeScoring`，用于解释候选 Provider
 * 为什么按某个顺序排序。Java 控制面不应该把该 Map 原样返回给前端，而是裁剪成稳定、低敏字段：</p>
 *
 * <p>1. 保留 provider/model/health/cache/priority 这类控制面事实；</p>
 * <p>2. 不返回 prompt、messages、工具参数、URL、API Key、真实 cache key、isolationKey 或模型输出；</p>
 * <p>3. 不返回内部 sortKey 明细，避免前端绑定评分算法内部实现。</p>
 *
 * @param providerName Provider 逻辑名称，用于解释 fallback 和候选排序，不是 URL 或密钥。
 * @param modelName 模型逻辑名称，帮助审计确认本轮路由选项。
 * @param healthStatus 候选 Provider 当时的健康状态，例如 healthy/degraded/unavailable/unknown。
 * @param latencyTier 候选路由配置的延迟等级。
 * @param cacheScope 候选路由可使用的缓存隔离范围。
 * @param cachePlanEnabled 该候选是否可以启用 cache plan。
 * @param cacheIssueCount cache plan 被禁用或降级的原因数量，只返回数量不返回真实 cache key。
 * @param priority 配置优先级，用于解释 health/cache 相同情况下的排序。
 */
public record AgentModelGatewayRouteScoringView(
        String providerName,
        String modelName,
        String healthStatus,
        String latencyTier,
        String cacheScope,
        Boolean cachePlanEnabled,
        Integer cacheIssueCount,
        Integer priority
) {
}
