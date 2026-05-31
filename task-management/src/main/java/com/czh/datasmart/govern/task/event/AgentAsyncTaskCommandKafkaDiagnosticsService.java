/**
 * @Author : Cui
 * @Date: 2026/05/31 23:13
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaDiagnosticsService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import com.czh.datasmart.govern.task.config.AgentAsyncTaskCommandKafkaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 异步工具命令 Kafka 消费诊断服务。
 *
 * <p>当前实现选择“进程内有界窗口 + 类型计数”，是一个刻意克制的第一阶段：
 * 它能让我们立即知道坏消息出现在哪里，又不会过早引入真实 DLQ Producer、额外数据库表、
 * 重放状态机和权限审批入口。这样既能提升当前可观测性，也不会把项目拖入另一个大模块。</p>
 *
 * <p>线程安全说明：Kafka listener、手动测试入口和未来并发消费者都可能同时记录失败。
 * 因此本服务使用 synchronized 包住内存结构修改和快照复制。这里的数据量很小，
 * 锁粒度可接受；后续如果失败量很大，应迁移到 Micrometer counter + 持久化事件表。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncTaskCommandKafkaDiagnosticsService {

    private final AgentAsyncTaskCommandKafkaProperties properties;

    /**
     * 最近失败样本窗口。
     *
     * <p>ArrayDeque 适合做固定容量队列：尾部追加最新失败，头部移除最旧失败。
     * 这里不保存 payload，只保存分类、原因和字节数，避免诊断服务成为敏感数据缓存。</p>
     */
    private final ArrayDeque<AgentAsyncTaskCommandKafkaFailureRecord> recentFailures = new ArrayDeque<>();

    /**
     * 按失败类型聚合的累计计数。
     *
     * <p>EnumMap 比普通 HashMap 更适合枚举 key：内存更小，语义更清晰，也能提醒维护者
     * “失败类型应该来自稳定枚举，而不是随意字符串”。</p>
     */
    private final EnumMap<AgentAsyncTaskCommandKafkaFailureType, Long> failuresByType =
            new EnumMap<>(AgentAsyncTaskCommandKafkaFailureType.class);

    private long totalFailures;
    private long dlqCandidateFailures;

    /**
     * 记录一次 Kafka 消费失败。
     *
     * @param type 稳定失败类型，用于后续指标、告警和 DLQ 分类。
     * @param reason 可读失败原因，应该足够帮助开发者定位，但不能包含完整 payload。
     * @param payloadBytes payload 的 UTF-8 字节数，未知时可传 0。
     */
    public synchronized void recordFailure(AgentAsyncTaskCommandKafkaFailureType type,
                                           String reason,
                                           int payloadBytes) {
        recordFailure(type, reason, payloadBytes, AgentAsyncTaskCommandKafkaRecordMetadata.empty());
    }

    /**
     * 记录带 Kafka record 元数据的消费失败。
     *
     * @param type 稳定失败类型，用于后续指标、告警和 DLQ 分类。
     * @param reason 可读失败原因，不能包含完整 payload。
     * @param payloadBytes payload 的 UTF-8 字节数。
     * @param metadata Kafka record 的低敏定位元数据，帮助运维定位 topic/partition/offset。
     */
    public synchronized void recordFailure(AgentAsyncTaskCommandKafkaFailureType type,
                                           String reason,
                                           int payloadBytes,
                                           AgentAsyncTaskCommandKafkaRecordMetadata metadata) {
        if (!properties.isDiagnosticsEnabled()) {
            return;
        }
        boolean dlqCandidate = properties.isDlqEnabled();
        AgentAsyncTaskCommandKafkaFailureRecord record = new AgentAsyncTaskCommandKafkaFailureRecord(
                "kafka-failure-" + UUID.randomUUID(),
                type,
                reason,
                Math.max(0, payloadBytes),
                dlqCandidate,
                metadata == null ? AgentAsyncTaskCommandKafkaRecordMetadata.empty() : metadata,
                LocalDateTime.now()
        );
        totalFailures++;
        if (dlqCandidate) {
            dlqCandidateFailures++;
        }
        failuresByType.merge(type, 1L, Long::sum);
        appendRecentFailure(record);
    }

    /**
     * 读取当前诊断快照。
     *
     * <p>返回前会复制 Map 和 List，避免 Controller 或测试拿到内部可变集合后破坏服务状态。
     * 这也是企业后端常见的小习惯：服务内部状态和对外 DTO 之间尽量不要共享可变引用。</p>
     */
    public synchronized AgentAsyncTaskCommandKafkaDiagnosticsSnapshot snapshot() {
        return new AgentAsyncTaskCommandKafkaDiagnosticsSnapshot(
                properties.isDiagnosticsEnabled(),
                properties.isDlqEnabled(),
                properties.getDlqTopic(),
                normalizedMaxRecentFailures(),
                totalFailures,
                dlqCandidateFailures,
                Map.copyOf(failuresByType),
                List.copyOf(new ArrayList<>(recentFailures)),
                LocalDateTime.now()
        );
    }

    private void appendRecentFailure(AgentAsyncTaskCommandKafkaFailureRecord record) {
        recentFailures.addLast(record);
        int maxRecentFailures = normalizedMaxRecentFailures();
        while (recentFailures.size() > maxRecentFailures) {
            recentFailures.removeFirst();
        }
    }

    private int normalizedMaxRecentFailures() {
        return Math.max(1, properties.getMaxRecentFailures());
    }
}
