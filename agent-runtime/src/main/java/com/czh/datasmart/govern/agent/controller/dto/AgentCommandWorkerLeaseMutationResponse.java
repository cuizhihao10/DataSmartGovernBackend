/**
 * @Author : Cui
 * @Date: 2026/06/24 18:30
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseMutationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * command worker lease 非 claim 类变更响应。
 *
 * <p>续租和释放都属于 lease mutation，但它们不等同于首次领取：续租延长当前持有者的有效期，释放把当前
 * lease 标记为立即过期。为了避免把“领取成功 acquired”误读成“续租/释放也在领取”，这里单独建响应 DTO，
 * 用 {@code success} 表示本次变更是否被当前控制面接受。</p>
 *
 * <p>token 返回策略：续租成功可以把同一个 fencingToken 返回给当前 worker，便于 worker 刷新本地上下文；
 * 释放成功、token 不匹配、版本不匹配、被他人持有或已过期时都不返回 token，避免把内部资格凭证泄露给
 * 不再拥有执行资格的调用方。</p>
 */
public record AgentCommandWorkerLeaseMutationResponse(
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
