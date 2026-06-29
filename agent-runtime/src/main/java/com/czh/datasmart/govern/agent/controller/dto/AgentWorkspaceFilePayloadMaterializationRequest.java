/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFilePayloadMaterializationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Workspace 文件工具 payload 物化请求。
 *
 * <p>该 DTO 只用于内部控制面路由，不面向普通浏览器或第三方客户端开放。请求中可能包含真实
 * relativePath、写入 content 或 contentReference，因此它的职责和普通低敏查询 DTO 不同：
 * Controller 必须先用 gateway/permission-admin 透传 Header 收口 tenant/project/actor，再交给
 * service 写入服务端 `agent-payload:` store。任何响应、事件、projection 和日志都不能回显这些字段值。</p>
 *
 * <p>为什么不让调用方直接把参数塞进 command outbox：outbox 是可重试、可补偿、可排障的持久化事实，
 * 一旦写入真实路径或正文，后续管理台、日志、审计导出和死信处理都可能变成敏感数据泄露面。
 * 本请求的存在意义就是把“真实执行参数”留在受控 store，把 outbox 和事件中的字段降级为引用与摘要。</p>
 *
 * @param payloadReference 可选 `agent-payload:` 引用；为空时服务端根据 runId/payloadKey 构造。
 * @param runId Agent run ID，payloadReference 中的 runId 必须与它一致。
 * @param payloadKey run 内部的载荷键；为空时服务端会用操作和路径摘要生成低敏键。
 * @param tenantId 请求体中的租户 ID，只能用于缩小范围，不能覆盖可信 Header。
 * @param projectId 请求体中的项目 ID，PROJECT 数据范围下必须落在授权项目集合内。
 * @param actorId 请求体中的 actor ID，只能用于缩小范围，不能覆盖可信 Header。
 * @param toolName 工具编码，只允许 `workspace.file.read` 或 `workspace.file.write`。
 * @param operation READ/WRITE；为空时可由 toolName 推导。
 * @param graphId 来源执行图 ID，用于后续 worker、审计和执行图回放串联。
 * @param contractId durable action contract ID，用于把参数与 outbox 契约绑定。
 * @param relativePath workspace 内相对路径；只进入服务端内部 store，不会回显。
 * @param content 写入正文；只进入服务端内部 store，不会出现在响应或事件里。
 * @param contentReference 写入正文引用；响应只返回是否提供，不回显引用值。
 * @param overwrite 是否允许覆盖；真实 worker 仍需做版本/哈希冲突检查。
 * @param expectedSha256 乐观并发校验摘要；响应只返回是否提供，不回显值。
 * @param maxInlineContentBytes 单次内联写入大小上限，未提供时使用 service 默认值。
 * @param ttlSeconds payload store TTL 秒数，未提供时使用 service 默认值。
 */
public record AgentWorkspaceFilePayloadMaterializationRequest(
        String payloadReference,
        String runId,
        String payloadKey,
        String tenantId,
        String projectId,
        String actorId,
        String toolName,
        String operation,
        String graphId,
        String contractId,
        String relativePath,
        String content,
        String contentReference,
        Boolean overwrite,
        String expectedSha256,
        Integer maxInlineContentBytes,
        Long ttlSeconds
) {
}
