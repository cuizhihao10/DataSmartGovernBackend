/**
 * @Author : Cui
 * @Date: 2026/05/31 17:10
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

/**
 * Agent 异步命令投递目标。
 *
 * <p>投递目标是 dispatcher 的适配层。当前可以有 HTTP task-management consume target，
 * 后续可以替换或并行增加 Kafka target、审计中心 target、死信 target。
 * 业务 outbox 状态机不应该依赖具体传输技术。</p>
 */
public interface AgentAsyncTaskCommandDispatchTarget {

    /**
     * 目标名称，用于日志和诊断。
     */
    String targetName();

    /**
     * 投递一条命令。
     *
     * <p>实现应在确认下游接收成功后返回；失败时抛出 RuntimeException，让 dispatcher 写回 FAILED。</p>
     */
    void dispatch(AgentAsyncTaskCommandOutboxRecord record);
}
