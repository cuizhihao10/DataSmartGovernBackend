/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionApprovalFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Agent 受控工具动作审批事实内存仓储。
 *
 * <p>该实现只用于第一阶段本地开发、单元测试和跨模块契约验证。它能证明“审批事实必须在 permission-admin
 * 服务端登记并被回查”，但不具备生产所需的多实例共享、JVM 重启恢复、TTL 后台清理、审计留存和加密能力。
 * 这些能力应在后续 MySQL 实现中补齐。</p>
 */
@Component
public class InMemoryAgentToolActionApprovalFactStore implements AgentToolActionApprovalFactStore {

    private final ConcurrentMap<String, AgentToolActionApprovalFactRecord> records = new ConcurrentHashMap<>();

    @Override
    public AgentToolActionApprovalFactRecord save(AgentToolActionApprovalFactRecord record) {
        records.put(record.approvalFactId(), record);
        return record;
    }

    @Override
    public Optional<AgentToolActionApprovalFactRecord> findById(String approvalFactId) {
        return Optional.ofNullable(records.get(approvalFactId));
    }
}
