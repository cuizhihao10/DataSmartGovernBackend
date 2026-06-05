/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentMcpListedToolView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.Map;

/**
 * MCP tools/list 中的单个工具条目。
 *
 * <p>该视图尽量贴近 MCP Tool 对象：名称、标题、描述、输入 schema、注解和执行语义。
 * 需要特别注意：这里不输出 DataSmart 内部 `targetEndpoint`、`targetService`、工具参数示例值、
 * 某次执行参数、SQL、资源正文或模型输出。外部 Agent 发现工具时只需要知道工具能力和输入结构，
 * 真实调用必须重新经过 DataSmart 的权限与审计链路。</p>
 *
 * @param name MCP 工具名。使用 DataSmart toolCode，但会经过字符安全化，避免不符合 MCP 命名建议
 * @param title 面向用户展示的工具标题
 * @param description 低敏工具描述，已过滤 URL、密钥提示和高风险查询样例
 * @param inputSchema JSON Schema 风格输入结构，只描述字段名/类型/是否必填，不带真实示例值
 * @param annotations 工具治理注解，例如只读、幂等、风险、审批、租户/项目边界
 * @param execution MCP task-augmented execution 摘要，当前主要输出 taskSupport
 */
public record AgentMcpListedToolView(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> annotations,
        Map<String, Object> execution
) {
}
