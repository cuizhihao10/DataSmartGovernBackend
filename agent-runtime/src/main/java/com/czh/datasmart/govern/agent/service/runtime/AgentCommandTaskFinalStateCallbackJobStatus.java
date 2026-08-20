/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackJobStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * 最终态 callback durable job 的状态。
 *
 * <p>状态描述的是 callback 收敛过程，而不是 Agent 工具或 task-management 业务任务本身。这样下游已经接收
 * FAILED callback、但仍需人工补偿的场景可以被明确表示为 {@link #COMPENSATION_REQUIRED}，不会被误写成成功。</p>
 */
public enum AgentCommandTaskFinalStateCallbackJobStatus {

    /** 已由 Java receipt 创建，等待 worker 领取。 */
    PENDING,

    /** 当前实例持有可见性租约，正在重新对账并调用下游。 */
    DISPATCHING,

    /** 下游暂时不可用，等待 nextAttemptAt 后重试。 */
    RETRY_WAIT,

    /** task-management 已幂等接受 callback，且不再需要人工补偿。 */
    DELIVERED,

    /** Java receipt 已产生更高回放序列，新 job 会以最新事实重新收敛，旧 job 不再允许写下游。 */
    SUPERSEDED,

    /** callback 已发送或事实异常，但仍需要人工补偿/核对，自动 worker 不会继续领取。 */
    COMPENSATION_REQUIRED,

    /** 可恢复失败已超过最大尝试次数，进入死信待人工处理。 */
    DEAD_LETTER
}
