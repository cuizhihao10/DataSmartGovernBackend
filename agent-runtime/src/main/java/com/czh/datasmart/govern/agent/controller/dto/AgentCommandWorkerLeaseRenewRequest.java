/**
 * @Author : Cui
 * @Date: 2026/06/24 18:30
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseRenewRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * command worker lease 续租请求。
 *
 * <p>续租用于“worker 已经合法持有 lease，但执行时间超过最初 TTL”的场景。例如真实 sandbox 运行命令、
 * 等待受控进程退出、上传 artifact 或等待外部数据源响应时，worker 不能让 lease 静默过期后继续写 receipt。
 * 因此 worker 需要在过期前携带当前 fencingToken 和 workerLeaseVersion 向 Java Host 申请延长过期时间。</p>
 *
 * <p>安全边界：该 DTO 只接收低敏控制面字段，不接收 commandLine、workingDirectory、stdout/stderr、
 * artifact body、SQL、prompt、模型输出、签名 URL、bucket/key、内部 endpoint 或任何凭据。续租成功只表示
 * “当前 worker 仍是这条 command 的合法持有者”，不表示命令执行成功，也不授予读取 artifact 正文的权限。</p>
 *
 * @param commandId command outbox 指令 ID，用来定位被处理的受控命令。
 * @param executorId worker 实例低敏身份，必须与当前 lease 持有者一致。
 * @param fencingToken 当前 worker claim 得到的 fencing token，服务端会校验它仍是当前有效 token。
 * @param workerLeaseVersion fencingToken 中携带的版本号；续租不会递增版本，只会延长过期时间。
 * @param tenantId 租户边界，便于后续审计和多租户隔离扩展；当前只作为低敏事实保存。
 * @param projectId 项目边界，便于后续按项目治理 worker 配额、执行权限和审计视图。
 * @param actorId 触发者边界，便于后续串联用户、服务账号或 Agent 身份。
 * @param leaseTtlSeconds 续租后的 TTL 秒数，服务端会裁剪到安全范围，避免 worker 无限持有 lease。
 */
public record AgentCommandWorkerLeaseRenewRequest(
        String commandId,
        String executorId,
        String fencingToken,
        Long workerLeaseVersion,
        Long tenantId,
        Long projectId,
        Long actorId,
        Integer leaseTtlSeconds
) {
}
