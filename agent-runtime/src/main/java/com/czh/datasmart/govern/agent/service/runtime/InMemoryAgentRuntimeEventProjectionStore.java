/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - InMemoryAgentRuntimeEventProjectionStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeEventConsumerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 Agent runtime event 投影仓储。
 *
 * <p>该实现用于当前控制面过渡阶段：我们已经有 Kafka 消费入口，但还没有为 Agent runtime events
 * 设计长期审计表、冷热分层和查询索引。内存实现能先让 Java 控制面“看见并去重”Python 事件，
 * 同时避免为了过早建表而锁死事件模型。</p>
 *
 * <p>生产边界必须明确：
 * - JVM 重启会丢失投影；
 * - 多实例之间不共享投影；
 * - 只适合作为在线热窗口，不适合作为长期审计事实；
 * - 事件风暴时依赖 maxTotalEvents/maxEventsPerRun 做保守裁剪。</p>
 */
@Component
public class InMemoryAgentRuntimeEventProjectionStore implements AgentRuntimeEventProjectionStore {

    /**
     * 全局事件表，key 为 identityKey。
     *
     * <p>使用 LinkedHashMap 是为了保留插入顺序，超过全局上限时可以稳定裁剪最早事件。</p>
     */
    private final Map<String, AgentRuntimeEventProjectionRecord> recordsByIdentityKey = new LinkedHashMap<>();

    /**
     * runId -> identityKey 队列。
     *
     * <p>保存 identityKey 而不是直接保存 record，是为了避免同一条事件在两个集合中重复持有大对象。
     * 当前 attributes 可能变大，后续还需要做 payload 大小限制和敏感字段脱敏。</p>
     */
    private final Map<String, Deque<String>> identityKeysByRunId = new LinkedHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxEventsPerRun;
    private final int maxTotalEvents;
    /**
     * Java 控制面内存投影的稳定 replaySequence 分配器。
     *
     * <p>这里使用单调递增的 long，而不是复用 Python 事件里的 producer sequence。
     * 原因是 producer sequence 可能只在某次 Python plan 内连续，Java 工具状态事件甚至可能没有 sequence。
     * replaySequence 则表达“Java 控制面接收/投影这些事件的顺序”，用于 HTTP replay、WebSocket 断线续传和外部 source cursor。
     *
     * <p>当前是内存实现，JVM 重启后会从 1 重新开始；这与内存热窗口定位一致。
     * 后续切到 MySQL/Redis Stream 时，应改由数据库自增 ID、Redis Stream ID 或专用 cursor 表生成稳定游标。</p>
     */
    private long nextReplaySequence = 1L;

    /**
     * Spring 生产运行时使用的构造器。
     *
     * <p>该类同时保留了一个接收两个 int 参数的测试构造器，用于单元测试快速构造小窗口仓储。
     * 当一个 Spring Bean 存在多个构造器时，Spring 无法仅凭参数类型判断哪个构造器代表“正式依赖注入入口”，
     * 因此这里显式标注 {@link Autowired}：生产运行时从
     * {@link AgentRuntimeEventConsumerProperties} 读取窗口上限，测试代码仍可继续使用轻量构造器。</p>
     *
     * @param properties Agent runtime event 消费与投影配置，包含单个 run 的事件窗口上限和全局事件窗口上限。
     */
    @Autowired
    public InMemoryAgentRuntimeEventProjectionStore(AgentRuntimeEventConsumerProperties properties) {
        this.maxEventsPerRun = Math.max(1, properties.getMaxEventsPerRun());
        this.maxTotalEvents = Math.max(1, properties.getMaxTotalEvents());
    }

    public InMemoryAgentRuntimeEventProjectionStore(int maxEventsPerRun, int maxTotalEvents) {
        this.maxEventsPerRun = Math.max(1, maxEventsPerRun);
        this.maxTotalEvents = Math.max(1, maxTotalEvents);
    }

