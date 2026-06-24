/**
 * @Author : Cui
 * @Date: 2026/06/24 18:30
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseReleaseRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * command worker lease 释放请求。
 *
 * <p>释放用于 worker 已完成当前 command、确认失败、取消、补偿完成或主动下线的场景。真实生产队列里，
 * 如果 worker 不释放 lease，只能等待 TTL 到期后其他 worker 才能继续处理，会增加任务尾延迟和队列堆积；
 * 如果错误释放了别人的 lease，又会产生重复执行和旧结果回写风险。因此 release 必须携带当前 token/version，
 * 并且只能由当前持有者执行。</p>
 *
 * <p>实现语义不是物理删除 lease 行，而是把当前 lease 事实更新为“立即过期”。这样后续 claim 会基于旧
 * leaseVersion 继续递增，保持 fencing 版本单调，避免释放后版本号回退导致排障困难。</p>
 *
 * @param commandId command outbox 指令 ID。
 * @param executorId worker 实例低敏身份，必须是当前 lease 持有者。
 * @param fencingToken 当前 worker claim 得到的 fencing token。
 * @param workerLeaseVersion 当前 lease 版本，必须与 fencingToken 中的版本一致。
 * @param releaseReason 低敏释放原因代码，例如 COMPLETED、FAILED、CANCELLED、WORKER_SHUTDOWN。
 * @param tenantId 租户边界。
 * @param projectId 项目边界。
 * @param actorId 触发者边界。
 */
public record AgentCommandWorkerLeaseReleaseRequest(
        String commandId,
        String executorId,
        String fencingToken,
        Long workerLeaseVersion,
        String releaseReason,
        Long tenantId,
        Long projectId,
        Long actorId
) {
}
