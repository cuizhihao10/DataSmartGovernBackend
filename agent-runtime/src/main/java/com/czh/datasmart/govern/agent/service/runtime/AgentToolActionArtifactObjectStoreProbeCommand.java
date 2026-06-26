/**
 * @Author : Cui
 * @Date: 2026/06/26 23:13
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreProbeCommand.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * 对象存储 adapter 的内部探针命令。
 *
 * <p>它和 HTTP DTO 分开，是为了明确边界：Controller DTO 描述调用方输入，Service 会先完成 grant 复核、数据范围收口、
 * 探针字节数裁剪和低敏字段归一化，再把一个更小、更可信的命令交给 adapter。adapter 不应再相信原始请求体，
 * 也不应通过该命令拿到未授权扩大的租户、项目或 actor 范围。</p>
 *
 * @param commandId 被探针动作绑定的命令 ID。
 * @param artifactReference 低敏 artifact 引用，真实 bucket/key 解析只能在 adapter 内部按服务端配置完成。
 * @param artifactReferenceType 低敏引用类型，例如 MINIO_OBJECT。
 * @param readPurpose 已归一化的读取目的。
 * @param requestedContentMode 已归一化的读取形态。
 * @param maxProbeBytes 本次 adapter 最多允许读取的 sample 字节数。
 * @param tenantId grant 复核后确认的租户 ID。
 * @param projectId grant 复核后确认的项目 ID。
 * @param actorId grant 复核后确认的 actor ID。
 * @param runId grant 复核后确认的 runId。
 * @param sessionId grant 复核后确认的 sessionId。
 * @param toolCode grant 复核后确认的工具编码。
 */
public record AgentToolActionArtifactObjectStoreProbeCommand(
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String readPurpose,
        String requestedContentMode,
        int maxProbeBytes,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode
) {
}
