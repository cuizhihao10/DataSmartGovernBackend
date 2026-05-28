/**
 * @Author : Cui
 * @Date: 2026/05/28 00:58
 * @Description DataSmart Govern Backend - NoopAgentToolExecutionEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;

/**
 * 空事件发布器。
 *
 * <p>4.12 之后，Spring 容器中的默认实现已经切换为
 * {@link DefaultAgentToolExecutionEventPublisher}。它会自动收集所有
 * {@link AgentToolExecutionEventSink}，因此即使没有 Kafka sink，也可以把事件写入本地
 * runtime-event 投影。</p>
 *
 * <p>本类保留为“显式无副作用”的测试/临时装配工具：当某些单元测试只想验证工具审计状态机，
 * 而不想写入投影或触发任何下游 sink 时，可以直接 new 这个 publisher。它不再注册为 Spring Bean，
 * 避免和默认组合发布器产生多个 {@link AgentToolExecutionEventPublisher} Bean 的注入冲突。</p>
 */
public class NoopAgentToolExecutionEventPublisher implements AgentToolExecutionEventPublisher {

    /**
     * 空实现。
     *
     * <p>这里刻意不记录日志。状态变更可能非常频繁，如果默认 Noop 每次都打印日志，会让本地调试和测试输出被噪音淹没。
     * 如果需要验证事件发布，可在单元测试中注入收集型 publisher，或者在生产环境启用 Kafka publisher。</p>
     */
    @Override
    public void publishStateChanged(AgentToolExecutionState previousState, AgentToolExecutionAuditRecord record) {
        // 默认关闭事件投递时不做任何副作用。
    }
}
