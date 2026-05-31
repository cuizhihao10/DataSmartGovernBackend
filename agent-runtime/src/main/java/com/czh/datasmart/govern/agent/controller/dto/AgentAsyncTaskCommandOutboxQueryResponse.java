/**
 * @Author : Cui
 * @Date: 2026/05/31 17:17
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 异步命令 outbox 查询响应。
 *
 * @param runId 可选 runId 过滤条件。
 * @param status 可选状态过滤条件。
 * @param limit 本次查询上限。
 * @param count 返回记录数量。
 * @param items 记录列表。
 */
public record AgentAsyncTaskCommandOutboxQueryResponse(
        String runId,
        String status,
        Integer limit,
        Integer count,
        List<AgentAsyncTaskCommandOutboxRecordView> items
) {
}
