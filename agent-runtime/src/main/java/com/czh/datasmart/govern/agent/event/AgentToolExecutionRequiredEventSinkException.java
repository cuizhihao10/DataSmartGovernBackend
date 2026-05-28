/**
 * @Author : Cui
 * @Date: 2026/05/28 23:59
 * @Description DataSmart Govern Backend - AgentToolExecutionRequiredEventSinkException.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

/**
 * 必达事件 sink 发布失败异常。
 *
 * <p>Agent 工具状态事件有多种下游出口。Kafka、WebSocket、热投影这类出口通常可以 fail-open，
 * 因为业务状态已经保存后，下游短暂失败可以通过告警、重试或用户刷新补偿。
 * 但事务 outbox 不一样：当 outbox 与工具审计状态处于同一个数据库事务时，outbox 写入失败意味着
 * “这次状态变更没有可靠的后续投递凭据”。如果继续提交状态，就会重新出现双写裂缝。</p>
 *
 * <p>发布器遇到 {@link AgentToolExecutionEventSink#requiredForStateCommit()} 为 true 的 sink 失败时，
 * 会抛出该异常。服务层可以据此区分“普通下游失败，仅记录告警”和“事务 outbox 失败，需要回滚状态提交”。</p>
 */
public class AgentToolExecutionRequiredEventSinkException extends RuntimeException {

    public AgentToolExecutionRequiredEventSinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
