/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentMcpToolsListResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * MCP tools/list 只读发现响应。
 *
 * <p>该响应刻意做成“JSON-RPC 语义 + DataSmart 控制面保护字段”的混合结构：
 * `jsonrpc`、`id`、`method`、`result.tools`、`result.nextCursor` 对齐 MCP `tools/list` 的发现模型；
 * `schemaVersion`、`previewOnly`、`callEnabled`、`payloadPolicy` 用于提醒调用方这仍然是 DataSmart
 * Agent Runtime 的只读协议发现端点，而不是完整 MCP Server。</p>
 *
 * <p>为什么不直接实现完整 MCP JSON-RPC Server：
 * 当前阶段我们只希望外部 Agent 或 Python Runtime 能“发现有哪些工具、schema 大概是什么、哪些工具需要任务化执行”。
 * 真正的 `tools/call` 会涉及权限、审批、工具沙箱、限流、审计、outbox、worker pre-check 和 runtime event。
 * 如果在发现阶段就开放执行，会把商业化系统最重要的治理链路绕开。</p>
 *
 * @param schemaVersion DataSmart MCP tools/list 投影契约版本
 * @param generatedAt 响应生成时间，便于判断前端或运行时缓存是否过期
 * @param protocolVersion 当前参考的 MCP 规范版本
 * @param jsonrpc JSON-RPC 版本，固定为 2.0，方便未来迁移到真正 JSON-RPC handler
 * @param id 请求 ID。GET 场景没有原生 JSON-RPC id，因此由查询参数或默认值生成
 * @param method MCP 方法名，当前固定为 tools/list
 * @param result MCP tools/list 的核心结果，包含工具列表和下一页 cursor
 * @param previewOnly 是否只读预览。true 表示该响应不能被解释为工具执行许可
 * @param callEnabled 是否启用 tools/call。当前固定 false，真实执行必须后续单独接治理链路
 * @param payloadPolicy 载荷策略，说明响应只包含低敏元数据
 * @param disclosureWarnings 对外暴露注意事项，帮助调用方理解哪些字段被刻意隐藏
 * @param recommendedNextSteps 下一步产品化建议
 */
public record AgentMcpToolsListResponse(
        String schemaVersion,
        Instant generatedAt,
        String protocolVersion,
        String jsonrpc,
        String id,
        String method,
        AgentMcpToolsListResultView result,
        boolean previewOnly,
        boolean callEnabled,
        String payloadPolicy,
        List<String> disclosureWarnings,
        List<String> recommendedNextSteps
) {
}
