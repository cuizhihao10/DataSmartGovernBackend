/**
 * @Author : Cui
 * @Date: 2026/05/28 02:10
 * @Description DataSmart Govern Backend - DefaultAgentToolExecutionEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventProperties;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认的 Agent 工具执行事件统一发布器。
 *
 * <p>这个类是工具状态事件链路的“扇出中心”。业务状态机只调用
 * {@link #publishStateChanged(AgentToolExecutionState, AgentToolExecutionAuditRecord)}，
 * 发布器负责把审计记录转换成统一事件契约，然后交给多个
 * {@link AgentToolExecutionEventSink} 处理。</p>
 *
 * <p>为什么不让每个 sink 自己从审计记录构造事件？因为那样会产生几个隐性风险：</p>
 * <p>1. 字段不一致：Kafka、投影、WebSocket 可能各自遗漏字段，导致排障时同一状态事实
 * 在不同渠道看起来不一样。</p>
 * <p>2. 脱敏不一致：某个出口可能不小心发布完整入参或审批备注，扩大敏感信息传播面。</p>
 * <p>3. 顺序难治理：同一条状态变化如果生成多个 eventId，后续 replay、幂等、审计比对都会变复杂。</p>
 * <p>因此这里坚持“一次状态变化 -> 一条统一事件 -> 多个出口复用”。</p>
 *
 * <p>发布失败策略默认采用 sink 级 fail-open：普通下游出口失败，只记录告警，不阻断其他出口。
 * 但 4.19 开始引入“必达 sink”语义：当事务 outbox 已与审计状态处于同一个数据库事务时，
 * outbox 写入失败必须向上抛出，让服务层回滚状态提交。这样才能真正避免“状态提交了、事件凭据丢了”的双写裂缝。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAgentToolExecutionEventPublisher implements AgentToolExecutionEventPublisher {

    /**
     * 工具状态事件的发布配置。
     *
     * <p>当前主要使用 source 字段统一标记事件来源。Kafka topic 是否启用由 Kafka sink 自己控制，
     * 但本地 runtime-event 投影 sink 不依赖 Kafka，因此即使 producer 关闭也能为控制面提供热回放能力。</p>
     */
    private final AgentToolExecutionEventProperties properties;

    /**
     * 所有已注册的事件下游出口。
     *
     * <p>Spring 会把容器中实现 {@link AgentToolExecutionEventSink} 的 Bean 自动注入为列表。
     * 列表为空时发布器天然退化为 no-op，这比在业务服务中判断“有没有 Kafka/有没有投影”
     * 更干净，也避免测试环境为了关闭副作用而到处改构造参数。</p>
     */
    private final List<AgentToolExecutionEventSink> sinks;

    /**
     * 发布工具状态变化事件。
     *
     * <p>方法内部做三件事：</p>
     * <p>1. 使用统一工厂方法从审计记录构造安全事件，确保字段、eventId、脱敏策略一致。</p>
     * <p>2. 遍历所有 sink，把同一份事件送往 Kafka、投影等下游出口。</p>
     * <p>3. 对每个 sink 单独处理异常：普通 sink 失败只告警，必达 sink 失败抛出
     * {@link AgentToolExecutionRequiredEventSinkException}，交给服务层事务边界处理。</p>
     */
    @Override
    public void publishStateChanged(AgentToolExecutionState previousState, AgentToolExecutionAuditRecord record) {
        AgentToolExecutionStateChangedEvent event = AgentToolExecutionStateChangedEvent.from(
                properties.getSource(),
                previousState,
                record
        );
        if (sinks.isEmpty()) {
            log.debug("Agent 工具执行状态事件没有注册下游 sink，eventId={}, auditId={}, state={}",
                    event.eventId(), event.auditId(), event.currentState());
            return;
        }
        for (AgentToolExecutionEventSink sink : sinks) {
            publishToSink(sink, previousState, record, event);
        }
    }

    /**
     * 向单个 sink 发布事件。
     *
     * <p>这里把 try/catch 拆出来，是为了让主发布流程保持线性可读：构造事件、遍历 sink、
     * 单个 sink 失败时隔离处理。后续如果要为每个 sink 增加耗时指标、失败计数或熔断策略，
     * 也可以集中在这个方法里扩展。</p>
     */
    private void publishToSink(AgentToolExecutionEventSink sink,
                               AgentToolExecutionState previousState,
                               AgentToolExecutionAuditRecord record,
                               AgentToolExecutionStateChangedEvent event) {
        try {
            sink.accept(previousState, record, event);
        } catch (RuntimeException exception) {
            log.warn("Agent 工具执行状态事件 sink 发布失败，sink={}, eventId={}, auditId={}, state={}, error={}",
                    sink.getClass().getSimpleName(),
                    event.eventId(),
                    event.auditId(),
                    event.currentState(),
                    exception.getMessage());
            if (sink.requiredForStateCommit()) {
                throw new AgentToolExecutionRequiredEventSinkException(
                        "Agent 工具执行状态事件必达 sink 发布失败，sink="
                                + sink.getClass().getSimpleName()
                                + ", eventId=" + event.eventId(),
                        exception
                );
            }
        }
    }
}
