/**
 * @Author : Cui
 * @Date: 2026/06/01 14:24
 * @Description DataSmart Govern Backend - InMemoryAgentRunToolDagConfirmationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.config.AgentRunToolDagConfirmationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 内存版 DAG selected-node 确认记录仓储。
 *
 * <p>该实现用于本地学习、单元测试和早期联调。它能验证确认记录的幂等、查询和容量保护逻辑，
 * 但不能作为生产持久化方案：服务重启会丢失记录，多实例部署也不会共享确认事实。
 * 生产环境后续应切换到 MySQL store，并让确认记录、outbox command 和工具审计状态尽量处在同一事务边界。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.tool-dag.confirmations",
        name = "store",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryAgentRunToolDagConfirmationStore implements AgentRunToolDagConfirmationStore {

    private final Map<String, AgentRunToolDagConfirmationRecord> recordsByConfirmationId = new LinkedHashMap<>();
    private final Map<String, Deque<String>> confirmationIdsByRunId = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxConfirmationsPerRun;
    private final int maxTotalRecords;

    public InMemoryAgentRunToolDagConfirmationStore(AgentRunToolDagConfirmationProperties properties) {
        this.maxConfirmationsPerRun = Math.max(1, properties.getMaxConfirmationsPerRun());
        this.maxTotalRecords = Math.max(1, properties.getMaxTotalRecords());
    }

    public InMemoryAgentRunToolDagConfirmationStore(int maxConfirmationsPerRun, int maxTotalRecords) {
        this.maxConfirmationsPerRun = Math.max(1, maxConfirmationsPerRun);
        this.maxTotalRecords = Math.max(1, maxTotalRecords);
    }

    @Override
    public AgentRunToolDagConfirmationRecord saveIfAbsent(AgentRunToolDagConfirmationRecord record) {
        lock.writeLock().lock();
        try {
            AgentRunToolDagConfirmationRecord existing = recordsByConfirmationId.get(record.confirmationId());
            if (existing != null) {
                return existing;
            }
            recordsByConfirmationId.put(record.confirmationId(), record);
            appendRunIndex(record);
            trimGlobalWindow();
            return record;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentRunToolDagConfirmationRecord> findByConfirmationId(String confirmationId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByConfirmationId.get(confirmationId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentRunToolDagConfirmationRecord> listByRun(String runId, int limit) {
        int normalizedLimit = normalizeLimit(limit);
        lock.readLock().lock();
        try {
            Deque<String> confirmationIds = confirmationIdsByRunId.get(runId);
            if (confirmationIds == null || confirmationIds.isEmpty()) {
                return List.of();
            }
            List<AgentRunToolDagConfirmationRecord> result = new ArrayList<>();
            for (String confirmationId : confirmationIds) {
                AgentRunToolDagConfirmationRecord record = recordsByConfirmationId.get(confirmationId);
                if (record == null) {
                    continue;
                }
                result.add(record);
                if (result.size() >= normalizedLimit) {
                    break;
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void appendRunIndex(AgentRunToolDagConfirmationRecord record) {
        Deque<String> confirmationIds = confirmationIdsByRunId.computeIfAbsent(record.runId(), ignored -> new ArrayDeque<>());
        confirmationIds.addLast(record.confirmationId());
        while (confirmationIds.size() > maxConfirmationsPerRun) {
            String removedConfirmationId = confirmationIds.removeFirst();
            recordsByConfirmationId.remove(removedConfirmationId);
        }
    }

    private void trimGlobalWindow() {
        while (recordsByConfirmationId.size() > maxTotalRecords) {
            String oldestConfirmationId = recordsByConfirmationId.keySet().iterator().next();
            AgentRunToolDagConfirmationRecord removed = recordsByConfirmationId.remove(oldestConfirmationId);
            removeFromRunIndex(removed, oldestConfirmationId);
        }
    }

    private void removeFromRunIndex(AgentRunToolDagConfirmationRecord removed, String removedConfirmationId) {
        if (removed == null) {
            return;
        }
        Deque<String> confirmationIds = confirmationIdsByRunId.get(removed.runId());
        if (confirmationIds == null) {
            return;
        }
        confirmationIds.remove(removedConfirmationId);
        if (confirmationIds.isEmpty()) {
            confirmationIdsByRunId.remove(removed.runId());
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }
}
