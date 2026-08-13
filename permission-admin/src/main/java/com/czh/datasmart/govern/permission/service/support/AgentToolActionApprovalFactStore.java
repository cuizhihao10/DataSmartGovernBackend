/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import java.util.Optional;

/**
 * Persistence boundary for Agent tool-action approval facts.
 *
 * <p>The service owns the approval fact contract, while this port keeps the
 * storage implementation replaceable. Production uses PostgreSQL; the
 * in-memory implementation is limited to local development and tests.</p>
 */
public interface AgentToolActionApprovalFactStore {

    /**
     * Performs an atomic idempotent write for one approval fact.
     *
     * <p>Implementations must validate the complete dual-subject scope,
     * server-derived action fingerprint, and policy version in the same write that
     * advances the lifecycle. A caller
     * must not perform a read-then-write check. Scope/version conflicts must
     * fail closed without changing the stored row, and a delayed PENDING write
     * must never replace an APPROVED or REJECTED row.</p>
     *
     * @param record approval fact to insert or advance
     * @return authoritative record after the atomic write
     */
    AgentToolActionApprovalFactRecord save(AgentToolActionApprovalFactRecord record);

    /**
     * Finds an approval fact by its stable low-sensitive identifier.
     *
     * @param approvalFactId approval fact identifier
     * @return matching persisted fact, if present
     */
    Optional<AgentToolActionApprovalFactRecord> findById(String approvalFactId);
}
