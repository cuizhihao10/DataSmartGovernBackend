/**
 * @Author : Cui
 * @Date: 2026/05/28 20:10
 * @Description DataSmart Govern Backend - KafkaAgentToolExecutionEventOutboxDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * outbox dispatcher 的 Kafka 投递目标。
 *
 * <p>该类与 {@code KafkaAgentToolExecutionEventSink} 的区别非常关键：
 * 后者是“状态变更时直接投递 Kafka”，本类是“从 outbox 读取已持久化 payload 后补偿式投递 Kafka”。
 * 生产环境更推荐使用本类，因为它能和 4.19 的事务 outbox 配合，避免状态提交后 Kafka 暂时不可用导致事件丢失。</p>
 *
 * <p>为了让 dispatcher 能准确决定 markPublished/markFailed，这里会等待 Kafka broker ack。
 * 如果发送超时或失败，会抛出异常，由 dispatcher 写回 FAILED 并计算下一次重试时间。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.tool-execution-events.outbox",
        name = "dispatcher-kafka-enabled",
        havingValue = "true"
)
public class KafkaAgentToolExecutionEventOutboxDispatchTarget implements AgentToolExecutionEventOutboxDispatchTarget {

    private final AgentToolExecutionEventProperties eventProperties;
    private final AgentToolExecutionEventOutboxProperties outboxProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public String targetName() {
        return "kafka:" + eventProperties.getTopic();
    }

    @Override
    public void dispatch(AgentToolExecutionEventOutboxRecord record) {
        try {
            kafkaTemplate.send(eventProperties.getTopic(), record.partitionKey(), record.payloadJson())
                    .get(Math.max(1, outboxProperties.getDispatcherKafkaSendTimeoutMs()), TimeUnit.MILLISECONDS);
            log.debug("Agent 工具 outbox 事件已由 dispatcher 投递到 Kafka，topic={}, outboxId={}, eventId={}, state={}",
                    eventProperties.getTopic(), record.outboxId(), record.eventId(), record.currentState());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent 工具 outbox Kafka 投递被中断，outboxId=" + record.outboxId(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 工具 outbox Kafka 投递失败，outboxId=" + record.outboxId(), exception);
        }
    }
}
