/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionClarificationFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版澄清事实仓储。
 *
 * <p>该实现的定位是“本地学习、单元测试、单实例联调和第一阶段控制面闭环”，不是最终生产存储。
 * 它让 Java agent-runtime 可以马上具备服务端可验证的澄清事实能力，同时把未来 MySQL durable store
 * 留在 {@link AgentToolActionClarificationFactStore} 接口之后。</p>
 *
 * <p>为什么不用普通 {@code ConcurrentHashMap} 直接写？
 * 这里需要在 upsert 时完成“读取旧记录、合并、写回、必要时淘汰最旧记录”这一组复合操作。
 * 读写锁让这些步骤在语义上更清晰，也方便学习者理解：内存仓储虽然轻量，但仍然要考虑并发一致性。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.tool-action-resume-facts.clarification-fact-store:memory}'"
                + ".equalsIgnoreCase('memory')"
)
public class InMemoryAgentToolActionClarificationFactStore implements AgentToolActionClarificationFactStore {

    /**
     * 按 factId 保存澄清事实。
     *
     * <p>{@link LinkedHashMap} 保留插入顺序，超过最大记录数时可以淘汰最早写入的事实。
     * 这不是生产级 TTL/归档策略，但能防止本地长时间联调时 JVM 内存无限增长。</p>
     */
    private final Map<String, AgentToolActionClarificationFactRecord> recordsByFactId = new LinkedHashMap<>();

    /** 读写锁保护 upsert 合并和容量裁剪的复合操作。 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 恢复事实包配置，当前只读取澄清事实内存窗口大小。 */
    private final AgentToolActionResumeFactBundleProperties properties;

    /**
     * 保存或合并澄清事实。
     *
     * <p>如果 record 不具备最小索引条件，仓储会直接忽略。业务侧登记服务会先抛出参数错误；
     * 这里额外防御是为了保护未来 mapper、测试夹具或内部调用误传半成品记录时不会污染事实库。</p>
     */
    @Override
    public void upsert(AgentToolActionClarificationFactRecord record) {
        if (record == null || !record.indexable()) {
            return;
        }
        lock.writeLock().lock();
        try {
            AgentToolActionClarificationFactRecord existing = recordsByFactId.get(record.clarificationFactId());
            recordsByFactId.put(record.clarificationFactId(), existing == null ? record : existing.merge(record));
            trimToMaxRecords();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 按 factId 查询澄清事实。
     *
     * <p>返回值仍需由 evaluator 做可见性校验。仓储层不根据当前调用者过滤，是为了保持仓储职责单一，
     * 也避免未来 MySQL 查询和内存查询在安全语义上发生漂移。</p>
     */
    @Override
    public Optional<AgentToolActionClarificationFactRecord> findByFactId(String clarificationFactId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByFactId.get(text(clarificationFactId)));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return recordsByFactId.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void trimToMaxRecords() {
        int maxRecords = normalizedMaxRecords();
        Iterator<String> iterator = recordsByFactId.keySet().iterator();
        while (recordsByFactId.size() > maxRecords && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private int normalizedMaxRecords() {
        Integer configured = properties == null ? null : properties.getClarificationFactMaxRecords();
        if (configured == null || configured <= 0) {
            return 10000;
        }
        return Math.min(configured, 100000);
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
