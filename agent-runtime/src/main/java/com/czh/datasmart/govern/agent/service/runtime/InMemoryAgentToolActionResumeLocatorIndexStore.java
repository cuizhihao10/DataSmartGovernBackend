/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionResumeLocatorIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版工具动作恢复 locator index。
 *
 * <p>它的目标是先验证“checkpoint/thread 可以反查 Java 控制面事实定位符”的业务语义，而不是宣称已经生产可用。
 * 和 runtime event projection、command outbox 的内存实现一样，本实现适合本地学习、单元测试和单实例联调；
 * 商业化部署应升级到 MySQL 或审计投影表，并给 checkpointId/threadId 建索引和保留期清理策略。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.tool-action-resume-facts.locator-index-store:memory}'.equalsIgnoreCase('memory')"
)
public class InMemoryAgentToolActionResumeLocatorIndexStore implements AgentToolActionResumeLocatorIndexStore {

    private final Map<String, AgentToolActionResumeLocatorIndexRecord> recordsByCheckpointId = new LinkedHashMap<>();
    private final Map<String, AgentToolActionResumeLocatorIndexRecord> recordsByThreadId = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 写入或合并 locator。
     *
     * <p>合并顺序是“先 checkpointId，再 threadId”：checkpointId 通常更精确；如果只有 threadId，也能支撑
     * “查该 thread 最近暂停点”的恢复预检。两个索引都指向同一份合并后的记录，避免字段漂移。</p>
     */
    @Override
    public void upsert(AgentToolActionResumeLocatorIndexRecord record) {
        if (record == null || !record.indexable()) {
            return;
        }
        lock.writeLock().lock();
        try {
            AgentToolActionResumeLocatorIndexRecord merged = mergeExisting(record);
            if (hasText(merged.checkpointId())) {
                recordsByCheckpointId.put(merged.checkpointId(), merged);
            }
            if (hasText(merged.threadId())) {
                recordsByThreadId.put(merged.threadId(), merged);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolActionResumeLocatorIndexRecord> findByCheckpointId(String checkpointId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByCheckpointId.get(text(checkpointId)));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolActionResumeLocatorIndexRecord> findByThreadId(String threadId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByThreadId.get(text(threadId)));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return Math.max(recordsByCheckpointId.size(), recordsByThreadId.size());
        } finally {
            lock.readLock().unlock();
        }
    }

    private AgentToolActionResumeLocatorIndexRecord mergeExisting(AgentToolActionResumeLocatorIndexRecord incoming) {
        AgentToolActionResumeLocatorIndexRecord existing = null;
        if (hasText(incoming.checkpointId())) {
            existing = recordsByCheckpointId.get(incoming.checkpointId());
        }
        if (existing == null && hasText(incoming.threadId())) {
            existing = recordsByThreadId.get(incoming.threadId());
        }
        return existing == null ? incoming : existing.merge(incoming);
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
