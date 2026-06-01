/**
 * @Author : Cui
 * @Date: 2026/06/01 22:19
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * DAG selected-node 确认记录列表查询响应。
 *
 * <p>当前第一版采用 limit + totalMatched 的轻量响应形式，避免在尚未确定审计中心持久化方案前过早绑定分页游标。
 * 后续如果确认记录进入 MySQL/ClickHouse/审计中心，可以在不破坏 {@code confirmations} 语义的前提下继续增加
 * {@code nextCursor}、时间范围、排序方向、导出任务 ID 等字段。</p>
 *
 * @param limit 本次服务端接受的最大返回条数，防止审计查询被误用为全量导出
 * @param totalMatched 本次已返回的确认记录数量；当前不是全库总数
 * @param confirmations 安全脱敏后的确认记录视图
 */
public record AgentRunToolDagConfirmationQueryResponse(
        int limit,
        int totalMatched,
        List<AgentRunToolDagConfirmationView> confirmations
) {
}
