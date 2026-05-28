/**
 * @Author : Cui
 * @Date: 2026/05/28 02:10
 * @Description DataSmart Govern Backend - AgentToolExecutionEventSink.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;

/**
 * Agent 工具执行状态事件的下游接收器扩展点。
 *
 * <p>4.11 阶段我们先定义了 {@link AgentToolExecutionEventPublisher}，让
 * {@code AgentToolExecutionAuditService} 在状态变化后能够“对外发布事实”。
 * 但真实商业化 Agent 平台不会只有一个出口：同一条工具状态事实通常要同时进入
 * Kafka、实时 WebSocket、控制面投影、审计中心、指标系统、未来的事务 outbox，
 * 甚至还可能触发长期记忆写入或 Python Runtime 的下一步 loop 判断。</p>
 *
 * <p>因此这里把“统一发布入口”和“具体下游出口”拆开：</p>
 * <p>1. {@link AgentToolExecutionEventPublisher} 仍是业务服务唯一依赖的端口，
 * 负责构造统一事件并控制发布流程。</p>
 * <p>2. {@code AgentToolExecutionEventSink} 代表一个可插拔下游出口，只负责把已经
 * 构造好的 {@link AgentToolExecutionStateChangedEvent} 写到自己的目标系统。</p>
 * <p>3. 后续如果新增 outbox、WebSocket、审计中心直写，只需要新增一个 sink，
 * 不需要再修改工具审计状态机，也不会让单个 Publisher 文件无限膨胀。</p>
 */
public interface AgentToolExecutionEventSink {

    /**
     * 接收并处理一条工具执行状态变化事件。
     *
     * @param previousState 状态变化前的状态；创建初始工具计划时可能为 null。
     * @param record 已完成状态推进后的审计记录，供 sink 读取必要上下文。
     * @param event 已按统一契约构造完成的安全事件，sink 不应再自行拼装另一套 payload。
     */
    void accept(AgentToolExecutionState previousState,
                AgentToolExecutionAuditRecord record,
                AgentToolExecutionStateChangedEvent event);

    /**
     * 当前 sink 是否属于“状态提交前必须成功”的关键出口。
     *
     * <p>大多数下游出口都应该保持 fail-open，例如 Kafka 临时不可用、WebSocket 推送失败、热投影写入失败，
     * 都不应直接回滚已经完成的审批或工具执行状态。但事务 outbox 是一个例外：当审计状态表和 outbox 表已经处于
     * 同一个 MySQL 事务里时，outbox 写入失败意味着“状态事实无法被后续可靠投递器补偿”，此时应抛出异常并让外层事务回滚。</p>
     *
     * <p>默认返回 false，避免新增普通 sink 时无意改变业务状态机的失败语义。
     * 需要必达语义的 sink 可以覆盖该方法，例如数据库事务 outbox sink。</p>
     *
     * @return true 表示该 sink 失败时发布器应抛出异常，而不是只记录 warn。
     */
    default boolean requiredForStateCommit() {
        return false;
    }
}
