/**
 * @Author : Cui
 * @Date: 2026/06/04 20:08
 * @Description DataSmart Govern Backend - InMemoryAgentSkillVisibilitySnapshotIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentSkillVisibilitySnapshotIndexProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 Skill 可见性快照专用索引。
 *
 * <p>这个类不是“又写了一个和通用 projection 一样的内存表”，而是为后续持久化索引预演业务边界：</p>
 * <p>1. consumer 只要收到 `skill_visibility_snapshot_recorded`，就把低敏快照物化到专用索引；</p>
 * <p>2. 查询服务优先读取专用索引，不再必须扫描所有 runtime event；</p>
 * <p>3. controller、DTO、权限收口和聚合逻辑保持不变，后续 JDBC/ClickHouse 实现只需要替换本 store。</p>
 *
 * <p>生产边界：</p>
 * <p>- 当前实现仍随 JVM 生命周期存在，不能作为长期审计表；</p>
 * <p>- 多实例之间不共享，需要后续用 MySQL/ClickHouse/OpenSearch 或审计中心统一承接；</p>
 * <p>- 索引只保存低敏聚合事实，不能扩展为保存 prompt、SQL、工具参数或权限明细。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.runtime-events.skill-visibility-index",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class InMemoryAgentSkillVisibilitySnapshotIndexStore implements AgentSkillVisibilitySnapshotIndexStore {

    /**
     * identityKey -> 投影记录。
     *
     * <p>identityKey 来自 runtime event consumer 的幂等键。专用索引继续使用同一键，避免 Kafka 重复投递、
     * consumer 重试或手动补偿时把同一条快照写成多条。</p>
     */
    private final Map<String, AgentRuntimeEventProjectionRecord> snapshotsByIdentityKey = new LinkedHashMap<>();

    /**
     * runId -> identityKey 队列。
     *
     * <p>该索引用于 per-run 裁剪。保存 key 而不是保存 record，是为了减少同一对象在多个集合中重复持有。</p>
     */
    private final Map<String, Deque<String>> identityKeysByRunId = new LinkedHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxSnapshotsPerRun;
    private final int maxTotalSnapshots;
    private long nextReplaySequence = 1L;

    public InMemoryAgentSkillVisibilitySnapshotIndexStore(AgentSkillVisibilitySnapshotIndexProperties properties) {
        this.maxSnapshotsPerRun = Math.max(1, properties.getMaxSnapshotsPerRun());
        this.maxTotalSnapshots = Math.max(1, properties.getMaxTotalSnapshots());
    }

    public InMemoryAgentSkillVisibilitySnapshotIndexStore(int maxSnapshotsPerRun, int maxTotalSnapshots) {
        this.maxSnapshotsPerRun = Math.max(1, maxSnapshotsPerRun);
        this.maxTotalSnapshots = Math.max(1, maxTotalSnapshots);
    }

    @Override
    public boolean append(AgentRuntimeEventProjectionRecord record) {
        if (record == null || !AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE.equals(record.eventType())) {
            return false;
        }
        lock.writeLock().lock();
        try {
            if (snapshotsByIdentityKey.containsKey(record.identityKey())) {
                return false;
            }
            AgentRuntimeEventProjectionRecord storedRecord = assignReplaySequenceIfMissing(record);
            snapshotsByIdentityKey.put(storedRecord.identityKey(), storedRecord);
            appendRunIndex(storedRecord);
            trimGlobalWindow();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<AgentRuntimeEventProjectionRecord> query(AgentRuntimeEventProjectionQuery query) {
        List<String> authorizedProjectIds = query.normalizedAuthorizedProjectIds();
        lock.readLock().lock();
        try {
            return snapshotsByIdentityKey.values().stream()
                    /*
                     * 与通用 projection 保持完全一致的 PROJECT 数据范围语义：
                     * - null 表示没有额外项目集合约束；
                     * - 空集合表示明确没有项目授权，应返回空结果；
                     * - 非空集合表示只能看这些项目。
                     */
                    .filter(record -> authorizedProjectIds == null || authorizedProjectIds.contains(record.projectId()))
                    .filter(record -> matches(query.tenantId(), record.tenantId()))
                    .filter(record -> matches(query.projectId(), record.projectId()))
                    .filter(record -> matches(query.actorId(), record.actorId()))
                    .filter(record -> matches(query.requestId(), record.requestId()))
                    .filter(record -> matches(query.runId(), record.runId()))
                    .filter(record -> matches(query.sessionId(), record.sessionId()))
                    .filter(record -> matches(query.severity(), record.severity()))
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
            return snapshotsByIdentityKey.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private AgentRuntimeEventProjectionRecord assignReplaySequenceIfMissing(AgentRuntimeEventProjectionRecord record) {
        Long replaySequence = record.replaySequence();
        if (replaySequence != null && replaySequence > 0) {
            nextReplaySequence = Math.max(nextReplaySequence, replaySequence + 1);
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
        while (keys.size() > maxSnapshotsPerRun) {
            String removedKey = keys.removeFirst();
            snapshotsByIdentityKey.remove(removedKey);
        }
    }

    private void trimGlobalWindow() {
        while (snapshotsByIdentityKey.size() > maxTotalSnapshots) {
            String oldestKey = snapshotsByIdentityKey.keySet().iterator().next();
            AgentRuntimeEventProjectionRecord removed = snapshotsByIdentityKey.remove(oldestKey);
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

    private boolean matches(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equals(actual);
    }
}
