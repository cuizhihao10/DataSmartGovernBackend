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
     * 运维或平台管理员明确决定不再投递。
     */
    IGNORED
}
