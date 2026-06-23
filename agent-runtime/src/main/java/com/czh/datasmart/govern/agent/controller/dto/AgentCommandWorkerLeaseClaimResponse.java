/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseClaimResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * command worker lease 领取响应。
 *
 * <p>只有真正领取成功或同一 worker 幂等重试时才返回 fencingToken；被其他 worker 持有时不会泄露 token。</p>
 */
public record AgentCommandWorkerLeaseClaimResponse(
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
