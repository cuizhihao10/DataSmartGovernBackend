/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentMcpAdapterPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * MCP Adapter 预览总览。
 *
 * <p>这个对象把 DataSmart 内部工具、资源和提示模板映射成 MCP 的三类核心 Server Feature：
 * tools、resources、prompts。它是“协议投影”，不是“协议服务”。也就是说，调用方可以通过它理解
 * DataSmart 将来会怎样暴露 MCP 能力，但不能据此直接发起 tools/call 或 resources/read。</p>
 *
 * @param schemaVersion DataSmart MCP adapter preview schema 版本，便于后续字段演进
 * @param protocolVersion 当前参考的 MCP 规范版本
 * @param serverName 未来 MCP Server 的逻辑服务名，不代表真实网络服务已启动
 * @param capabilities MCP capability 摘要，例如 tools/resources/prompts 是否计划支持
 * @param listMethods MCP list 方法名摘要，帮助学习和前端说明协议入口
 * @param toolCount 映射出的 MCP tool 数量
 * @param resourceCount 映射出的 MCP resource 数量
 * @param promptCount 映射出的 MCP prompt 数量
 * @param tools 工具映射预览，只包含低敏元数据
 * @param resources 资源目录预览，不包含资源正文
 * @param prompts Prompt 模板目录预览，不包含 prompt messages/text
 * @param compatibilityNotes 与真实 MCP Server 之间仍需补齐的能力说明
 */
public record AgentMcpAdapterPreviewView(
        String schemaVersion,
        String protocolVersion,
        String serverName,
        Map<String, Boolean> capabilities,
        Map<String, String> listMethods,
        int toolCount,
        int resourceCount,
        int promptCount,
        List<AgentMcpToolPreviewView> tools,
        List<AgentMcpResourcePreviewView> resources,
        List<AgentMcpPromptPreviewView> prompts,
        List<String> compatibilityNotes
) {
}
