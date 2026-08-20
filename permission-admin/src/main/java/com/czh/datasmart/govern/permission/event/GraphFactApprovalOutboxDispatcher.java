/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalOutboxDispatcher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.event;

import com.czh.datasmart.govern.permission.config.GraphFactApprovalEventProperties;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.mapper.PermissionEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 业务图事实审批 outbox 投递器。
 *
 * <p>它复用 permission_event_outbox 的状态机和审计查询，但通过 event_type 精确筛选图事实消息。
 * Kafka 暂时不可用时，消息仍保留在数据库，后续由重试或管理员补偿，不会让审批证据丢失。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphFactApprovalOutboxDispatcher {

    private final GraphFactApprovalEventProperties properties;
    private final PermissionEventOutboxMapper mapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 定时扫描图事实审批消息。 */
    @Scheduled(fixedDelayString = "${datasmart.permission.graph-fact-events.dispatch-fixed-delay-ms:5000}")
    public void dispatch() {
        if (!properties.isEnabled() || !properties.isDispatcherEnabled()) {
            return;
        }
        mapper.recoverStaleSending(properties.getSendingTimeout().toSeconds());
        List<PermissionEventOutbox> events = mapper.selectGraphFactDispatchable(properties.getDispatchBatchSize());
        for (PermissionEventOutbox event : events) {
            dispatchOne(event);
        }
    }

    private void dispatchOne(PermissionEventOutbox event) {
        if (mapper.markSending(event.getId()) == 0) {
            return;
        }
        try {
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayloadJson())
                    .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            mapper.markSent(event.getId());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "unknown error" : exception.getMessage();
            mapper.markFailed(event.getId(), message.substring(0, Math.min(1000, message.length())),
                    properties.getRetryDelay().toSeconds());
            log.warn("图事实审批 Kafka 事件投递失败，eventId={}", event.getEventId());
        }
    }
}
