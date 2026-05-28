/**
 * @Author : Cui
 * @Date: 2026/05/28 02:10
 * @Description DataSmart Govern Backend - KafkaAgentToolExecutionEventSink.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventProperties;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Kafka 的 Agent 工具执行事件下游 sink。
 *
 * <p>该类只负责把 {@link DefaultAgentToolExecutionEventPublisher} 已经构造好的统一事件写入 Kafka。
 * 也就是说，Kafka 只是事件的一个投递渠道，而不是工具状态事件契约的拥有者。这个边界非常重要：
 * 后续即使改成事务 outbox、Pulsar、审计中心直写或多 broker 灰度，业务状态机和事件契约都不需要重写。</p>
 *
 * <p>为什么当前仍投递 JSON 字符串？</p>
 * <p>1. Python Runtime、智能网关、审计中心等消费方不一定是 Java，JSON 是最容易跨语言消费的契约。</p>
 * <p>2. 避免 Spring Kafka JsonSerializer 自动携带 Java 类型头，降低非 Java 消费者解析成本。</p>
 * <p>3. Kafka UI、日志平台和命令行工具可以直接查看 payload，便于学习、联调和生产排障。</p>
 *
 * <p>当前实现仍是状态变更后的异步通知，不是强事务 outbox。Kafka 投递失败不会回滚工具状态，
 * 但会记录告警；商业化强一致版本应继续补充 outbox 表、后台投递器、重试、死信和重放能力。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.tool-execution-events",
        name = "enabled",
        havingValue = "true"
)
public class KafkaAgentToolExecutionEventSink implements AgentToolExecutionEventSink {

    /**
     * Kafka topic 和 source 等发布配置。
     *
     * <p>是否启用 Kafka sink 由 {@code enabled} 控制；默认关闭是为了让本地开发和单元测试不依赖 broker。
     * 但注意：关闭 Kafka 不会关闭本地投影 sink，因此控制面仍能看到 JVM 内热窗口中的工具状态事件。</p>
     */
    private final AgentToolExecutionEventProperties properties;

    /**
     * Spring Kafka 提供的字符串消息生产者。
     *
     * <p>key 使用事件的 partitionKey，尽量让同一个 run/session/audit 的状态变化落到同一分区，
     * 消费侧才更容易按顺序重建工具执行过程。</p>
     */
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * JSON 序列化器。
     *
     * <p>复用 Spring 容器中的 ObjectMapper，确保 JavaTimeModule、字段命名策略等全局配置一致。</p>
     */
    private final ObjectMapper objectMapper;

    /**
     * 将工具状态事件写入 Kafka。
     *
     * <p>方法内部只捕获序列化异常；异步发送失败通过 whenComplete 记录日志。
     * 如果 KafkaTemplate 在 send 阶段同步抛出 RuntimeException，外层统一发布器会捕获并隔离，
     * 不会影响其他 sink。</p>
     */
    @Override
    public void accept(AgentToolExecutionState previousState,
                       AgentToolExecutionAuditRecord record,
                       AgentToolExecutionStateChangedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(properties.getTopic(), event.partitionKey(), payload)
                    .whenComplete((result, error) -> {
                        if (error == null) {
                            log.debug("Agent 工具执行状态事件已投递，topic={}, eventId={}, auditId={}, state={}",
                                    properties.getTopic(), event.eventId(), event.auditId(), event.currentState());
                            return;
                        }
                        log.warn("Agent 工具执行状态事件投递失败，topic={}, eventId={}, auditId={}, state={}, error={}",
                                properties.getTopic(), event.eventId(), event.auditId(), event.currentState(), error.getMessage());
                    });
        } catch (JsonProcessingException exception) {
            log.warn("Agent 工具执行状态事件序列化失败，auditId={}, state={}, error={}",
                    record.getAuditId(), record.getState(), exception.getMessage());
        }
    }
}
