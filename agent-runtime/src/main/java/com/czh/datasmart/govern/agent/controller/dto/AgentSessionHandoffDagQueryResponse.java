/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Master Agent 交接 DAG 查询响应。
 *
 * <p>响应中同时返回列表和聚合计数，是为了让管理台可以先展示概览卡片，再展开单次会话图。
 * 当前聚合只覆盖本次查询窗口，不代表全量历史报表；后续如果建立 dedicated index，可以保持
 * API 字段不变，把统计下推到 MySQL、ClickHouse 或审计仓库。</p>
 *
 * @param limit 服务端实际采用的查询窗口上限。
 * @param totalMatched 本次命中的 DAG 数量。
 * @param indexSource 数据来源说明，当前复用 runtime-event-projection-fallback。
 * @param readyCount READY DAG 数量。
 * @param degradedCount DEGRADED DAG 数量。
 * @param approvalRequiredCount APPROVAL_REQUIRED DAG 数量。
 * @param blockedCount BLOCKED DAG 数量。
 * @param executableCount 可进入真实执行候选的 DAG 数量。
 * @param handoffRequiredCount 需要 handoff 的 DAG 数量。
 * @param dags DAG 明细列表。
 */
public record AgentSessionHandoffDagQueryResponse(
        Integer limit,
        Integer totalMatched,
        String indexSource,
        Long readyCount,
        Long degradedCount,
        Long approvalRequiredCount,
        Long blockedCount,
        Long executableCount,
        Long handoffRequiredCount,
        List<AgentSessionHandoffDagView> dags
) {
}
