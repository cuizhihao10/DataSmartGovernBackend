/**
 * @Author : Cui
 * @Date: 2026/05/31 17:08
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

/**
 * Agent 异步任务命令 outbox 投递状态。
 *
 * <p>状态机与工具执行状态分开建模。工具执行状态描述 Agent 工具计划是否执行成功；
 * command outbox 状态描述“创建任务的命令是否可靠送达 task-management”。</p>
 */
public enum AgentAsyncTaskCommandOutboxStatus {

    /**
     * 已进入 outbox，等待 dispatcher 领取。
     */
    PENDING,

    /**
     * 已被某一轮 dispatcher 领取，正在发送。
     */
    PUBLISHING,

    /**
     * 已成功投递到下游目标。
     */
    PUBLISHED,

    /**
     * 投递失败，等待 nextRetryAt 后再次尝试。
     */
    FAILED,

    /**
     * 连续失败、契约错误或缺少目标，已停止自动重试。
     */
    BLOCKED,

    /**
     * 已进入命令死信队列，自动 dispatcher 不再领取。
     *
     * <p>DEAD_LETTER 与 BLOCKED 的差异在于：BLOCKED 更偏“当前被策略或契约阻断”，修复后可直接重新入队；
     * DEAD_LETTER 更偏“已经确认自动恢复不安全或无意义”，需要管理员明确重排、忽略或进一步排障。
     * 真实商业化运维台通常会把 DEAD_LETTER 作为待处理队列，而不是让它继续参与自动重试。</p>
     */
    DEAD_LETTER,

    /**
     * 运维或平台管理员明确决定不再投递。
     */
    IGNORED
}
