/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentRuntimeCommandWorkerLeaseReleaseRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * task-management 释放 agent-runtime command worker lease 的内部请求副本。
 *
 * <p>释放 lease 是队列尾延迟治理的一部分。真实提交、失败或补偿进入可观测状态后，当前 worker 应主动释放
 * command lease，而不是等待 TTL 自然过期。请求仍然只携带低敏身份和 token/version，不携带工具正文。</p>
 */
public record AgentRuntimeCommandWorkerLeaseReleaseRequest(
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
