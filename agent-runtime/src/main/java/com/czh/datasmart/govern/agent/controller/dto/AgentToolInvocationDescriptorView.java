/**
 * @Author : Cui
 * @Date: 2026/05/23 20:31
 * @Description DataSmart Govern Backend - AgentToolInvocationDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 工具调用描述。
 *
 * <p>该视图只描述“工具应该如何被调用”，不直接暴露权限判断结果。
 * 这样 Python Runtime、智能网关或未来 MCP 适配层可以先根据调用描述生成计划，
 * 再把计划交给 Java Agent Runtime 做审批、审计和真正执行。
 *
 * @param targetService 下游服务名，例如 datasource-management、data-quality、task-management
 * @param targetEndpoint 下游端点模板，允许包含路径变量，例如 `/datasources/{datasourceId}/metadata/discover`
 * @param executionMode 执行模式，例如 SYNC、ASYNC_TASK、DRAFT_ONLY、APPROVAL_REQUIRED
 * @param timeoutMs 工具调用超时，用于规划器判断该工具是否适合在同步交互中使用
 * @param maxRetries 最大重试次数；非幂等工具通常应保持 0
 * @param idempotent 是否幂等，影响重试、恢复和失败补偿策略
 */
public record AgentToolInvocationDescriptorView(String targetService,
                                                String targetEndpoint,
                                                String executionMode,
                                                Long timeoutMs,
                                                Integer maxRetries,
                                                Boolean idempotent) {
}