    @Override
    public boolean append(AgentRuntimeEventProjectionRecord record) {
        lock.writeLock().lock();
        try {
            if (recordsByIdentityKey.containsKey(record.identityKey())) {
                return false;
            }
            AgentRuntimeEventProjectionRecord storedRecord = assignReplaySequence(record);
            recordsByIdentityKey.put(storedRecord.identityKey(), storedRecord);
            appendRunIndex(storedRecord);
            trimGlobalWindow();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentRuntimeEventProjectionRecord> findByIdentityKey(String identityKey) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByIdentityKey.get(identityKey));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentRuntimeEventProjectionRecord> listByRunId(String runId) {
        lock.readLock().lock();
        try {
            Deque<String> keys = identityKeysByRunId.get(runId);
            if (keys == null || keys.isEmpty()) {
                return List.of();
            }
            List<AgentRuntimeEventProjectionRecord> records = new ArrayList<>();
            for (String key : keys) {
                AgentRuntimeEventProjectionRecord record = recordsByIdentityKey.get(key);
                if (record != null) {
                    records.add(record);
                }
            }
            return records;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentRuntimeEventProjectionRecord> query(AgentRuntimeEventProjectionQuery query) {
        List<String> authorizedProjectIds = query.normalizedAuthorizedProjectIds();
        lock.readLock().lock();
        try {
            return recordsByIdentityKey.values().stream()
                    /*
                     * PROJECT 数据范围的关键点：如果 authorizedProjectIds 是空集合，stream 会自然返回空结果。
                     * 这比在调用方“跳过项目过滤”安全得多，因为 permission-admin 明确说 PROJECT 范围但没有项目时，
                     * 业务含义应是“看不到任何项目事件”，而不是“退化成租户全量事件”。
                     */
                    .filter(record -> authorizedProjectIds == null || authorizedProjectIds.contains(record.projectId()))
                    .filter(record -> matches(query.tenantId(), record.tenantId()))
                    .filter(record -> matches(query.projectId(), record.projectId()))
                    .filter(record -> matches(query.actorId(), record.actorId()))
                    .filter(record -> matches(query.requestId(), record.requestId()))
                    .filter(record -> matches(query.runId(), record.runId()))
                    .filter(record -> matches(query.sessionId(), record.sessionId()))
                    .filter(record -> matches(query.eventType(), record.eventType()))
                    .filter(record -> matches(query.severity(), record.severity()))
                    /*
                     * afterSequence 使用 Java 控制面分配的 replaySequence，而不是事件生产者原始 sequence。
                     * 这样 Python replay client 可以把 Java source cursor 下推回来，避免每次 WebSocket 重连都重复读取
                     * 已经被前端确认过的 Java 工具状态事件。
                     */
                    .filter(record -> record.replaySequence() != null
                            && record.replaySequence() > query.normalizedAfterSequence())
                    .limit(query.normalizedLimit())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return recordsByIdentityKey.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean matches(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equals(actual);
    }

    private AgentRuntimeEventProjectionRecord assignReplaySequence(AgentRuntimeEventProjectionRecord record) {
        Long existingReplaySequence = record.replaySequence();
        if (existingReplaySequence != null && existingReplaySequence > 0) {
            nextReplaySequence = Math.max(nextReplaySequence, existingReplaySequence + 1);
            return record;
        }
        return record.withReplaySequence(nextReplaySequence++);
    }

    private void appendRunIndex(AgentRuntimeEventProjectionRecord record) {
        if (record.runId() == null || record.runId().isBlank()) {
            return;
        }
        Deque<String> keys = identityKeysByRunId.computeIfAbsent(record.runId(), ignored -> new ArrayDeque<>());
        keys.addLast(record.identityKey());
        while (keys.size() > maxEventsPerRun) {
            String removedKey = keys.removeFirst();
            recordsByIdentityKey.remove(removedKey);
        }
    }

    private void trimGlobalWindow() {
        while (recordsByIdentityKey.size() > maxTotalEvents) {
            String oldestKey = recordsByIdentityKey.keySet().iterator().next();
            AgentRuntimeEventProjectionRecord removed = recordsByIdentityKey.remove(oldestKey);
            removeFromRunIndex(removed, oldestKey);
        }
    }

    private void removeFromRunIndex(AgentRuntimeEventProjectionRecord removed, String removedKey) {
        if (removed == null || removed.runId() == null || removed.runId().isBlank()) {
            return;
        }
        Deque<String> keys = identityKeysByRunId.get(removed.runId());
        if (keys == null) {
            return;
        }
        keys.remove(removedKey);
        if (keys.isEmpty()) {
            identityKeysByRunId.remove(removed.runId());
        }
    }
}
