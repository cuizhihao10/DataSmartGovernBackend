/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumerService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Agent runtime event 消费业务服务。
 *
 * <p>Kafka listener 只负责接收字符串 payload；真正的业务处理放在这里：
 * 1. 反序列化 Python AI Runtime 的 JSON；
 * 2. 校验 schemaVersion、事件类型、聚合字段；
 * 3. 生成幂等 identityKey；
 * 4. 写入控制面投影仓储；
 * 5. 返回结构化处理结果，供日志、测试和后续指标使用。</p>
 *
 * <p>为什么当前不直接修改 `AgentRunRecord`：
 * Python Runtime 产生的 runId/sessionId 目前还不一定等于 Java `AgentSessionService` 中的内存 runId/sessionId。
 * 如果现在强行回写 Java run 状态，可能制造“看起来闭环、实际 ID 没对齐”的假象。
 * 所以本阶段先落地事件投影，下一阶段再做 ID 对齐、run 状态映射和持久化审计。</p>
 */
@Slf4j
@Service
public class AgentRuntimeEventConsumerService {

    private static final String SUPPORTED_SCHEMA_VERSION = "agent-runtime-event.v1";

    private final ObjectMapper objectMapper;
    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventConsumerStats consumerStats;
    private final Optional<AgentSkillVisibilitySnapshotIndexStore> skillVisibilitySnapshotIndexStore;
    private final AgentSkillVisibilitySnapshotIndexTelemetry skillVisibilitySnapshotIndexTelemetry;
    private final Optional<AgentToolActionWorkerReceiptIndexService> workerReceiptIndexService;

    /**
     * Spring 运行时使用的构造方法。
     *
     * <p>`skillVisibilitySnapshotIndexStore` 是 Optional，是为了支持一个清晰的渐进开关：
     * - 开启专用索引时，consumer 会把 Skill 可见性快照从通用事件流中物化出来；
     * - 关闭专用索引时，系统仍可退回通用 runtime event projection 查询，保持向后兼容；
     * - 后续替换为 MySQL/ClickHouse 索引时，只需要提供同一接口的实现。</p>
     */
    @Autowired
    public AgentRuntimeEventConsumerService(ObjectMapper objectMapper,
                                            AgentRuntimeEventProjectionStore projectionStore,
                                            AgentRuntimeEventConsumerStats consumerStats,
                                            Optional<AgentSkillVisibilitySnapshotIndexStore> skillVisibilitySnapshotIndexStore,
                                            AgentSkillVisibilitySnapshotIndexTelemetry skillVisibilitySnapshotIndexTelemetry,
                                            Optional<AgentToolActionWorkerReceiptIndexService> workerReceiptIndexService) {
        this.objectMapper = objectMapper;
        this.projectionStore = projectionStore;
        this.consumerStats = consumerStats;
        this.skillVisibilitySnapshotIndexStore = skillVisibilitySnapshotIndexStore;
        this.skillVisibilitySnapshotIndexTelemetry = skillVisibilitySnapshotIndexTelemetry;
        this.workerReceiptIndexService = workerReceiptIndexService == null ? Optional.empty() : workerReceiptIndexService;
    }

    public AgentRuntimeEventConsumerService(ObjectMapper objectMapper,
                                            AgentRuntimeEventProjectionStore projectionStore,
                                            AgentRuntimeEventConsumerStats consumerStats,
                                            Optional<AgentSkillVisibilitySnapshotIndexStore> skillVisibilitySnapshotIndexStore) {
        this(objectMapper,
                projectionStore,
                consumerStats,
                skillVisibilitySnapshotIndexStore,
                AgentSkillVisibilitySnapshotIndexTelemetry.inMemoryOnly(),
                Optional.empty());
    }

    public AgentRuntimeEventConsumerService(ObjectMapper objectMapper,
                                            AgentRuntimeEventProjectionStore projectionStore,
                                            AgentRuntimeEventConsumerStats consumerStats,
                                            Optional<AgentSkillVisibilitySnapshotIndexStore> skillVisibilitySnapshotIndexStore,
                                            AgentSkillVisibilitySnapshotIndexTelemetry skillVisibilitySnapshotIndexTelemetry) {
        this(objectMapper,
                projectionStore,
                consumerStats,
                skillVisibilitySnapshotIndexStore,
                skillVisibilitySnapshotIndexTelemetry,
                Optional.empty());
    }

