/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentRuntimeCommandWorkerLeaseClaimResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * agent-runtime command worker lease 领取响应副本。
 *
 * <p>只有领取成功时才会返回 fencingToken。task-management 必须把 token、leaseVersion 和过期时间原样带入
 * command worker receipt；agent-runtime 会用这些字段阻断旧 worker、重复投递或伪造回执。</p>
 */
public record AgentRuntimeCommandWorkerLeaseClaimResponse(
        boolean acquired,
        String state,
        String commandId,
        String executorId,
        String fencingToken,
        Long workerLeaseVersion,
        Long workerLeaseExpiresAtMs,
        String message
) {
}
