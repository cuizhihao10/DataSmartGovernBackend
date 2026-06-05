/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentMcpPromptPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * MCP Prompt 映射预览。
 *
 * <p>MCP Prompt 是“用户可选择的提示模板”。它和 Tool 不同：Tool 偏模型控制，Prompt 偏用户控制。
 * 对 DataSmart 来说，Prompt 可以承载治理任务规划、质量规则设计、数据源元数据总结等工作流入口。
 * 但 prompt 正文非常敏感，可能包含系统策略、业务口径、合规模板或越权防护说明，因此 preview
 * 只暴露 prompt 名称、标题、用途和参数名，不返回 messages/text 内容。</p>
 *
 * @param name MCP prompt name，后续 prompts/list 可复用该标识
 * @param title 人类可读标题
 * @param description Prompt 业务用途说明，不包含系统提示词正文
 * @param argumentNames 参数名摘要，用于说明调用该模板前需要哪些低敏上下文字段
 * @param userControlled 是否用户显式选择。MCP Prompt 的推荐使用方式通常是 user-controlled
 * @param bodyDisclosurePolicy 正文暴露策略，明确 preview 不返回 prompt messages/text
 */
public record AgentMcpPromptPreviewView(
        String name,
        String title,
        String description,
        List<String> argumentNames,
        Boolean userControlled,
        String bodyDisclosurePolicy
) {
}
