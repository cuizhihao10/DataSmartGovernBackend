/**
 * @Author : Cui
 * @Date: 2026/06/30 23:14
 * @Description DataSmart Govern Backend - InMemoryAgentSkillPublicationLifecycleStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 Skill 发布生命周期仓储。
 *
 * <p>该实现用于关闭当前 P0 的“创建/审核/发布/下线”控制面状态机，而不是最终生产事实库。
 * 它保留了仓储端口、唯一性查询、列表查询和并发锁，目的是让后续 MySQL 版本可以等价替换。</p>
 *
 * <p>为什么仍然要加读写锁：
 * ConcurrentHashMap 能保证单个 put/get 的并发安全，但发布列表查询需要同时遍历、过滤和排序。
 * 使用读写锁可以让“保存/状态流转”和“列表查询”之间保持一致窗口，避免管理台看到半更新状态。</p>
 */
@Component
public class InMemoryAgentSkillPublicationLifecycleStore implements AgentSkillPublicationLifecycleStore {

    private final Map<String, AgentSkillPublicationRecord> records = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public AgentSkillPublicationRecord save(AgentSkillPublicationRecord record) {
        lock.writeLock().lock();
        try {
            records.put(record.publicationId(), record);
            return record;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentSkillPublicationRecord> findById(String publicationId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(records.get(publicationId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<AgentSkillPublicationRecord> findBySkillCodeAndVersion(String tenantId,
                                                                           String projectId,
                                                                           String skillCode,
                                                                           String version) {
        lock.readLock().lock();
        try {
            return records.values().stream()
                    .filter(record -> equalsText(record.tenantId(), tenantId))
                    .filter(record -> equalsText(record.projectId(), projectId))
                    .filter(record -> equalsText(record.skillCode(), skillCode))
                    .filter(record -> equalsText(record.version(), version))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentSkillPublicationRecord> query(AgentSkillPublicationLifecycleQuery query) {
        lock.readLock().lock();
        try {
            return records.values().stream()
                    .filter(record -> matches(query.tenantId(), record.tenantId()))
                    .filter(record -> matches(query.projectId(), record.projectId()))
                    .filter(record -> matches(query.skillCode(), record.skillCode()))
                    .filter(record -> matches(query.domain(), record.domain()))
                    .filter(record -> query.status() == null || query.status() == record.status())
                    .sorted(Comparator.comparing(AgentSkillPublicationRecord::updatedAt).reversed()
                            .thenComparing(AgentSkillPublicationRecord::publicationId))
                    .limit(query.normalizedLimit())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || equalsText(actual, expected);
    }

    private boolean equalsText(String actual, String expected) {
        String left = actual == null ? "" : actual.trim();
        String right = expected == null ? "" : expected.trim();
        return left.equals(right);
    }
}
