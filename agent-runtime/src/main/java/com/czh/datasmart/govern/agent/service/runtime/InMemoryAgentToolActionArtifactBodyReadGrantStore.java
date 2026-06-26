/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionArtifactBodyReadGrantStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentArtifactBodyReadGrantStoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 artifact 正文读取授权事实仓库。
 *
 * <p>该实现用于本地学习、单元测试和单实例联调。它可以完整表达“服务端签发 -> 服务端回查 ->
 * 过期/撤销 fail-closed”的业务语义，但不能跨 JVM 重启或多实例共享。因此生产环境最终应新增 MySQL store，
 * 并配合唯一索引、TTL 归档、审计导出和管理员撤销 API。</p>
 *
 * <p>即使只是内存实现，也要显式控制容量。Agent、工具执行和对象存储探针都可能在异常循环下高频调用，
 * 如果没有上限，grant fact 会成为隐藏的内存增长点。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.artifact-body-read-grants.store:memory}'.equalsIgnoreCase('memory')"
)
public class InMemoryAgentToolActionArtifactBodyReadGrantStore
        implements AgentToolActionArtifactBodyReadGrantStore {

    /**
     * grantDecisionReference -> grant fact。
     *
     * <p>LinkedHashMap 保留插入顺序，便于在达到容量上限时裁剪最旧记录。这里不使用 access-order，
     * 是因为 grant fact 的安全语义不应因为频繁查询而变成“永远不淘汰”的热点记录。</p>
     */
    private final Map<String, AgentToolActionArtifactBodyReadGrantRecord> recordsByReference =
            new LinkedHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxRecords;

    public InMemoryAgentToolActionArtifactBodyReadGrantStore(
            AgentArtifactBodyReadGrantStoreProperties properties) {
        this(normalizedMaxRecords(properties));
    }

    InMemoryAgentToolActionArtifactBodyReadGrantStore(int maxRecords) {
        this.maxRecords = Math.max(1, maxRecords);
    }

    @Override
    public void save(AgentToolActionArtifactBodyReadGrantRecord record) {
        if (record == null || !record.indexable()) {
            return;
        }
        lock.writeLock().lock();
        try {
            recordsByReference.put(record.grantDecisionReference(), record);
            trimGlobalWindow();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolActionArtifactBodyReadGrantRecord> findByReference(
            String grantDecisionReference) {
        if (grantDecisionReference == null || grantDecisionReference.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByReference.get(grantDecisionReference.trim()));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolActionArtifactBodyReadGrantRecord> revoke(
            String grantDecisionReference,
            String operatorId,
            String reasonCode,
            long revokedAtEpochMs) {
        if (grantDecisionReference == null || grantDecisionReference.isBlank()) {
            return Optional.empty();
        }
        lock.writeLock().lock();
        try {
            AgentToolActionArtifactBodyReadGrantRecord existing =
                    recordsByReference.get(grantDecisionReference.trim());
            if (existing == null) {
                return Optional.empty();
            }
            AgentToolActionArtifactBodyReadGrantRecord revoked =
                    existing.revoke(operatorId, reasonCode, revokedAtEpochMs);
            recordsByReference.put(revoked.grantDecisionReference(), revoked);
            return Optional.of(revoked);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return recordsByReference.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 达到容量上限时裁剪最旧记录。
     *
     * <p>memory store 本身不是审计最终归档，因此裁剪不会尝试导出历史；生产 MySQL 版本应改由
     * TTL/归档任务处理，不能简单删除。</p>
     */
    private void trimGlobalWindow() {
        while (recordsByReference.size() > maxRecords) {
            String oldestKey = recordsByReference.keySet().iterator().next();
            recordsByReference.remove(oldestKey);
        }
    }

    private static int normalizedMaxRecords(AgentArtifactBodyReadGrantStoreProperties properties) {
        if (properties == null || properties.getMemoryMaxRecords() == null
                || properties.getMemoryMaxRecords() <= 0) {
            return 10000;
        }
        return Math.min(properties.getMemoryMaxRecords(), 100000);
    }
}
