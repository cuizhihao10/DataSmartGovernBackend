/**
 * @Author : Cui
 * @Date: 2026/08/11 18:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerKafkaDispatcher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 将已持久化的恢复触发事件发送到 Kafka。
 *
 * <p>dispatcher 不解析 payload，也不重新做业务决策；它只使用固定 topic 和 syncTaskId 分区键。
 * 固定分区键能让同一同步任务的多轮恢复事件保持有序，减少并发循环互相覆盖状态的风险。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncAutopilotRecoveryTriggerKafkaDispatcher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SyncAutopilotRecoveryTriggerProperties properties;

    /**
     * Sends one already-persisted outbox record and waits for the Kafka broker acknowledgement.
     *
     * <p>The outbox supplies a trusted, previously serialized low-sensitive payload; this dispatcher neither
     * parses it nor makes another policy decision. {@code syncTaskId} is the fixed partition key, preserving
     * ordering for recovery rounds of one task. A successful broker acknowledgement is the only result: the
     * caller owns the subsequent durable {@code DELIVERED} update.</p>
     *
     * <p>The method can be invoked again after a transport failure because the outbox service owns retry and
     * idempotency. Kafka delivery is at least once, so downstream consumers must still deduplicate by event
     * identity. Failures intentionally escape to the caller, which records bounded retry/dead-letter state;
     * no topic, payload, broker address, or credential is accepted from an untrusted caller.</p>
     *
     * @param outbox persisted event containing the fixed payload and task partition key to deliver
     * @throws Exception when the broker rejects the send or does not acknowledge it before the configured timeout
     */
    public void dispatch(SyncAutopilotRecoveryTriggerOutbox outbox) throws Exception {
        kafkaTemplate.send(
                        properties.getTopic(),
                        String.valueOf(outbox.getSyncTaskId()),
                        outbox.getPayloadJson())
                .get(Math.max(1L, properties.getSendTimeoutMs()), TimeUnit.MILLISECONDS);
    }
}
