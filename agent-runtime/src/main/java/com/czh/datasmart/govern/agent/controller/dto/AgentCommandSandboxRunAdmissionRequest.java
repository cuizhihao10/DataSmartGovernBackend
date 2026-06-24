/**
 * @Author : Cui
 * @Date: 2026/06/24 23:58
 * @Description DataSmart Govern Backend - AgentCommandSandboxRunAdmissionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * command sandbox run 准入请求。
 *
 * <p>该 DTO 面向内部 worker、Python Runtime 或未来 OpenClaw-style sandbox runner。它表达的是：
 * “某个 worker 已经领取 command lease，准备进入受控沙箱执行区，请 Java Host 最后确认能否启动执行”。
 * 这里仍然不是命令执行请求，因此不会也不能接收 commandLine、workingDirectory、环境变量、stdin、
 * stdout/stderr、payload body、SQL、prompt、模型输出、URL、真实路径、凭据或内部 endpoint。</p>
 *
 * <p>为什么要单独有 sandbox admission，而不是让 worker 只凭 lease 直接执行：
 * lease 只能证明“谁有资格处理这条 command”，不能证明“现在可以启动进程”。真实生产环境还必须同时确认
 * command safety、workspace 隔离、资源预算、输出预算、工具目录和 artifact 链路。把这些检查集中在 Java Host，
 * 后续 Python runner、容器 runner、Kubernetes Job runner 都能复用同一个准入合同。</p>
 *
 * @param commandId command outbox 指令 ID，用于串联 proposal、outbox、lease、sandbox run 和 receipt。
 * @param executorId worker 低敏身份，必须与当前 lease 持有者一致。
 * @param fencingToken worker 领取 lease 后获得的内部 token；服务端只校验，不会在响应中返回。
 * @param workerLeaseVersion lease 单调版本号，必须与 fencingToken 版本一致。
 * @param workerLeaseExpiresAtMs worker 看到的当前 lease 过期时间；必须与 Java Host 当前事实一致。
 * @param tenantId 租户边界；用于与 lease 事实做低敏范围对齐。
 * @param projectId 项目边界；用于与 lease 事实做低敏范围对齐。
 * @param actorId 触发者边界；用于与 lease 事实做低敏范围对齐。
 * @param commandSafetyDecision Java safety precheck 或 worker precheck 复核后的低敏决策。
 * @param commandSafetyPolicyVersion 命令安全策略版本，便于审计“当时按哪套策略放行”。
 * @param commandSafetyIssueCodes 尚未关闭的问题码；非空时不能启动执行。
 * @param requestedIsolationMode worker 期望的隔离模式，例如 NO_NETWORK_PROCESS_SANDBOX。
 * @param requestedTimeoutSeconds worker 期望的超时秒数，服务端会裁剪到安全上限。
 * @param requestedOutputByteLimitBytes worker 期望的输出字节预算，服务端会裁剪到安全上限。
 * @param requestedCpuMillicores worker 期望 CPU 配额，当前只是控制面预算，不直接调度容器。
 * @param requestedMemoryMb worker 期望内存配额，当前只是控制面预算，不直接调度容器。
 * @param workspaceReference 低敏 workspace 引用，只允许 agent-workspace:/workspace:/sandbox-workspace: 这类引用。
 * @param toolCode 工具编码，例如 command.run-program；不能携带工具参数。
 * @param requesterComponent 调用方组件名，例如 python-ai-runtime-controlled-worker。
 * @param idempotencyKey 调用方幂等键；当前用于生成低敏 sandboxRunId，不作为唯一约束持久化。
 */
public record AgentCommandSandboxRunAdmissionRequest(
        String commandId,
        String executorId,
        String fencingToken,
        Long workerLeaseVersion,
        Long workerLeaseExpiresAtMs,
        Long tenantId,
        Long projectId,
        Long actorId,
        String commandSafetyDecision,
        String commandSafetyPolicyVersion,
        List<String> commandSafetyIssueCodes,
        String requestedIsolationMode,
        Integer requestedTimeoutSeconds,
        Integer requestedOutputByteLimitBytes,
        Integer requestedCpuMillicores,
        Integer requestedMemoryMb,
        String workspaceReference,
        String toolCode,
        String requesterComponent,
        String idempotencyKey
) {
}
