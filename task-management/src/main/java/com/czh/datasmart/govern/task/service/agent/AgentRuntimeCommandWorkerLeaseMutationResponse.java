/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentRuntimeCommandWorkerLeaseMutationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * agent-runtime command worker lease 释放/续租响应副本。
 *
 * <p>task-management 当前只使用 release 响应。保留完整字段是为了与 agent-runtime 契约对齐，
 * 后续如果质量治理提交变成长耗时任务，可以继续接入 renew，而不需要重新设计 DTO。</p>
 */
public record AgentRuntimeCommandWorkerLeaseMutationResponse(
        boolean success,
        String state,
        String commandId,
        String executorId,
        String fencingToken,
        Long workerLeaseVersion,
        Long workerLeaseExpiresAtMs,
        String message
) {
}
