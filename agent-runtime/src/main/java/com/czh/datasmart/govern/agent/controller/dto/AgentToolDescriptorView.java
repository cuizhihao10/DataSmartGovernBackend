/**
 * @Author : Cui
 * @Date: 2026/05/23 20:31
 * @Description DataSmart Govern Backend - AgentToolDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具标准化描述视图。
 *
 * <p>这是 DataSmart 走向 MCP-style 工具治理的第一步。
 * 它不要求当前版本立刻实现完整 MCP Server，而是先把内部工具目录转换成更稳定、更可导出、更容易被 Python Runtime
 * 和智能网关消费的结构化描述。
 *
 * <p>与 `AgentToolDefinitionView` 的区别：
 * - DefinitionView 更适合前端展示和内部管理；
 * - DescriptorView 更适合工具规划、协议导出、跨运行时消费和审计快照。
 *
 * @param schemaVersion DataSmart 工具描述 schema 版本，后续演进时用于兼容判断
 * @param descriptorType 描述类型，当前固定为 DATASMART_AGENT_TOOL
 * @param protocolHint 协议提示，当前为 MCP_STYLE，表示形态接近 MCP 工具描述但不是完整 MCP 协议响应
 * @param toolCode 稳定工具编码
 * @param displayName 展示名称
 * @param description 工具说明，可进入 Agent 工具选择提示
 * @param toolType 工具业务类型
 * @param invocation 调用信息
 * @param governance 治理策略
 * @param memory 记忆与缓存策略
 * @param parameters 参数描述
 */
public record AgentToolDescriptorView(String schemaVersion,
                                      String descriptorType,
                                      String protocolHint,
                                      String toolCode,
                                      String displayName,
                                      String description,
                                      String toolType,
                                      AgentToolInvocationDescriptorView invocation,
                                      AgentToolGovernanceDescriptorView governance,
                                      AgentToolMemoryDescriptorView memory,
                                      List<AgentToolParameterDescriptorView> parameters) {
}
