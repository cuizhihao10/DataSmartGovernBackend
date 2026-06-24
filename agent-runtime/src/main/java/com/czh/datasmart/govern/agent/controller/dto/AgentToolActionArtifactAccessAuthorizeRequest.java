/**
 * @Author : Cui
 * @Date: 2026/06/24 17:53
 * @Description DataSmart Govern Backend - AgentToolActionArtifactAccessAuthorizeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 命令执行产物访问预授权请求。
 *
 * <p>这个 DTO 服务于“读取 artifact 正文之前”的安全预检，而不是实际下载接口。真实 Agent Host
 * 会经历多段链路：command proposal -> safety precheck -> outbox -> worker lease -> command worker receipt
 * -> artifact metadata -> artifact body read。前几步已经证明“命令为什么可以执行”，但用户、前端、
 * 外部 Agent 或后续 worker 想读取产物时，还必须再次证明“当前访问者能否看到这份产物”。这就是本请求存在的原因。</p>
 *
 * <p>字段刻意保持低敏：只接收 commandId、artifactReference 以及租户/项目/run/session 等范围字段。
 * 不接收 stdout/stderr、对象存储真实 bucket/key、签名 URL、命令行、工作目录、SQL、prompt、模型输出或工具实参。
 * 这样即使该请求被审计、重放或进入排障日志，也不会把执行正文扩散到控制面。</p>
 *
 * @param commandId command outbox 中的稳定指令 ID，用于定位是哪一次受控命令执行产生了 artifact。
 * @param artifactReference worker receipt 写入 runtime event 的低敏产物引用，必须使用受控 scheme。
 * @param artifactReferenceType 调用方认为的引用类型，例如 MINIO_OBJECT、AGENT_ARTIFACT；可为空，服务端会以 receipt 事实为准。
 * @param requestedAccessMode 调用方期望的访问模式；当前阶段只授权 METADATA_ONLY，不直接授予 BODY_READ。
 * @param tenantId 用户主动传入的租户过滤条件，只能缩小 gateway Header 中的可见范围，不能扩大权限。
 * @param projectId 用户主动传入的项目过滤条件，PROJECT 数据范围下必须命中 authorizedProjectIds。
 * @param actorId 用户主动传入的触发人过滤条件；SELF 范围下会被收口为当前 actor。
 * @param runId Agent run 过滤条件，用于把 artifact 绑定到某一次具体执行。
 * @param sessionId Agent session 过滤条件，用于把 artifact 绑定到某个会话时间线。
 * @param toolCode 工具编码过滤条件，例如 command.run-program；用于避免同一 commandId 在迁移期被不同工具链误用。
 */
public record AgentToolActionArtifactAccessAuthorizeRequest(
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String requestedAccessMode,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode
) {
}
