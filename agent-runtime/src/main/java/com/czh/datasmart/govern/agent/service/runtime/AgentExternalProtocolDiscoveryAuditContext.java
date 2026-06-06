/**
 * @Author : Cui
 * @Date: 2026/06/06 02:37
 * @Description DataSmart Govern Backend - AgentExternalProtocolDiscoveryAuditContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * 外部协议发现事件的审计上下文。
 *
 * <p>MCP tools/list 与 A2A Agent Card 都属于“发现行为”，它们通常没有 sessionId、runId 或 tool auditId。
 * 如果强行伪造会话，会让 timeline 误以为发生了一次 Agent 运行；如果完全不记录上下文，又无法回答：
 * 是哪个租户、哪个操作者、哪个入口读取了能力目录。因此这里单独定义轻量上下文，专门承接 gateway 透传的
 * trace、tenant、actor、workspace 和请求来源。</p>
 *
 * <p>字段安全说明：
 * 该上下文不承载 URL、token、cookie、authorization、工具参数、Prompt、资源正文或模型输出。
 * 即使未来 public Agent Card 被公网扫描器读取，本上下文也只会在 header 存在时记录低敏来源摘要。</p>
 *
 * @param traceId 当前请求链路 ID，用于把 HTTP 访问与 runtime event 串起来
 * @param tenantId 当前租户 ID，来自受信 gateway header；公开匿名访问时可为空
 * @param workspaceId 当前 workspace/project 近似上下文。当前项目 Header 叫 workspace，事件投影字段叫 projectId
 * @param actorId 操作者 ID，公开匿名访问时可为空
 * @param actorRole 操作者角色，作为低敏审计维度
 * @param requestSource 请求来源，例如 WEB_CONSOLE、PYTHON_RUNTIME、EXTERNAL_AGENT、PUBLIC_WELL_KNOWN
 * @param sourceService 上游服务名，主要用于区分 gateway、Python Runtime 或内部管理任务
 */
public record AgentExternalProtocolDiscoveryAuditContext(
        String traceId,
        String tenantId,
        String workspaceId,
        String actorId,
        String actorRole,
        String requestSource,
        String sourceService
) {
}