    /**
     * 单元测试和早期调用方兼容构造方法。
     *
     * <p>很多历史测试只关心通用 projection 消费语义，不需要专用 Skill 索引。保留该构造方法可以避免为了
     * 新增索引端口而重写大量无关测试，也体现“新增能力不破坏旧主路径”的演进原则。</p>
     */
    public AgentRuntimeEventConsumerService(ObjectMapper objectMapper,
                                            AgentRuntimeEventProjectionStore projectionStore,
                                            AgentRuntimeEventConsumerStats consumerStats) {
        this(objectMapper, projectionStore, consumerStats, Optional.empty());
    }

    /**
     * 处理 Kafka payload。
     *
     * @param payload Kafka message value，来自 Python AI Runtime publisher。
     * @return 消费结果，区分首次接收、重复投递和拒绝。
     */
    public AgentRuntimeEventConsumeResult consume(String payload) {
        long startedAt = System.nanoTime();
        AgentRuntimeEventConsumeResult result = doConsume(payload);
        consumerStats.record(result, System.nanoTime() - startedAt);
        return result;
    }

    private AgentRuntimeEventConsumeResult doConsume(String payload) {
        if (payload == null || payload.isBlank()) {
            return AgentRuntimeEventConsumeResult.rejected("EMPTY_PAYLOAD");
        }

        AgentRuntimeEventMessage message;
        try {
            message = objectMapper.readValue(payload, AgentRuntimeEventMessage.class);
        } catch (JsonProcessingException exception) {
            log.warn("Agent runtime event 反序列化失败，payload={}, error={}", payload, exception.getMessage());
            return AgentRuntimeEventConsumeResult.rejected("MALFORMED_JSON");
        }

        String validationError = validate(message);
        if (validationError != null) {
            log.warn("Agent runtime event 契约校验失败，reason={}, payload={}", validationError, payload);
            return AgentRuntimeEventConsumeResult.rejected(validationError);
        }

        AgentRuntimeEventProjectionRecord record = toProjectionRecord(message);
        boolean appended = projectionStore.append(record);
        if (!appended) {
            /*
             * 这里仍然尝试物化 Skill 可见性快照，是 6.19 阶段补上的一个可靠性细节：
             * projectionStore 是消费幂等的第一道闸门，但如果上一轮“projection 写入成功、专用索引写入失败”，
             * Kafka 重试时会先命中 projection duplicate。若此时直接返回 duplicate，MySQL 索引就永远没有补写机会。
             * 因此对 Skill 可见性事件，即使 projection 已存在，也允许专用索引再执行一次幂等 append。
             * 已存在的索引记录会由 identityKey 返回 false，不会放大统计事实。
             */
            materializeSkillVisibilitySnapshot(record);
            materializeWorkerReceiptIndex(record);
            return AgentRuntimeEventConsumeResult.duplicate(record.identityKey());
        }
        materializeSkillVisibilitySnapshot(record);
        materializeWorkerReceiptIndex(record);
        return AgentRuntimeEventConsumeResult.accepted(record.identityKey());
    }

    private void materializeWorkerReceiptIndex(AgentRuntimeEventProjectionRecord record) {
        /*
         * controlled dry-run receipt 当前主要由 agent-runtime HTTP 内部接口接收，但真实商业化控制面通常会出现
         * 事件重放、补偿导入、历史 projection 补物化等路径。这里把 Kafka consumer 也接上专用索引，
         * 保证“通用 projection 已经接收但 receipt index 写入失败”的场景可以在下一次重复消费时自愈。
         *
         * 和 Skill 可见性索引一样，物化时优先读取 projectionStore 中已经分配 replaySequence 的记录，
         * 这样专用索引与通用 timeline 使用同一套 Java 控制面游标。
         */
        if (!AgentToolActionControlledDryRunReceiptService.EVENT_TYPE.equals(record.eventType())
                || workerReceiptIndexService.isEmpty()) {
            return;
        }
        AgentRuntimeEventProjectionRecord storedRecord = projectionStore.findByIdentityKey(record.identityKey())
                .orElse(record);
        workerReceiptIndexService.get().materialize(storedRecord);
    }

