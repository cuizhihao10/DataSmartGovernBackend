/**
 * @Author : Cui
 * @Date: 2026/05/31 17:22
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaListener.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Agent 异步工具命令 Kafka listener。
 *
 * <p>该类是 `agent-runtime command outbox -> Kafka -> task-management Inbox` 的传输层适配器。
 * 它刻意保持很薄：不做任务创建、不写 Inbox、不复制协议校验，只把 Kafka value 交给
 * {@link AgentAsyncTaskCommandKafkaMessageHandler}。真正的业务语义继续收口在
 * {@code AgentAsyncTaskCommandConsumerService}，避免 HTTP 入口和 Kafka 入口出现两套不同逻辑。</p>
 *
 * <p>当前默认不启动。启用前应确认：
 * 1. Kafka topic 已创建或 broker 允许自动创建 topic；
 * 2. task-management 数据库表 `agent_async_task_command_inbox` 已迁移；
 * 3. 消费者组、告警、重试和后续死信处理策略已经明确；
 * 4. agent-runtime 侧 outbox dispatcher 已经按相同 schemaVersion 生产消息。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAsyncTaskCommandKafkaListener {

    private final AgentAsyncTaskCommandKafkaMessageHandler messageHandler;

    /**
     * 消费 Agent Runtime 投递的异步工具命令。
     *
     * <p>topic、groupId 和 autoStartup 都通过配置 bean 注入：
     * - topic 与 agent-runtime commandTopic 保持一致；
     * - groupId 表达 task-management 任务创建消费者组；
     * - autoStartup 绑定 enabled 开关，避免本地没有 Kafka 时自动连接。</p>
     */
    @KafkaListener(
            topics = "#{@agentAsyncTaskCommandKafkaProperties.topic}",
            groupId = "#{@agentAsyncTaskCommandKafkaProperties.groupId}",
            autoStartup = "#{@agentAsyncTaskCommandKafkaProperties.enabled}"
    )
    public void onAgentAsyncTaskCommand(ConsumerRecord<String, String> record) {
        AgentAsyncTaskCommandKafkaRecordMetadata metadata = AgentAsyncTaskCommandKafkaRecordMetadata.from(record);
        AgentAsyncTaskCommandKafkaMessageHandler.AgentAsyncTaskCommandKafkaHandleResult result =
                messageHandler.handle(record == null ? null : record.value(), metadata);
        if (!result.accepted()) {
            log.warn("Agent 异步工具命令 Kafka 消息已跳过，failureType={}, reason={}, metadata={}",
                    result.failureType(), result.reason(), metadata.location());
            return;
        }
        if (result.duplicate()) {
            log.debug("Agent 异步工具命令重复消费，commandId={}, taskId={}, metadata={}",
                    result.commandId(), result.taskId(), metadata.location());
            return;
        }
        log.info("Agent 异步工具命令已消费并创建任务，commandId={}, taskId={}, metadata={}",
                result.commandId(), result.taskId(), metadata.location());
    }
}
