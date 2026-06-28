/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentRuntimeCommandWorkerLeaseClaimRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * task-management 领取 agent-runtime command worker lease 的内部请求副本。
 *
 * <p>该 record 只保存领取 lease 必需的低敏定位字段。它不包含 payload body、工具参数、SQL、prompt、
 * 样本、模型输出、真实路径、主机地址或凭据。task-management 与 agent-runtime 不共享编译期 DTO，
 * 目的是保持微服务边界清晰，后续可以通过 OpenAPI 或服务契约治理替换这类本地副本。</p>
 *
 * @param commandId agent-runtime command outbox 的稳定 ID。
 * @param executorId task-management 当前 worker 的低敏身份。
 * @param tenantId 租户边界，用于 agent-runtime lease fact 复核。
 * @param projectId 项目边界，用于 agent-runtime lease fact 复核。
 * @param actorId 原始发起人或服务账号 ID，用于低敏审计串联。
 * @param leaseTtlSeconds 本次 command worker lease 的 TTL，agent-runtime 会再次裁剪。
 */
public record AgentRuntimeCommandWorkerLeaseClaimRequest(
        String commandId,
        String executorId,
        Long tenantId,
        Long projectId,
        Long actorId,
        Integer leaseTtlSeconds
) {
}
