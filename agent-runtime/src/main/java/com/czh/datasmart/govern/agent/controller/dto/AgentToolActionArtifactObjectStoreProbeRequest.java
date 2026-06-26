/**
 * @Author : Cui
 * @Date: 2026/06/26 23:11
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreProbeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * artifact 对象存储探针请求。
 *
 * <p>这个请求位于 artifact 读取链路的“对象存储适配层”入口。前面已经有三类控制面能力：</p>
 *
 * <p>1. `access-authorizations`：只验证低敏 artifactReference 是否属于当前可见 command worker receipt；</p>
 * <p>2. `body-read-grants`：验证读取目的、读取形态、最大读取字节数和调用组件；</p>
 * <p>3. `body-read-final-checks`：在返回安全短预览前做最终回查与裁剪。</p>
 *
 * <p>本请求补的是中间缺口：对象存储 adapter 是否真的能在服务端访问到对象。它仍然不是下载接口，
 * 也不是预览接口。服务端最多读取一个很小的 sample 来计算指纹、验证对象可用性和长度信息，
 * 响应不会返回正文、stdout/stderr、签名 URL、bucket/key、真实 endpoint、prompt、SQL 或工具参数。</p>
 *
 * @param commandId 命令 outbox 中的稳定命令 ID，用于把探针动作绑定到一次真实副作用命令。
 * @param artifactReference worker receipt 中登记的低敏 artifact 引用，不是 URL、bucket/key 或真实文件路径。
 * @param artifactReferenceType 低敏引用类型，例如 MINIO_OBJECT；真实对象定位由 adapter 内部按服务端配置解析。
 * @param previousGrantDecisionReference body-read-grants 返回的低敏 grant 引用；它不是 bearer token，但必须存在且形态正确。
 * @param readPurpose 读取目的，例如 TASK_RESULT_VIEW、AUDIT_REVIEW 或 OPERATOR_DIAGNOSTIC。
 * @param requestedContentMode 读取形态，例如 OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY、TRUNCATED_TEXT_PREVIEW。
 * @param maxReadableBytes 上一步授权期望的最大读取字节数；服务端会重新走 grant 校验并施加硬上限。
 * @param requestedProbeBytes 本次探针最多希望读取的 sample 字节数；服务端会继续按更小的硬上限裁剪。
 * @param tenantId 主动缩小查询范围的租户过滤条件，不能扩大 gateway Header 授权范围。
 * @param projectId 主动缩小查询范围的项目过滤条件，PROJECT 数据范围下必须命中授权项目。
 * @param actorId 主动缩小查询范围的 actor 过滤条件，SELF 范围下仍会被收口为当前 actor。
 * @param runId Agent run 过滤条件，用于绑定到一次具体执行。
 * @param sessionId Agent session 过滤条件，用于绑定到会话时间线。
 * @param toolCode 工具编码，例如 command.run-program，不包含工具实参。
 * @param requesterComponent 发起对象存储探针的内部组件，例如 agent-runtime、gateway 或 task-management。
 */
public record AgentToolActionArtifactObjectStoreProbeRequest(
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String previousGrantDecisionReference,
        String readPurpose,
        String requestedContentMode,
        Integer maxReadableBytes,
        Integer requestedProbeBytes,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        String requesterComponent
) {
}
