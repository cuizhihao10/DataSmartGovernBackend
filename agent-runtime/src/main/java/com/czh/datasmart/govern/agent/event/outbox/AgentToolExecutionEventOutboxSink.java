/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxSink.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionEventSink;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionStateChangedEvent;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 将工具执行状态事件写入 outbox 的 sink。
 *
 * <p>该 sink 是工具事件发布链路里的“可靠性入口”。它不替代 Kafka sink，也不替代 runtime-event 投影 sink，
 * 而是在所有下游投递之前先把同一条事件记录进 outbox 热窗口。后续当我们实现数据库版 store 和后台 dispatcher 后，
 * outbox 就可以成为 Kafka/WebSocket/审计中心投递的事实源。</p>
 *
 * <p>为什么设置最高优先级？因为 outbox 的职责是先捕获事件事实，再让其他 sink 做投递。
 * 如果 Kafka sink 先执行并失败，而 outbox 还没有写入，那么故障窗口内仍然可能丢失补偿依据。
 * 当 audit/outbox 都启用 MySQL 且数据库总开关打开时，该 sink 会自动升级为“必达 sink”：
 * outbox 写入失败会向上抛出异常，让外层 JDBC 事务回滚审计状态。默认 memory 模式仍保持 fail-open，
 * 这是为了避免没有事务兜底时出现“接口失败但内存状态已改变”的学习环境困惑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.tool-execution-events.outbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AgentToolExecutionEventOutboxSink implements AgentToolExecutionEventSink {

    private final AgentToolExecutionEventOutboxProperties properties;
    private final AgentRuntimePersistenceProperties persistenceProperties;
    private final AgentToolExecutionEventOutboxStore outboxStore;
    private final ObjectMapper objectMapper;

    /**
     * 声明 outbox 是否必须在状态提交前成功写入。
     *
     * <p>这里采用“配置强制 + MySQL 双仓储自动开启”的组合：
     * 1. {@code requiredForStateCommit=true} 可用于灰度或压测场景手动强制；
     * 2. {@code audit-store=mysql + outbox-store=mysql + database-enabled=true} 表示已经具备真正事务 outbox 条件，
     *    因此自动变成必达 sink，避免生产环境忘记额外打开一个开关。</p>
     *
     * @return true 表示写入 outbox 失败时，发布器应抛出异常并交给服务层事务边界回滚。
     */
    @Override
    public boolean requiredForStateCommit() {
        return properties.isRequiredForStateCommit()
                || persistenceProperties.isStateAndOutboxMysqlEnabled();
    }

    /**
     * 接收统一发布器生成的工具状态事件并追加到 outbox。
     *
     * <p>方法只依赖已经构造好的 {@link AgentToolExecutionStateChangedEvent}，不重新读取审计内存仓储。
     * 这样可以保证 Kafka、projection、outbox 看到的是同一份 eventId 和同一份脱敏契约，避免多出口字段漂移。</p>
     */
    @Override
    public void accept(AgentToolExecutionState previousState,
                       AgentToolExecutionAuditRecord record,
                       AgentToolExecutionStateChangedEvent event) {
        Instant now = Instant.now();
        AgentToolExecutionEventOutboxRecord outboxRecord = buildOutboxRecord(event, now);
        boolean appended = outboxStore.append(outboxRecord);
        if (!appended) {
            log.debug("Agent 工具状态事件 outbox 已存在，跳过重复写入，outboxId={}, eventId={}, auditId={}, state={}",
                    outboxRecord.outboxId(), event.eventId(), event.auditId(), event.currentState());
            return;
        }
        log.debug("Agent 工具状态事件已写入 outbox，outboxId={}, eventId={}, auditId={}, state={}, outboxStatus={}",
                outboxRecord.outboxId(), event.eventId(), event.auditId(), event.currentState(), outboxRecord.status());
    }

    private AgentToolExecutionEventOutboxRecord buildOutboxRecord(AgentToolExecutionStateChangedEvent event,
                                                                  Instant now) {
        try {
            String payloadJson = objectMapper.writeValueAsString(event);
            int payloadSizeBytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
            if (payloadSizeBytes > properties.getMaxPayloadBytes()) {
                return AgentToolExecutionEventOutboxRecord.blocked(
                        event,
                        buildPayloadPreview(event, payloadJson),
                        payloadSizeBytes,
                        "payload 超过 outbox 最大字节数限制，maxPayloadBytes=" + properties.getMaxPayloadBytes(),
                        now
                );
            }
            return AgentToolExecutionEventOutboxRecord.pending(event, payloadJson, payloadSizeBytes, now);
        } catch (JsonProcessingException exception) {
            return AgentToolExecutionEventOutboxRecord.blocked(
                    event,
                    "{\"eventId\":\"" + safe(event.eventId()) + "\",\"serializationFailed\":true}",
                    0,
                    "工具状态事件序列化失败: " + exception.getOriginalMessage(),
                    now
            );
        }
    }

    /**
     * 生成安全的 payload 预览。
     *
     * <p>被阻断的记录仍需要保留足够诊断信息，但不能把完整超大 payload 继续塞进 outbox。
     * 这里保留 eventId、auditId、runId、toolCode、currentState 和 payload 前缀，便于定位问题，同时避免内存继续膨胀。</p>
     */
    private String buildPayloadPreview(AgentToolExecutionStateChangedEvent event, String payloadJson) {
        int previewLength = Math.min(payloadJson.length(), 2048);
        return "{"
                + "\"eventId\":\"" + safe(event.eventId()) + "\","
                + "\"auditId\":\"" + safe(event.auditId()) + "\","
                + "\"runId\":\"" + safe(event.runId()) + "\","
                + "\"toolCode\":\"" + safe(event.toolCode()) + "\","
                + "\"currentState\":\"" + safe(event.currentState()) + "\","
                + "\"payloadPreview\":\"" + safe(payloadJson.substring(0, previewLength)) + "\""
                + "}";
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
