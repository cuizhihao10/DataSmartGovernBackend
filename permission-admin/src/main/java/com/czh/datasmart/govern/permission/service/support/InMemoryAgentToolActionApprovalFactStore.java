/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionApprovalFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Agent 受控工具动作审批事实内存仓储。
 *
 * <p>该实现只用于第一阶段本地开发、单元测试和跨模块契约验证。它能证明“审批事实必须在 permission-admin
 * 服务端登记并被回查”，但不具备生产所需的多实例共享、JVM 重启恢复、TTL 后台清理、审计留存和加密能力。
 * 这些能力应在后续 PostgreSQL 实现中补齐。</p>
 */
public class InMemoryAgentToolActionApprovalFactStore implements AgentToolActionApprovalFactStore {

    private final ConcurrentMap<String, AgentToolActionApprovalFactRecord> records = new ConcurrentHashMap<>();

    @Override
    public AgentToolActionApprovalFactRecord save(AgentToolActionApprovalFactRecord record) {
        AtomicReference<AgentToolActionApprovalFactRecord> saved = new AtomicReference<>();
        records.compute(record.approvalFactId(), (approvalFactId, existing) -> {
            AgentToolActionApprovalFactRecord next = existing == null
                    ? record
                    : merge(existing, record);
            saved.set(next);
            return next;
        });
        return saved.get();
    }

    @Override
    public Optional<AgentToolActionApprovalFactRecord> findById(String approvalFactId) {
        return Optional.ofNullable(records.get(approvalFactId));
    }

    /**
     * Mirrors the PostgreSQL write invariant for local development and tests.
     * compute serializes competing writes for one fact ID, so a stale caller
     * cannot replace a record after its scope has changed.
     */
    private AgentToolActionApprovalFactRecord merge(AgentToolActionApprovalFactRecord existing,
                                                    AgentToolActionApprovalFactRecord candidate) {
        if (!sameImmutableScopeAndVersion(existing, candidate)) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "approvalFactId 已绑定其他双主体 scope 或 policyVersion，拒绝覆盖已有审批事实");
        }
        if (isTerminal(existing.status()) && "PENDING".equals(normalizeStatus(candidate.status()))) {
            return existing;
        }
        return candidate;
    }

    private boolean sameImmutableScopeAndVersion(AgentToolActionApprovalFactRecord left,
                                                 AgentToolActionApprovalFactRecord right) {
        return Objects.equals(left.tenantId(), right.tenantId())
                && Objects.equals(left.applicationId(), right.applicationId())
                && Objects.equals(left.projectId(), right.projectId())
                && sameText(left.userId(), right.userId())
                && sameText(left.actorId(), right.actorId())
                && sameText(left.agentId(), right.agentId())
                && sameText(left.sessionId(), right.sessionId())
                && sameText(left.runId(), right.runId())
                && sameText(left.delegationId(), right.delegationId())
                && sameText(left.commandId(), right.commandId())
                && sameText(left.toolCode(), right.toolCode())
                && sameText(left.policyVersion(), right.policyVersion());
    }

    private boolean sameText(String left, String right) {
        return Objects.equals(normalizeText(left), normalizeText(right));
    }

    private boolean isTerminal(String status) {
        String normalized = normalizeStatus(status);
        return "APPROVED".equals(normalized) || "REJECTED".equals(normalized);
    }

    private String normalizeStatus(String status) {
        return status == null ? "PENDING" : status.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
