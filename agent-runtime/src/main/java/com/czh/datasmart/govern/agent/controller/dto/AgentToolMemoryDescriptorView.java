/**
 * @Author : Cui
 * @Date: 2026/05/23 20:31
 * @Description DataSmart Govern Backend - AgentToolMemoryDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 工具记忆与缓存描述。
 *
 * <p>前沿 Agent 系统正在把工具调用结果沉淀到短期/长期记忆，并通过 prefix cache / KV cache 降低长任务成本。
 * 本视图先把“工具是否允许写记忆”和“工具描述/上下文可在哪个范围缓存”纳入契约，
 * 后续 Python Runtime 和模型网关可以基于这些字段做更细的记忆写入、检索和缓存复用。
 *
 * @param memoryWritePolicy 工具执行结果允许写入哪类记忆，例如 NONE、EPISODIC、SEMANTIC、PROCEDURAL
 * @param cachePolicy 工具描述或上下文缓存范围，例如 GLOBAL_SAFE、TENANT_SAFE、PROJECT_SAFE、SESSION_ONLY
 */
public record AgentToolMemoryDescriptorView(String memoryWritePolicy,
                                            String cachePolicy) {
}
