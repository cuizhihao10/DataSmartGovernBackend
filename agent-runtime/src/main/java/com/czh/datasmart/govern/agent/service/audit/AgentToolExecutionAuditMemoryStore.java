/**
 * @Author : Cui
 * @Date: 2026/05/13 23:45
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditMemoryStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Agent 工具执行审计内存仓储。
 *
 * <p>当前仓储仅用于控制面骨架阶段。真实商业化环境应迁移到 MySQL 审计表、Kafka 事件流或专门审计服务。
 * 这里先使用线程安全 Map，是为了让多个请求查询同一 run 的工具计划时不会出现基础并发问题。
 */
@Component
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.persistence",
        name = "audit-store",
        havingValue = "memory",
        matchIfMissing = true
)
public class AgentToolExecutionAuditMemoryStore implements AgentToolExecutionAuditStore {

    private final ConcurrentMap<String, AgentToolExecutionAuditRecord> records = new ConcurrentHashMap<>();

    /**
     * 保存单条工具执行审计记录。
     *
     * <p>内存实现采用 auditId 作为 Map key。这里不复制对象，而是保留同一个记录实例，
     * 原因是当前阶段服务层仍在同一个 JVM 内推进状态；后续切换到数据库实现时，保存动作会真正把对象字段刷新到表中，
     * 因此 {@code AgentToolExecutionAuditService} 已经改为每次状态变化后显式调用 save。</p>
     */
    @Override
    public void save(AgentToolExecutionAuditRecord audit) {
        records.put(audit.getAuditId(), audit);
    }

    @Override
    public void saveAll(List<AgentToolExecutionAuditRecord> audits) {
        audits.forEach(this::save);
    }

    /**
     * 按审计 ID 查询单条工具计划。
     *
     * <p>审批/拒绝接口需要先定位具体审计记录，再校验它是否属于当前 session/run。
     * 这里不直接把 sessionId/runId 拼进 Map key，是为了后续迁移数据库时仍可保留 auditId 作为主键。
     */
    @Override
    public Optional<AgentToolExecutionAuditRecord> findById(String auditId) {
        return Optional.ofNullable(records.get(auditId));
    }

    @Override
    public List<AgentToolExecutionAuditRecord> list(String sessionId, String runId) {
        return records.values().stream()
                .filter(item -> sessionId == null || sessionId.equals(item.getSessionId()))
                .filter(item -> runId == null || runId.equals(item.getRunId()))
                /*
                 * createTime 相同并不是异常：批量写入 ToolPlan 时，多条审计记录可能在同一个时间粒度内创建。
                 * ConcurrentHashMap.values() 本身没有稳定遍历顺序，如果只按 createTime 排序，前端时间线、
                 * Python 二轮结果回填和单元测试都会偶发看到节点顺序交换。auditId 是稳定唯一键，适合作为
                 * 次排序键，让内存实现具备可复现结果；未来 MySQL 查询也应保持同样的 ORDER BY 语义。
                 */
                .sorted(Comparator.comparing(AgentToolExecutionAuditRecord::getCreateTime)
                        .thenComparing(AgentToolExecutionAuditRecord::getAuditId))
                .toList();
    }
}
