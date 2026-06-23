/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseClaimResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * command worker lease 领取或校验结果。
 *
 * <p>`tokenVisible` 用来控制是否能把 fencingToken 返回给调用者。被其他 worker 持有时，控制面可以返回
 * leaseVersion/expiresAt 供调度器判断重试窗口，但绝不能泄露对方 token。</p>
 */
public record AgentCommandWorkerLeaseClaimResult(
        boolean acquired,
        AgentCommandWorkerLeaseState state,
        AgentCommandWorkerLeaseRecord record,
        boolean tokenVisible,
        String message
) {

    public static AgentCommandWorkerLeaseClaimResult of(boolean acquired,
                                                        AgentCommandWorkerLeaseState state,
                                                        AgentCommandWorkerLeaseRecord record,
                                                        boolean tokenVisible,
                                                        String message) {
        return new AgentCommandWorkerLeaseClaimResult(acquired, state, record, tokenVisible, message);
    }
}
