/**
 * @Author : Cui
 * @Date: 2026/06/11 00:00
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionPayloadStore.java
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
 * 内存版工具动作 payload store。
 *
 * <p>该实现用于本地开发、单元测试和 5.57 的控制面链路打通。它不是生产级持久化：
 * JVM 重启会丢失记录，多实例部署不会共享状态，也不具备 KMS 加密、冷热分层和审计保留能力。
 * 但它能先把关键业务边界落地：`agent-payload:` 必须由服务端登记，writer/verifier 必须能回查登记事实，
 * 后续执行器必须按过期时间和作用域读取。</p>
 *
 * <p>为什么仍然值得先实现内存版：真实产品通常会先固定领域端口和安全语义，再替换底层存储。
 * 这样 MySQL、Redis、MinIO 或向量/对象存储的选择不会反向污染 writer、verifier 和 task-management inbox。</p>
 */
@Component
public class InMemoryAgentToolActionPayloadStore implements AgentToolActionPayloadStore {

    /**
     * 按 payloadReference 保存记录。
     *
     * <p>使用 LinkedHashMap 是为了保留插入顺序，方便后续如果需要增加窗口裁剪或诊断列表时能按登记顺序展示。
     * 读写锁用于保证本地多线程测试、dispatcher dry-run 和并发确认页重试时不会出现 Map 并发修改问题。</p>
     */
    private final Map<String, AgentToolActionPayloadRecord> recordsByReference = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public boolean append(AgentToolActionPayloadRecord record) {
        if (record == null || !hasText(record.payloadReference())) {
            return false;
        }
        lock.writeLock().lock();
        try {
            if (recordsByReference.containsKey(record.payloadReference())) {
                return false;
            }
            recordsByReference.put(record.payloadReference(), record);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolActionPayloadRecord> findByReference(String payloadReference) {
        if (!hasText(payloadReference)) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByReference.get(payloadReference.trim()));
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
            Iterator<Map.Entry<String, AgentToolActionPayloadRecord>> iterator = recordsByReference.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, AgentToolActionPayloadRecord> entry = iterator.next();
                if (entry.getValue().expired(referenceTime)) {
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
