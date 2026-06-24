/**
 * @Author : Cui
 * @Date: 2026/06/24 18:13
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 命令执行产物正文读取授权决策请求。
 *
 * <p>这个请求位于 artifact 访问链路的第二道门：第一道门已经通过
 * {@link AgentToolActionArtifactAccessAuthorizeRequest} 验证低敏 artifactReference
 * 是否确实来自当前可见范围内的 command worker receipt；第二道门进一步回答
 * “当前调用场景是否允许进入对象存储正文读取流程”。注意，这里仍然不读取 MinIO、
 * 不返回正文、不签发 URL，也不生成可直接使用的 bearer token。</p>
 *
 * <p>之所以把正文读取再拆成独立请求，是因为真实商业系统里 artifact 可能是
 * 数据质量报告、ETL 产物、任务诊断附件、采样摘要或执行日志片段。不同读取目的
 * 对审批、脱敏、限速、最大字节数、审计留痕和保留期要求不同，如果只沿用
 * “metadata 已匹配”这个事实直接下载，就会把低敏索引校验误用成高敏正文授权。</p>
 *
 * @param commandId command outbox 中的稳定命令 ID，用于把正文读取绑定到具体副作用动作。
 * @param artifactReference worker receipt 写入的低敏 artifact 引用，不能是 URL、真实路径或正文片段。
 * @param artifactReferenceType 调用方认为的引用类型，例如 MINIO_OBJECT；服务端最终以 receipt 事实为准。
 * @param readPurpose 正文读取目的，必须是低敏机器码，例如 TASK_RESULT_VIEW 或 AUDIT_REVIEW。
 * @param requestedContentMode 期望读取形态，例如 TRUNCATED_TEXT_PREVIEW 或 OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY。
 * @param maxReadableBytes 调用方期望的最大读取字节数；服务端会设置上限，避免大文件或日志炸弹。
 * @param tenantId 主动缩小查询范围的租户过滤条件，不能扩大 gateway Header 授权范围。
 * @param projectId 主动缩小查询范围的项目过滤条件，PROJECT 数据范围下必须命中授权项目。
 * @param actorId 主动缩小查询范围的 actor 过滤条件，SELF 范围下仍会被收口为当前 actor。
 * @param runId Agent run 过滤条件，用于绑定到一次具体执行。
 * @param sessionId Agent session 过滤条件，用于绑定到会话时间线。
 * @param toolCode 工具编码过滤条件，例如 command.run-program，不包含工具实参。
 * @param requesterComponent 发起正文读取决策的内部组件，例如 agent-runtime、gateway 或 task-management。
 */
public record AgentToolActionArtifactBodyReadGrantRequest(
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String readPurpose,
        String requestedContentMode,
        Integer maxReadableBytes,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        String requesterComponent
) {
}
