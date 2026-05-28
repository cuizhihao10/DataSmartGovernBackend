/**
 * @Author : Cui
 * @Date: 2026/05/28 20:10
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

/**
 * Agent 工具事件 outbox 投递目标。
 *
 * <p>该接口是 dispatcher 与具体下游之间的隔离层。它与
 * {@code AgentToolExecutionEventSink} 故意不是同一个接口，原因是两者处在不同阶段：</p>
 * <p>1. EventSink 发生在“业务状态刚变化”时，负责把统一事件写入 outbox、热投影或直接 Kafka；</p>
 * <p>2. DispatchTarget 发生在“outbox 记录已经持久化之后”，负责把 payloadJson 从 outbox 补偿式投递到下游。</p>
 *
 * <p>如果 dispatcher 直接复用 EventSink，就会再次触发 outbox sink，形成“从 outbox 读出事件又写回 outbox”的递归风险。
 * 因此这里单独定义投递目标，后续 Kafka、WebSocket、审计中心、长期记忆写入都可以按该接口扩展。</p>
 */
public interface AgentToolExecutionEventOutboxDispatchTarget {

    /**
     * 返回投递目标名称。
     *
     * <p>名称会进入日志和失败原因，便于运维定位是 Kafka、WebSocket、审计中心还是长期记忆写入失败。</p>
     */
    String targetName();

    /**
     * 投递一条 outbox 记录。
     *
     * <p>实现必须在确认投递成功后才返回；如果目标系统失败、超时或拒绝，应抛出 RuntimeException。
     * dispatcher 会捕获异常，并把 outbox 记录标记为 FAILED，等待下一次重试。</p>
     *
     * @param record 已被 dispatcher 领取并处于 PUBLISHING 语义的 outbox 记录。
     */
    void dispatch(AgentToolExecutionEventOutboxRecord record);
}
