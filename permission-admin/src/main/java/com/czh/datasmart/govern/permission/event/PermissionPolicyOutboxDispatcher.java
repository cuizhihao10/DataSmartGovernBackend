/**
 * @Author : Cui
 * @Date: 2026/04/27 20:12
 * @Description DataSmart Govern Backend - PermissionPolicyOutboxDispatcher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.event;

import com.czh.datasmart.govern.permission.config.PermissionPolicyEventProperties;
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
 * 权限策略 outbox 投递器。
 *
 * <p>它定期扫描 permission_event_outbox 表，把待发送事件投递到 Kafka。
 * 相比业务线程内直接发送 Kafka，这种做法把“业务事务提交”和“消息投递”解耦：
 * 业务接口只要完成数据库事务即可返回，Kafka 短暂不可用时事件也不会丢失。
 *
 * <p>当前第一版采用单表扫描 + 条件状态推进，适合早期商业化基线。
 * 后续如果事件量变大，可以继续优化为分片扫描、独立 outbox worker、指标告警、
 * DEAD 事件管理 API、更精细的指数退避和死信队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionPolicyOutboxDispatcher {

    private final PermissionPolicyEventProperties eventProperties;
    private final PermissionEventOutboxMapper eventOutboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 定时投递 outbox 事件。
     *
     * <p>fixedDelayString 使用毫秒配置，是为了让本地调试可以简单调小频率。
     * 如果 dispatcherEnabled=false 或 enabled=false，方法会快速返回，不做任何数据库扫描。
     */
    @Scheduled(fixedDelayString = "${datasmart.permission.policy-events.dispatch-fixed-delay-ms:5000}")
    public void dispatch() {
        if (!eventProperties.isEnabled() || !eventProperties.isDispatcherEnabled()) {
            return;
        }

        recoverStaleSendingEvents();
        List<PermissionEventOutbox> events = eventOutboxMapper.selectDispatchable(eventProperties.getDispatchBatchSize());
        for (PermissionEventOutbox event : events) {
            dispatchOne(event);
        }
    }

    /**
     * 投递单条事件。
     *
     * <p>先 markSending 是为了“声明处理权”。
     * 如果多个投递器同时运行，只有成功把状态从 PENDING/FAILED 改为 SENDING 的实例才能继续发送。
     */
    private void dispatchOne(PermissionEventOutbox event) {
        int claimed = eventOutboxMapper.markSending(event.getId());
        if (claimed == 0) {
            return;
        }

        try {
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayloadJson())
                    .get(eventProperties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            eventOutboxMapper.markSent(event.getId());
            log.info("权限策略 outbox 事件投递成功，eventId={}, eventType={}, topic={}",
                    event.getEventId(), event.getEventType(), event.getTopic());
        } catch (Exception exception) {
            String message = truncateError(exception.getMessage());
            eventOutboxMapper.markFailed(event.getId(), message, eventProperties.getRetryDelay().toSeconds());
            log.warn("权限策略 outbox 事件投递失败，eventId={}, eventType={}, error={}",
                    event.getEventId(), event.getEventType(), message);
        }
    }

    /**
     * 恢复长时间卡在 SENDING 的事件。
     */
    private void recoverStaleSendingEvents() {
        int recovered = eventOutboxMapper.recoverStaleSending(eventProperties.getSendingTimeout().toSeconds());
        if (recovered > 0) {
            log.warn("已恢复超时 SENDING 权限 outbox 事件，count={}", recovered);
        }
    }

    /**
     * 截断错误信息。
     *
     * <p>数据库 last_error 字段有限长，不能直接写入超长堆栈。
     */
    private String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
