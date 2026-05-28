/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * Java 控制面保存的 Agent runtime event 投影记录。
 *
 * <p>它不是 Kafka 原始消息的简单复制，而是 Java agent-runtime 为“运行详情页、状态同步、审计索引”
 * 准备的控制面视图。当前第一版保存在内存里，后续可以平滑迁移到 MySQL、ClickHouse 或审计中心。</p>
 *
 * <p>`identityKey` 是本阶段非常关键的字段：Kafka consumer 在 rebalance、ack 丢失、实例重启后可能重复收到同一条消息。
 * 如果没有幂等键，控制面详情页会出现重复事件，甚至后续状态机被重复推进。
 * Python payload 当前没有独立 eventId，因此 Java 侧用 source/run/session/request/sequence/eventType/createdAt 组合生成近似稳定键。</p>
 *
 * <p>`sequence` 与 `replaySequence` 要刻意区分：
 * `sequence` 是事件生产方带来的原始序号，通常只在 Python 单次 Agent plan 内连续；
 * `replaySequence` 是 Java 控制面写入投影时分配的稳定回放游标，面向 HTTP replay、WebSocket 断线续传和审计回放。
 * 这样做可以避免把不同来源的局部序号误认为全局序号，也为后续迁移到 MySQL/Redis Stream/Kafka compacted log
 * 预留统一游标字段。</p>
 */
public record AgentRuntimeEventProjectionRecord(
        String identityKey,
        String schemaVersion,
        String source,
        String eventType,
        String stage,
        String message,
        String severity,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        Long sequence,
        Long replaySequence,
        Instant createdAt,
        Instant publishedAt,
        Instant consumedAt,
        Map<String, Object> attributes
) {

    /**
     * 兼容早期投影写入点的构造方法。
     *
     * <p>4.24 之前投影记录只有生产者原始 `sequence`。新增 replaySequence 后保留该构造方法，
     * 可以让现有消费、工具事件投影和测试逐步迁移；真正的稳定 replaySequence 会在
     * {@link AgentRuntimeEventProjectionStore#append(AgentRuntimeEventProjectionRecord)} 写入时统一补齐。</p>
     */
    public AgentRuntimeEventProjectionRecord(String identityKey,
                                             String schemaVersion,
                                             String source,
                                             String eventType,
                                             String stage,
                                             String message,
                                             String severity,
                                             String tenantId,
                                             String projectId,
                                             String actorId,
                                             String requestId,
                                             String runId,
                                             String sessionId,
                                             Long sequence,
                                             Instant createdAt,
                                             Instant publishedAt,
                                             Instant consumedAt,
                                             Map<String, Object> attributes) {
        this(identityKey, schemaVersion, source, eventType, stage, message, severity,
                tenantId, projectId, actorId, requestId, runId, sessionId,
                sequence, null, createdAt, publishedAt, consumedAt, attributes);
    }

    /**
     * 返回带指定 replaySequence 的新记录。
     *
     * <p>record 是不可变对象，仓储层分配游标时不能原地修改字段。
     * 使用该方法能把“游标分配”限制在 store 写入边界，避免 controller、consumer 或 sink 各自生成不同游标。</p>
     */
    public AgentRuntimeEventProjectionRecord withReplaySequence(Long assignedReplaySequence) {
        return new AgentRuntimeEventProjectionRecord(
                identityKey,
                schemaVersion,
                source,
                eventType,
                stage,
                message,
                severity,
                tenantId,
                projectId,
                actorId,
                requestId,
                runId,
                sessionId,
                sequence,
                assignedReplaySequence,
                createdAt,
                publishedAt,
                consumedAt,
                attributes
        );
    }
}
