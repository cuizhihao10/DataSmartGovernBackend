/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 工具执行事件 outbox 查询响应。
 *
 * <p>records 是已按 runId/status/limit 过滤后的记录视图；count 是本次返回数量。
 * 当前响应不做分页游标，是因为 outbox 仍是内存热窗口。后续数据库化后，应扩展 cursor、createdAfter、
 * status 分组统计和按租户/项目的数据范围过滤。</p>
 */
public record AgentToolExecutionEventOutboxQueryResponse(
        String runId,
        String status,
        int limit,
        int count,
        List<AgentToolExecutionEventOutboxRecordView> records
) {
}
