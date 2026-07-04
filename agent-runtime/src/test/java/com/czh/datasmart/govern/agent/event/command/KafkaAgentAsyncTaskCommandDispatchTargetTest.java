/**
 * @Author : Cui
 * @Date: 2026/05/31 18:06
 * @Description DataSmart Govern Backend - KafkaAgentAsyncTaskCommandDispatchTargetTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kafka Agent 异步命令投递目标测试。
 *
 * <p>测试不启动 Kafka broker，只固定投递目标的传输语义：
 * - 发送时必须使用 outbox record 上的 topic、partitionKey 和 payloadJson；
 * - broker ack 失败时必须抛出异常，让 dispatcher 把 outbox 记录写回 FAILED；
 * - 缺少 topic 这类契约错误必须在发送前阻断，不能把坏消息交给 Kafka。</p>
 */
class KafkaAgentAsyncTaskCommandDispatchTargetTest {

    @Test
    void supportsShouldExcludeRagCommandAndKeepGenericCommand() {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        KafkaAgentAsyncTaskCommandDispatchTarget target =
                new KafkaAgentAsyncTaskCommandDispatchTarget(properties, kafkaTemplate());

        assertFalse(target.supports(record(
                "datasmart.agent.rag.commands",
                RagAgentAsyncTaskCommandDispatchTarget.RAG_TOOL_CODE,
                RagAgentAsyncTaskCommandDispatchTarget.RAG_CONSUMER_SERVICE,
                RagAgentAsyncTaskCommandDispatchTarget.RAG_CONSUMER_SERVICE
        )));
        assertTrue(target.supports(record(
                "datasmart.agent.tool.async.commands",
                "data-sync.execute",
                "task-management",
                "data-sync"
        )));
    }

    @Test
    void dispatchShouldSendPayloadToKafkaWithRecordTopicAndPartitionKey() {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        properties.setDispatcherKafkaSendTimeoutMs(100);
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        AgentAsyncTaskCommandOutboxRecord record = record("datasmart.agent.tool.async.commands");
        CompletableFuture<SendResult<String, String>> acknowledged = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(record.commandTopic(), record.partitionKey(), record.payloadJson()))
                .thenReturn(acknowledged);

        new KafkaAgentAsyncTaskCommandDispatchTarget(properties, kafkaTemplate).dispatch(record);

        verify(kafkaTemplate).send(record.commandTopic(), record.partitionKey(), record.payloadJson());
    }

    @Test
    void dispatchShouldThrowWhenBrokerAckFails() {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        properties.setDispatcherKafkaSendTimeoutMs(100);
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        AgentAsyncTaskCommandOutboxRecord record = record("datasmart.agent.tool.async.commands");
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker 暂时不可用"));
        when(kafkaTemplate.send(record.commandTopic(), record.partitionKey(), record.payloadJson()))
                .thenReturn(failed);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new KafkaAgentAsyncTaskCommandDispatchTarget(properties, kafkaTemplate).dispatch(record));

        assertTrue(exception.getMessage().contains("Kafka 投递失败"));
        assertTrue(exception.getMessage().contains("broker 暂时不可用"));
    }

    @Test
    void dispatchShouldRejectRecordWithoutTopicBeforeCallingKafka() {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplate();
        AgentAsyncTaskCommandOutboxRecord record = record(" ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new KafkaAgentAsyncTaskCommandDispatchTarget(properties, kafkaTemplate).dispatch(record));

        assertTrue(exception.getMessage().contains("commandTopic"));
        verifyNoInteractions(kafkaTemplate);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }

    private AgentAsyncTaskCommandOutboxRecord record(String topic) {
        return record(topic, "data-sync.execute", "task-management", "data-sync");
    }

    /**
     * 构造 Kafka target 测试专用的 outbox record。
     *
     * <p>这里把 topic、toolCode、consumerService、targetService 都作为参数暴露出来，是为了明确验证路由边界：
     * Kafka target 只负责把通用 task-management 异步任务命令写入 Kafka；RAG/MCP 这类拥有独立 Python worker
     * 语义、低敏回执策略和权限边界的命令，必须被排除在通用投递目标之外。</p>
     */
    private AgentAsyncTaskCommandOutboxRecord record(String topic,
                                                     String toolCode,
                                                     String consumerService,
                                                     String targetService) {
        Instant now = Instant.now();
        return new AgentAsyncTaskCommandOutboxRecord(
                "async-command-outbox:aatc-kafka-target",
                "aatc-kafka-target",
                "agent-tool-async:session:run:audit",
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ASYNC_TASK_REQUESTED",
                "run-kafka-target",
                topic,
                "task-management",
                "session-kafka-target",
                "run-kafka-target",
                "audit-kafka-target",
                toolCode,
                targetService,
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-kafka",
                "trace-kafka",
                "agent-tool-audit://session-kafka-target/run-kafka-target/audit-kafka-target/plan-arguments",
                AgentAsyncTaskCommandOutboxStatus.PENDING,
                0,
                now,
                now,
                null,
                null,
                "",
                128,
                false,
                "{\"schemaVersion\":\"datasmart.agent.async-task-command.v1\"}"
        );
    }
}
