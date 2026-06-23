/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseClaimRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * command worker lease 领取请求。
 *
 * <p>该 DTO 面向内部 worker/运行时调用，不接收命令行、stdout/stderr、payload、SQL、prompt、真实路径或凭据。
 * 它只提供领取 lease 所需的低敏定位信息和 worker 身份。</p>
 *
 * @param commandId command outbox 指令 ID。
 * @param executorId worker 实例低敏身份。
 * @param tenantId 租户边界。
 * @param projectId 项目边界。
 * @param actorId 触发者边界。
 * @param leaseTtlSeconds 本次 lease 的有效秒数，服务端会裁剪到安全范围。
 */
public record AgentCommandWorkerLeaseClaimRequest(
        String commandId,
        String executorId,
        Long tenantId,
        Long projectId,
        Long actorId,
        Integer leaseTtlSeconds
) {
}
