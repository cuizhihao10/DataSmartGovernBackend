/**
 * @Author : Cui
 * @Date: 2026/06/24 23:58
 * @Description DataSmart Govern Backend - AgentCommandSandboxRunAdmissionResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * command sandbox run 准入响应。
 *
 * <p>该响应是 Java Host 发给 worker 的“执行前合同”，不是执行结果。它只包含低敏状态、预算、隔离模式、
 * workspace 引用和 evidence/issue code，不包含 fencingToken 明文、命令行、真实路径、stdout/stderr、payload、
 * SQL、prompt、模型输出、签名 URL、bucket/key 或凭据。</p>
 *
 * <p>字段里的 {@code processStarted=false} 和 {@code rawCommandAccepted=false} 是刻意保留的安全信号：
 * 当前 Java Host 只做准入治理，不启动本机进程，也不接收命令正文。真正执行仍由后续 sandbox runner 在独立工作区、
 * 独立权限和输出净化链路内完成。</p>
 *
 * @param accepted true 表示允许 worker 按本合同进入沙箱执行区。
 * @param decision 低敏决策码，例如 ADMITTED_FOR_SANDBOX_EXECUTION、DENIED_BY_COMMAND_SAFETY。
 * @param sandboxRunId 低敏 sandbox run 引用；只有 accepted=true 时返回。
 * @param commandId command outbox 指令 ID。
 * @param executorId worker 低敏身份。
 * @param workerLeaseVersion 已验证的当前 lease 版本。
 * @param workerLeaseExpiresAtMs 已验证的当前 lease 过期时间。
 * @param tenantId 租户边界。
 * @param projectId 项目边界。
 * @param actorId 触发者边界。
 * @param isolationMode 服务端裁剪后的隔离模式。
 * @param normalizedTimeoutSeconds 服务端裁剪后的超时预算。
 * @param normalizedOutputByteLimitBytes 服务端裁剪后的输出预算。
 * @param normalizedCpuMillicores 服务端裁剪后的 CPU 预算。
 * @param normalizedMemoryMb 服务端裁剪后的内存预算。
 * @param workspaceReference 低敏 workspace 引用。
 * @param payloadPolicy 响应载荷策略说明，明确不返回敏感正文。
 * @param evidenceCodes 已满足的低敏证据码。
 * @param issueCodes 阻断或提示问题码。
 * @param recommendedActions 后续动作建议，只允许低敏文案。
 * @param commandBodyRequired 当前 Host 是否需要命令正文；固定 false。
 * @param rawCommandAccepted 当前 Host 是否接收原始命令；固定 false。
 * @param processStarted 当前 Host 是否已经启动进程；固定 false。
 */
public record AgentCommandSandboxRunAdmissionResponse(
        Boolean accepted,
        String decision,
        String sandboxRunId,
        String commandId,
        String executorId,
        Long workerLeaseVersion,
        Long workerLeaseExpiresAtMs,
        Long tenantId,
        Long projectId,
        Long actorId,
        String isolationMode,
        Integer normalizedTimeoutSeconds,
        Integer normalizedOutputByteLimitBytes,
        Integer normalizedCpuMillicores,
        Integer normalizedMemoryMb,
        String workspaceReference,
        String payloadPolicy,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions,
        Boolean commandBodyRequired,
        Boolean rawCommandAccepted,
        Boolean processStarted
) {
}
