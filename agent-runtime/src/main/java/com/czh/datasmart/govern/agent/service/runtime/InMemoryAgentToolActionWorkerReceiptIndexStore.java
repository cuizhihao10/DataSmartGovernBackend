/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionWorkerReceiptIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 worker receipt 低敏索引。
 *
 * <p>它承担的不是最终生产持久化，而是把业务查询路径从“扫描通用 runtime event projection”推进到
 * “按 commandId 查询专用事实索引”。这样一来，后续把本类替换为 MySQL store 时，恢复事实包验真器和
 * Controller 响应契约都不需要重写。</p>
 *
 * <p>生产边界必须明确：
 * 1. JVM 重启会丢失索引；
 * 2. 多实例之间不共享索引；
 * 3. 当前没有 TTL/归档/管理员查询；
 * 4. 它仍然不保存 message、payload、SQL、prompt、工具参数或内部 endpoint。</p>
 */
@Component
public class InMemoryAgentToolActionWorkerReceiptIndexStore implements AgentToolActionWorkerReceiptIndexStore {

    /**
     * eventIdentityKey -> receipt record。
     *
     * <p>identityKey 是幂等主键，保证同一条 receipt 被 HTTP 重试、Kafka 重放或索引补偿多次物化时，
     * 不会把 receiptCount 和最新状态放大。</p>
     */
    private final Map<String, AgentToolActionWorkerReceiptIndexRecord> recordsByIdentityKey = new LinkedHashMap<>();

    /**
     * commandId -> eventIdentityKey 列表。
     *
     * <p>恢复事实包查询的核心路径是 commandId，因此这里额外维护倒排索引，避免每次在 JVM 中全量扫描。</p>
     */
    private final Map<String, List<String>> identityKeysByCommandId = new LinkedHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxRecords;

    public InMemoryAgentToolActionWorkerReceiptIndexStore(AgentToolActionResumeFactBundleProperties properties) {
        this(normalizedMaxRecords(properties));
    }

    InMemoryAgentToolActionWorkerReceiptIndexStore(int maxRecords) {
        this.maxRecords = Math.max(1, maxRecords);
    }

    @Override
    public boolean upsert(AgentToolActionWorkerReceiptIndexRecord record) {
        if (record == null || !record.indexable()) {
            return false;
        }
        lock.writeLock().lock();
        try {
            boolean inserted = !recordsByIdentityKey.containsKey(record.eventIdentityKey());
            recordsByIdentityKey.put(record.eventIdentityKey(), record);
            identityKeysByCommandId
                    .computeIfAbsent(record.commandId(), ignored -> new ArrayList<>())
                    .remove(record.eventIdentityKey());
            identityKeysByCommandId.get(record.commandId()).add(record.eventIdentityKey());
            trimGlobalWindow();
            return inserted;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<AgentToolActionWorkerReceiptIndexRecord> queryByCommandId(
            AgentToolActionWorkerReceiptIndexQuery query) {
        if (query == null || query.commandId() == null || query.commandId().isBlank()) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            List<String> keys = identityKeysByCommandId.get(query.commandId());
            if (keys == null || keys.isEmpty()) {
                return List.of();
            }
            return keys.stream()
                    .map(recordsByIdentityKey::get)
                    .filter(record -> record != null && visible(record, query))
                    .sorted(Comparator
                            .comparing(this::replaySequence, Comparator.naturalOrder())
                            .thenComparing(AgentToolActionWorkerReceiptIndexRecord::consumedAt))
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

    private boolean visible(AgentToolActionWorkerReceiptIndexRecord record,
                            AgentToolActionWorkerReceiptIndexQuery query) {
        List<String> authorizedProjectIds = query.authorizedProjectIds();
        if (authorizedProjectIds != null && !authorizedProjectIds.contains(record.projectId())) {
            return false;
        }
        return matches(query.tenantId(), record.tenantId())
                && matches(query.projectId(), record.projectId())
                && matches(query.actorId(), record.actorId())
                && matches(query.runId(), record.runId())
                && matches(query.sessionId(), record.sessionId())
                /*
                 * 历史 receipt projection 可能没有 toolCode。为了向后兼容，只有查询和记录两边都有 toolCode 时才强匹配。
                 * 等 MySQL durable index 上线后，可以通过 migration 或补物化把历史记录补齐，再逐步收紧这里的策略。
                 */
                && (!hasText(query.toolCode()) || !hasText(record.toolCode()) || query.toolCode().equals(record.toolCode()));
    }

    private void trimGlobalWindow() {
        while (recordsByIdentityKey.size() > maxRecords) {
            String oldestKey = recordsByIdentityKey.keySet().iterator().next();
            AgentToolActionWorkerReceiptIndexRecord removed = recordsByIdentityKey.remove(oldestKey);
            if (removed != null) {
                List<String> commandKeys = identityKeysByCommandId.get(removed.commandId());
                if (commandKeys != null) {
                    commandKeys.remove(oldestKey);
                    if (commandKeys.isEmpty()) {
                        identityKeysByCommandId.remove(removed.commandId());
                    }
                }
            }
        }
    }

    private Long replaySequence(AgentToolActionWorkerReceiptIndexRecord record) {
        return record.replaySequence() == null ? -1L : record.replaySequence();
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int normalizedMaxRecords(AgentToolActionResumeFactBundleProperties properties) {
        if (properties == null || properties.getWorkerReceiptIndexMaxRecords() == null
                || properties.getWorkerReceiptIndexMaxRecords() <= 0) {
            return 10000;
        }
        return Math.min(properties.getWorkerReceiptIndexMaxRecords(), 100000);
    }
}
