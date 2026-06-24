/**
 * @Author : Cui
 * @Date: 2026/06/24 21:08
 * @Description DataSmart Govern Backend - AgentCommandWorkerOutputSanitizeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 受控命令 worker 输出片段净化请求。
 *
 * <p>这个 DTO 服务于真实 sandbox/worker 把 stdout、stderr 或组合日志写入 artifact 之前的前置治理。
 * 它不是 runtime event 写入合同，也不是 artifact 下载合同。业务意义是：worker 可以把一小段“待净化输出片段”
 * 交给 Java Host 控制面，控制面按统一策略做大小限制、敏感行重写、控制字符清理和安全预览裁剪，然后把结果作为
 * 后续 artifact safe preview 的候选输入。</p>
 *
 * <p>为什么允许请求体中出现 `rawOutputChunk`，但禁止它进入响应和事件层？因为真实命令执行时，原始输出只能在
 * 短生命周期的服务端净化链路中出现，不能进入 timeline、projection、审计模板或模型上下文。该请求被设计为
 * 内部服务间接口，生产部署时必须叠加内网路由、服务账号签名、mTLS 或服务网格 ACL。</p>
 *
 * @param commandId command outbox 的稳定命令 ID，用于把输出片段绑定到具体副作用动作。
 * @param outputChannel 输出通道，允许 STDOUT、STDERR 或 COMBINED_LOG；响应只回显归一化通道码。
 * @param rawOutputChunk worker 捕获到的原始输出片段；只允许在本次请求内短暂处理，不会被写入事件或响应原样返回。
 * @param rawOutputEncoding worker 声明的输出编码，例如 UTF-8；当前只作为低敏元数据和排障提示，不做真实转码。
 * @param rawOutputTruncatedByWorker true 表示 worker 在到达 Java 前已经按本地预算裁剪过输出。
 * @param workerOutputByteLimitBytes worker 侧采用的输出字节预算，服务端会据此继续限制预览大小。
 * @param requestedPreviewBytes 调用方希望返回的安全预览字节数，服务端会继续施加硬上限。
 * @param tenantId 租户 ID，用于后续审计串联和与 artifactReference 绑定；不能扩大 Header 授权范围。
 * @param projectId 项目 ID，用于后续审计串联和数据范围收口。
 * @param actorId 触发命令的 actor ID；为空代表只能识别到服务角色。
 * @param runId Agent run ID，用于绑定一次具体执行。
 * @param sessionId Agent session ID，用于绑定会话时间线。
 * @param toolCode 工具编码，例如 command.run-program，不包含工具实参。
 * @param requesterComponent 发起净化请求的内部组件，例如 command-worker、task-management 或 agent-runtime。
 */
public record AgentCommandWorkerOutputSanitizeRequest(
        String commandId,
        String outputChannel,
        String rawOutputChunk,
        String rawOutputEncoding,
        Boolean rawOutputTruncatedByWorker,
        Integer workerOutputByteLimitBytes,
        Integer requestedPreviewBytes,
        Long tenantId,
        Long projectId,
        Long actorId,
        String runId,
        String sessionId,
        String toolCode,
        String requesterComponent
) {
}
