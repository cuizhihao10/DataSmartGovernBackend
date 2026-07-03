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
     * 判断当前投递目标是否负责处理指定 command。
     *
     * <p>早期 outbox 只有 task-management 一类消费者，因此 dispatcher 可以把记录交给所有 target。现在加入 Python MCP
     * Durable Worker 后，同一个 outbox 中会出现不同执行面的命令，必须先按 commandType、consumerService、targetService
     * 与 toolCode 做路由，避免 MCP 命令被误投到任务中心，或普通数据同步命令被误投到 Python MCP worker。</p>
     *
     * <p>默认返回 true 用于保持历史实现兼容；新增协议 target 应覆盖本方法并显式声明自己的消费边界。</p>
     */
    default boolean supports(AgentAsyncTaskCommandOutboxRecord record) {
        return true;
    }

    /**
     * 投递一条命令。
     *
     * <p>实现应在确认下游接收成功后返回；失败时抛出 RuntimeException，让 dispatcher 写回 FAILED。</p>
     */
    void dispatch(AgentAsyncTaskCommandOutboxRecord record);
}
