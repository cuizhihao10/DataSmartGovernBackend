/**
 * @Author : Cui
 * @Date: 2026/08/11 20:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerKafkaConsumer.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryExecutionResult;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryDataSyncClient;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryMetrics;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryTriggerEvent;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryTriggerConsumerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * data-sync durable outbox 到 Agent Autopilot Recovery 的 Kafka 入口。
 *
 * <p>监听器只负责消息接入和低敏日志；JSON、授权、Python 规划、证据、双策略、状态 receipt 和 retry
 * 全部委托给应用服务。默认关闭，只有 Kafka、PostgreSQL、Python Runtime 与内部令牌都准备好后才应
 * 在环境配置中开启。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAutopilotRecoveryTriggerKafkaConsumer {

    private final AgentAutopilotRecoveryTriggerConsumerService consumerService;
    private final AgentAutopilotRecoveryDataSyncClient dataSyncClient;
    private final ObjectMapper objectMapper;
    private final AgentAutopilotRecoveryMetrics metrics;

    /**
     * 接收一条原始 Kafka 恢复触发，并把解析、验证和执行交给专用应用服务。
     *
     * <p>输入是 broker 反序列化后的 JSON 文本，输出为 {@code void}；本监听器只记录低敏结果日志。
     * 实际副作用包括验证持久化授权、请求规划、写入 data-sync case 和可能的失败对象重试，均由
     * {@code consumerService} 负责。监听器自身不把 payload、证据正文、令牌或模型输出写入日志。</p>
     *
     * <p>权限、租户范围和证据校验不会在这里被绕过，服务层会在执行前重新处理。该方法没有本地去重缓存，
     * 因而重复投递的幂等性由服务层与 data-sync 的持久 receipt/状态机保证。服务若将基础设施异常向外抛出，
     * Spring Kafka 会按监听容器的错误处理策略重试或转交；以结果对象表示的永久拒绝则在此正常记录并返回，
     * 让容器可以提交 offset。</p>
     *
     * @param payload data-sync outbox 发布的恢复触发 JSON
     */
    @RetryableTopic(
            attempts = "${datasmart.agent-runtime.autopilot-recovery.kafka.retry-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${datasmart.agent-runtime.autopilot-recovery.kafka.retry-delay-ms:1000}",
                    multiplier = 2.0d,
                    maxDelayExpression = "${datasmart.agent-runtime.autopilot-recovery.kafka.retry-max-delay-ms:5000}"
            ),
            autoCreateTopics = "false",
            retryTopicSuffix = "-autopilot-recovery-retry",
            dltTopicSuffix = "-autopilot-recovery-dlt",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "${datasmart.agent-runtime.autopilot-recovery.kafka.enabled:false}"
    )
    @KafkaListener(
            topics = "${datasmart.agent-runtime.autopilot-recovery.kafka.topic:datasmart.agent.autopilot-recovery-trigger.v1}",
            groupId = "${datasmart.agent-runtime.autopilot-recovery.kafka.group-id:datasmart-agent-autopilot-recovery}",
            autoStartup = "${datasmart.agent-runtime.autopilot-recovery.kafka.enabled:false}"
    )
    public void onRecoveryTrigger(String payload) {
        AgentAutopilotRecoveryExecutionResult result = consumerService.consume(payload);
        if ("RECOVERY_STARTED".equals(result.status())) {
            log.info("Autopilot recovery started, eventId={}, caseId={}, executionId={}",
                    result.eventId(), result.caseId(), result.currentExecutionId());
            return;
        }
        log.warn("Autopilot recovery did not start, eventId={}, status={}, reasonCode={}",
                result.eventId(), result.status(), result.reasonCode());
    }

    /**
     * Persists a bounded attention outcome after the listener retry policy routes a record to the DLT.
     *
     * <p>The handler parses only the event and execution identities from the original value, then calls a fixed
     * data-sync endpoint. data-sync owns the outbox and exact decision receipt, so it decides whether an executable
     * case must move to {@code ATTENTION_REQUIRED}; payload-supplied case IDs or versions are never trusted. Only
     * after that durable callback succeeds does this method record the metric, write broker metadata to the log,
     * and return so the DLT offset can be committed.</p>
     *
     * <p>The log intentionally excludes the payload, key, headers, planner result, authorization facts, and
     * exception message. Parsing or callback failures are converted to fixed technical exceptions and deliberately
     * escape the method. Combined with {@code DltStrategy.FAIL_ON_ERROR}, that keeps an unconverged DLT record
     * available for another handler attempt instead of silently losing the recovery case.</p>
     *
     * @param record DLT record carrying the original topic, partition, and offset metadata
     */
    @DltHandler
    public void onRecoveryTriggerDlt(ConsumerRecord<?, ?> record) {
        AgentAutopilotRecoveryTriggerEvent event = deadLetterEvent(record);
        dataSyncClient.recordTriggerDeadLettered(event.eventId(), event.currentExecutionId());
        metrics.recordDeadLettered();
        log.error("Autopilot recovery trigger routed to DLT, topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());
    }

    /**
     * Reads the original DLT value without logging it and extracts the two identities required for convergence.
     *
     * <p>Spring Kafka normally retains the listener's String value on retry and DLT topics, while a byte array can
     * appear with lower-level serializers. Both forms are decoded explicitly. Any other value type, malformed JSON,
     * blank event ID, or nonpositive execution ID becomes one fixed {@link IllegalStateException}; the raw value and
     * parser message are never copied into logs or exception text.</p>
     *
     * @param record dead-letter record delivered to the handler
     * @return parsed trigger whose identity fields are structurally usable
     */
    private AgentAutopilotRecoveryTriggerEvent deadLetterEvent(ConsumerRecord<?, ?> record) {
        if (record == null) {
            throw new IllegalStateException("AUTOPILOT_TRIGGER_DLT_RECORD_INVALID");
        }
        Object value = record.value();
        String payload;
        if (value instanceof String text) {
            payload = text;
        } else if (value instanceof byte[] bytes) {
            payload = new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new IllegalStateException("AUTOPILOT_TRIGGER_DLT_PAYLOAD_INVALID");
        }
        try {
            AgentAutopilotRecoveryTriggerEvent event =
                    objectMapper.readValue(payload, AgentAutopilotRecoveryTriggerEvent.class);
            if (event.eventId() == null || event.eventId().isBlank()
                    || event.currentExecutionId() == null || event.currentExecutionId() <= 0) {
                throw new IllegalStateException("AUTOPILOT_TRIGGER_DLT_IDENTITY_INVALID");
            }
            return event;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AUTOPILOT_TRIGGER_DLT_PAYLOAD_INVALID");
        }
    }
}
