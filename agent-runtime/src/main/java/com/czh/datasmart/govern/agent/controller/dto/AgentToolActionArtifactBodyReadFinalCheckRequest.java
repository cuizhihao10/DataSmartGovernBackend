/**
 * @Author : Cui
 * @Date: 2026/06/24 20:41
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadFinalCheckRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 命令执行 artifact 正文读取最终回查请求。
 *
 * <p>这个请求位于 artifact 读取链路的第三道门：</p>
 * <ol>
 *     <li>第一道门：`access-authorizations` 校验低敏 artifactReference 是否属于当前可见范围内的 worker receipt；</li>
 *     <li>第二道门：`body-read-grants` 校验读取目的、读取形态、调用组件和最大读取字节数；</li>
 *     <li>第三道门：本请求用于对象存储服务或 artifact 服务在真正返回预览前，再向 Java Host 控制面做最终回查。</li>
 * </ol>
 *
 * <p>当前项目尚未接入真实 MinIO 下载和真实对象存储 adapter，因此这里采用“安全预览候选”模型：
 * 下游对象存储服务未来可以把已经经过对象 ACL、DLP、恶意内容扫描和基础脱敏后的短文本预览传入
 * `sanitizedPreviewText`，Java Host 再根据 grant、字节上限和敏感标记做最后裁剪。这样既能让 API 契约先稳定下来，
 * 又不会把本接口误实现成“任意读对象正文”的快捷通道。</p>
 *
 * @param commandId 命令 outbox 中的稳定命令 ID，用来把本次最终回查绑定到具体副作用动作。
 * @param artifactReference worker receipt 写入的低敏 artifact 引用，不能是 URL、真实路径、bucket/key 或正文片段。
 * @param artifactReferenceType 调用方声明的引用类型，例如 MINIO_OBJECT；服务端最终仍以 receipt 事实为准。
 * @param previousGrantDecisionReference 上一步 body-read grant 返回的低敏审计引用；它不是 bearer token，但必须存在且形态正确。
 * @param readPurpose 正文读取目的，例如 TASK_RESULT_VIEW、AUDIT_REVIEW 或 HUMAN_APPROVAL_REVIEW。
 * @param requestedContentMode 读取形态；只有 TRUNCATED_TEXT_PREVIEW、SAFE_RENDERED_PREVIEW 等安全模式才会返回预览文本。
 * @param maxReadableBytes 上一步授权期望的最大读取字节数，final-check 会复用 grant 服务做当前时刻的二次判断。
 * @param requestedMaxPreviewBytes 本次希望返回的安全预览最大字节数，服务端会继续施加更小的硬上限。
 * @param candidateContentType 对象存储服务识别出的候选内容类型，例如 text/plain；只用于低敏展示与策略判断。
 * @param candidateContentLengthBytes 对象存储侧看到的候选对象大小；用于判断预览是否只是大对象的一小段。
 * @param sanitizedPreviewText 已由下游服务完成基础脱敏的候选预览，不允许包含 prompt、SQL、URL、token、stdout/stderr 等敏感片段。
 * @param tenantId 主动缩小查询范围的租户过滤条件，不能扩大 gateway Header 授权范围。
 * @param projectId 主动缩小查询范围的项目过滤条件，PROJECT 数据范围下必须命中授权项目。
 * @param actorId 主动缩小查询范围的 actor 过滤条件，SELF 范围下仍会被收口为当前 actor。
 * @param runId Agent run 过滤条件，用于绑定到一次具体执行。
 * @param sessionId Agent session 过滤条件，用于绑定到会话时间线。
 * @param toolCode 工具编码，例如 command.run-program，不包含工具实参。
 * @param requesterComponent 发起最终回查的内部组件，例如 agent-runtime、gateway、task-management 或对象存储服务代理。
 */
public record AgentToolActionArtifactBodyReadFinalCheckRequest(
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String previousGrantDecisionReference,
        String readPurpose,
        String requestedContentMode,
        Integer maxReadableBytes,
        Integer requestedMaxPreviewBytes,
        String candidateContentType,
        Long candidateContentLengthBytes,
        String sanitizedPreviewText,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        String requesterComponent
) {
}