    private void materializeSkillVisibilitySnapshot(AgentRuntimeEventProjectionRecord record) {
        /*
         * 这里故意发生在 projectionStore.append(...) 成功之后：
         * - 通用 projection 是消费者幂等主入口，重复消息应先在那里被拦住；
         * - 专用索引只物化首次接收的低敏快照，避免 Kafka 重放把统计数据放大；
         * - 物化时优先取 projectionStore 中已经分配 replaySequence 的记录，让专用索引和通用 replay 使用同一游标。
         */
        if (!AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE.equals(record.eventType())
                || skillVisibilitySnapshotIndexStore.isEmpty()) {
            if (AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE.equals(record.eventType())) {
                skillVisibilitySnapshotIndexTelemetry.recordSkipped(record, "index-store-missing");
            }
            return;
        }
        AgentRuntimeEventProjectionRecord storedRecord = projectionStore.findByIdentityKey(record.identityKey())
                .orElse(record);
        AgentSkillVisibilitySnapshotIndexStore store = skillVisibilitySnapshotIndexStore.get();
        String storeLabel = storeLabel(store);
        try {
            boolean indexed = store.append(storedRecord);
            if (indexed) {
                skillVisibilitySnapshotIndexTelemetry.recordMaterialized(storedRecord, storeLabel);
            } else {
                skillVisibilitySnapshotIndexTelemetry.recordDuplicate(storedRecord, storeLabel);
            }
        } catch (RuntimeException exception) {
            skillVisibilitySnapshotIndexTelemetry.recordMaterializationFailure(storedRecord, storeLabel, exception);
            throw exception;
        }
    }

    private String storeLabel(AgentSkillVisibilitySnapshotIndexStore store) {
        if (store instanceof InMemoryAgentSkillVisibilitySnapshotIndexStore) {
            return "memory";
        }
        if (store instanceof JdbcAgentSkillVisibilitySnapshotIndexStore) {
            return "mysql";
        }
        return "other";
    }

    /**
     * 校验消息是否满足 Java 控制面最低消费要求。
     *
     * <p>这里不是做完整 JSON Schema 校验，而是保护几个关键边界：
     * - schemaVersion 必须是当前支持版本；
     * - eventType 必须存在，否则无法路由、筛选和统计；
     * - 至少要有 runId/sessionId/requestId 之一，否则控制面无法把事件归到某个运行上下文。</p>
     */
    private String validate(AgentRuntimeEventMessage message) {
        if (message.getSchemaVersion() == null || !SUPPORTED_SCHEMA_VERSION.equals(message.getSchemaVersion())) {
            return "UNSUPPORTED_SCHEMA_VERSION";
        }
        if (message.getEventType() == null || message.getEventType().isBlank()) {
            return "MISSING_EVENT_TYPE";
        }
        if (isBlank(message.getRunId()) && isBlank(message.getSessionId()) && isBlank(message.getRequestId())) {
            return "MISSING_CORRELATION_ID";
        }
        return null;
    }

    private AgentRuntimeEventProjectionRecord toProjectionRecord(AgentRuntimeEventMessage message) {
        Instant consumedAt = Instant.now();
        String identityKey = buildIdentityKey(message);
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                message.getSchemaVersion(),
                message.getSource(),
                message.getEventType(),
                message.getStage(),
                message.getMessage(),
                normalizeSeverity(message.getSeverity()),
                message.getTenantId(),
                message.getProjectId(),
                message.getActorId(),
                message.getRequestId(),
                message.getRunId(),
                message.getSessionId(),
                message.getSequence(),
                message.getCreatedAt(),
                message.getPublishedAt(),
                consumedAt,
                copyAttributes(message.getAttributes())
        );
    }

    /**
     * 生成幂等键。
     *
     * <p>当前 Python payload 尚未提供独立 eventId，因此这里用多个业务字段组合：
     * source + runId/sessionId/requestId + sequence + eventType + createdAt。
     * 这不是最终形态，后续更推荐 Python publisher 直接生成 eventId，并由 Java 消费端使用 eventId 做唯一约束。</p>
     */
    private String buildIdentityKey(AgentRuntimeEventMessage message) {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(nullToDash(message.getSource()));
        joiner.add(nullToDash(message.getRunId()));
        joiner.add(nullToDash(message.getSessionId()));
        joiner.add(nullToDash(message.getRequestId()));
        joiner.add(message.getSequence() == null ? "-" : String.valueOf(message.getSequence()));
        joiner.add(nullToDash(message.getEventType()));
        joiner.add(message.getCreatedAt() == null ? "-" : message.getCreatedAt().toString());
        return joiner.toString();
    }

    private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(attributes);
    }

    private String normalizeSeverity(String severity) {
        return severity == null || severity.isBlank() ? "info" : severity.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
