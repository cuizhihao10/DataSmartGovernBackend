/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionApprovalConfirmationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版工具动作审批确认事实仓储。
 *
 * <p>该实现只用于本地开发、单元测试和当前阶段的 Host 控制面闭环。它不具备跨实例共享、重启恢复、审计归档和加密能力；
 * 但它能先把重要产品语义固定下来：真实执行前必须存在服务端确认事实，确认事实必须短 TTL，且 writer 必须回查而不是
 * 信任调用方随手传入的 confirmationId 字符串。</p>
 */
@Component
public class InMemoryAgentToolActionApprovalConfirmationStore
        implements AgentToolActionApprovalConfirmationStore {

    /**
     * confirmationId -> confirmation record。
     *
     * <p>使用 LinkedHashMap 保留插入顺序，方便未来做诊断列表、容量裁剪和审计导出。读写锁用于保护本地并发确认、
     * writer 重试和测试并行执行时的 Map 访问。</p>
     */
    private final Map<String, AgentToolActionApprovalConfirmationRecord> recordsById = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public boolean saveIfAbsent(AgentToolActionApprovalConfirmationRecord record) {
        if (record == null || !hasText(record.confirmationId())) {
            return false;
        }
        lock.writeLock().lock();
        try {
            if (recordsById.containsKey(record.confirmationId())) {
                return false;
            }
            recordsById.put(record.confirmationId(), record);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolActionApprovalConfirmationRecord> findByConfirmationId(String confirmationId) {
        if (!hasText(confirmationId)) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsById.get(confirmationId.trim()));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int removeExpired(Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        lock.writeLock().lock();
        try {
            int removed = 0;
            Iterator<Map.Entry<String, AgentToolActionApprovalConfirmationRecord>> iterator =
                    recordsById.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().expired(referenceTime)) {
                    iterator.remove();
                    removed++;
                }
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
