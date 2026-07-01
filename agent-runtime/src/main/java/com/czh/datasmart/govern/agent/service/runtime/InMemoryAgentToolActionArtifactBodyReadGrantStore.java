/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionArtifactBodyReadGrantStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentArtifactBodyReadGrantStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Spring 运行时构造器。
     *
     * <p>artifact 正文读取授权事实虽然在本地默认保存在内存中，但它仍然必须受容量上限约束，
     * 否则对象存储探针或读取授权异常循环会持续堆积 grant fact。这里从配置属性读取 memory
     * store 上限；类内的包级构造器只服务于单元测试容量裁剪场景。显式标注 {@link Autowired}
     * 可以避免 Spring 在多构造器情况下寻找无参构造器。</p>
     */
    @Autowired
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

    /**
     * 按低敏条件查询内存中的 grant fact。
     *
     * <p>虽然 memory store 只面向本地学习和单实例联调，但查询语义必须和 MySQL store 尽量一致：
     * 1. 缺少 grantDecisionReference/commandId 时直接返回空结果，避免误用成全表浏览；
     * 2. PROJECT 范围下 authorizedProjectIds 为空时返回空结果，不能退化成全项目可见；
     * 3. 查询结果按签发时间倒序返回，方便管理台优先看到最近一次授权；
     * 4. limit 在调用方和 Store 两层都生效，避免异常请求把内存窗口一次性序列化出去。</p>
     */
    @Override
    public List<AgentToolActionArtifactBodyReadGrantRecord> query(
            AgentToolActionArtifactBodyReadGrantQuery query,
            int limit) {
        if (query == null || !query.hasRequiredSelector()) {
            return List.of();
        }
        if (query.authorizedProjectIds() != null && query.authorizedProjectIds().isEmpty()) {
            return List.of();
        }
        int appliedLimit = Math.max(1, Math.min(limit, query.normalizedLimit()));
        lock.readLock().lock();
        try {
            return recordsByReference.values().stream()
                    .filter(record -> matchesQuery(record, query))
                    .sorted(Comparator.comparingLong(
                            AgentToolActionArtifactBodyReadGrantRecord::issuedAtEpochMs
                    ).reversed())
                    .limit(appliedLimit)
                    .toList();
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

    /**
     * 判断单条记录是否满足低敏查询条件。
     *
     * <p>这里不做模糊搜索，也不支持 artifact 正文内容搜索。grant fact 管理接口的目标是“按已知低敏定位符排障”，
     * 不是给用户提供 artifact 内容检索。真正内容检索必须走对象存储 ACL、DLP 和下载审计链路。</p>
     */
    private boolean matchesQuery(AgentToolActionArtifactBodyReadGrantRecord record,
                                 AgentToolActionArtifactBodyReadGrantQuery query) {
        return matches(record.grantDecisionReference(), query.grantDecisionReference())
                && matches(record.commandId(), query.commandId())
                && matches(record.artifactReference(), query.artifactReference())
                && matches(record.tenantId(), query.tenantId())
                && matches(record.projectId(), query.projectId())
                && matches(record.actorId(), query.actorId())
                && matches(record.runId(), query.runId())
                && matches(record.sessionId(), query.sessionId())
                && matches(record.toolCode(), query.toolCode())
                && matches(record.status() == null ? null : record.status().name(), query.status())
                && projectVisible(record.projectId(), query.authorizedProjectIds());
    }

    private boolean projectVisible(String projectId, List<String> authorizedProjectIds) {
        if (authorizedProjectIds == null) {
            return true;
        }
        return authorizedProjectIds.contains(projectId);
    }

    private boolean matches(String actual, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.trim().equals(actual == null ? null : actual.trim());
    }

    private static int normalizedMaxRecords(AgentArtifactBodyReadGrantStoreProperties properties) {
        if (properties == null || properties.getMemoryMaxRecords() == null
                || properties.getMemoryMaxRecords() <= 0) {
            return 10000;
        }
        return Math.min(properties.getMemoryMaxRecords(), 100000);
    }
}
