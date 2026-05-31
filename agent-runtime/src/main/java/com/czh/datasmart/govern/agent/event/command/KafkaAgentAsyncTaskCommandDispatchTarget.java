/**
 * @Author : Cui
 * @Date: 2026/05/31 18:02
 * @Description DataSmart Govern Backend - KafkaAgentAsyncTaskCommandDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Kafka 的 Agent 异步工具命令投递目标。
 *
 * <p>该 target 是 4.47 command outbox 与 4.48 task-management Kafka listener 之间的生产级传输桥。
 * dispatcher 从 outbox 领取一条命令后，会调用本类把 payloadJson 写入 record.commandTopic，
 * task-management 再通过自己的 Kafka listener 消费并写入 Inbox。</p>
 *
 * <p>边界说明：</p>
 * <p>1. 本类不生成 command，不修改工具审计，也不创建任务，只负责“把已经入 outbox 的命令可靠发送到 Kafka”。</p>
 * <p>2. Kafka 发送成功必须等待 broker ack；如果超时或失败，抛出 RuntimeException，让 dispatcher 写回 FAILED。</p>
 * <p>3. payload 使用字符串 JSON，而不是 Java 对象序列化，确保 Python、Kafka UI、命令行和未来非 Java 服务都能读懂。</p>
 * <p>4. 幂等不在 Kafka producer 里重新实现，消费者侧 Inbox 会用 commandId/idempotencyKey 做最终去重。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-commands.outbox",
        name = "dispatcher-kafka-enabled",
        havingValue = "true"
)
public class KafkaAgentAsyncTaskCommandDispatchTarget implements AgentAsyncTaskCommandDispatchTarget {

    /**
     * command outbox dispatcher 配置。
     *
     * <p>当前主要读取 Kafka send timeout。topic 不放在配置里，是因为每条 outbox record 已经带有 commandTopic，
     * 后续不同工具、风险等级或租户可被路由到不同 topic，而不需要改投递器代码。</p>
     */
    private final AgentAsyncTaskCommandOutboxProperties properties;

    /**
     * Spring Kafka 字符串生产者。
     *
     * <p>key 使用 record.partitionKey，默认等于 runId。这样同一个 Agent Run 的异步工具命令更可能进入同一分区，
     * task-management 在重建 run 级执行轨迹时更容易保持顺序。</p>
     */
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public String targetName() {
        return "kafka:record.commandTopic";
    }

    /**
     * 将一条 command outbox 记录投递到 Kafka。
     *
     * @param record command outbox 记录，必须包含 commandTopic、partitionKey 和 payloadJson。
     */
    @Override
    public void dispatch(AgentAsyncTaskCommandOutboxRecord record) {
        String topic = requireText(record.commandTopic(), "commandTopic", record.commandId());
        String key = partitionKey(record);
        String payload = requireText(record.payloadJson(), "payloadJson", record.commandId());
        long timeoutMs = Math.max(1, properties.getDispatcherKafkaSendTimeoutMs());
        try {
            kafkaTemplate.send(topic, key, payload).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent 异步命令 Kafka 投递被中断，commandId=" + record.commandId()
                    + ", topic=" + topic, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("Agent 异步命令 Kafka 投递失败，commandId=" + record.commandId()
                    + ", topic=" + topic + ", error=" + cause.getMessage(), cause);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Agent 异步命令 Kafka 投递超时，commandId=" + record.commandId()
                    + ", topic=" + topic + ", timeoutMs=" + timeoutMs, exception);
        }
    }

    /**
     * 计算 Kafka key。
     *
     * <p>优先使用 outbox record 的 partitionKey；如果旧记录缺失，则回退 runId；再不行回退 commandId。
     * 这样兼容未来 schema 演进，也避免 key 为空导致 Kafka 分区完全随机。</p>
     */
    private String partitionKey(AgentAsyncTaskCommandOutboxRecord record) {
        if (hasText(record.partitionKey())) {
            return record.partitionKey().trim();
        }
        if (hasText(record.runId())) {
            return record.runId().trim();
        }
        return record.commandId();
    }

    private String requireText(String value, String fieldName, String commandId) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("Agent 异步命令 Kafka 投递缺少 " + fieldName
                    + "，commandId=" + commandId);
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
