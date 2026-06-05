/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentModelGatewayRoutingProjectionQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 模型网关路由投影查询响应。
 *
 * <p>该响应不只返回单条快照列表，还返回本次窗口的低敏聚合摘要。这样管理台可以直接回答：</p>
 * <p>1. 本次窗口内发生了多少次 fallback；</p>
 * <p>2. 有多少次预算不允许；</p>
 * <p>3. cache plan 实际启用了多少次；</p>
 * <p>4. 选中的 Provider 和健康状态分布是什么。</p>
 *
 * <p>当前聚合只基于本次返回窗口，不代表全量历史报表。后续接专用 MySQL/ClickHouse 索引时，可以用
 * 同样字段承载数据库聚合结果。</p>
 */
public record AgentModelGatewayRoutingProjectionQueryResponse(
        Integer limit,
        Integer totalMatched,
        String indexSource,
        Long fallbackUsedCount,
        Long budgetBlockedCount,
        Long cachePlanEnabledCount,
        Long routeScoringTruncatedCount,
        Map<String, Long> selectedProviderCounts,
        Map<String, Long> selectedHealthStatusCounts,
        List<AgentModelGatewayRoutingProjectionView> snapshots
) {
}
