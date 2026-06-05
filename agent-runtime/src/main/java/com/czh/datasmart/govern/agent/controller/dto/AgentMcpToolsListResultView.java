/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentMcpToolsListResultView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * MCP tools/list 的 result 部分。
 *
 * <p>MCP 规范中的 `tools/list` 支持分页，本视图保留 `nextCursor`，并额外提供 total/returned/limit
 * 这些低敏诊断字段。真实 MCP JSON-RPC Server 可只保留标准字段；当前 DataSmart 控制面为了学习和排障，
 * 保留更多解释信息，方便确认“为什么这一页只返回这些工具”。</p>
 *
 * @param tools 当前页工具列表
 * @param cursor 本次请求使用的 cursor。为空表示第一页
 * @param nextCursor 下一页 cursor；为空表示没有更多工具
 * @param limit 本次分页大小，已经过服务端最大值保护
 * @param returnedCount 当前页返回数量
 * @param totalCount 过滤后可发现工具总量
 */
public record AgentMcpToolsListResultView(
        List<AgentMcpListedToolView> tools,
        String cursor,
        String nextCursor,
        int limit,
        int returnedCount,
        int totalCount
) {
}
