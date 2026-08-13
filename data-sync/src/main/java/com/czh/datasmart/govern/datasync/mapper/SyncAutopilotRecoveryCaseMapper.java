/**
 * @Author : Cui
 * @Date: 2026/08/11 00:15
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCaseMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryCase;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * PostgreSQL persistence operations for autopilot recovery cases.
 *
 * <p>Both insert and transition SQL are concurrency-aware. The unique identity makes repeated
 * decision publication converge on one case, and the expected state/version predicate prevents
 * stale callers from overwriting a newer lifecycle transition.</p>
 */
@Mapper
public interface SyncAutopilotRecoveryCaseMapper extends BaseMapper<SyncAutopilotRecoveryCase> {

    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_case
            WHERE tenant_id = #{tenantId}
              AND sync_task_id = #{syncTaskId}
              AND root_execution_id = #{rootExecutionId}
              AND authorization_digest = #{authorizationDigest}
              AND repair_fingerprint = #{repairFingerprint}
            LIMIT 1
            """)
    SyncAutopilotRecoveryCase selectByIdentity(@Param("tenantId") Long tenantId,
                                                @Param("syncTaskId") Long syncTaskId,
                                                @Param("rootExecutionId") Long rootExecutionId,
                                                @Param("authorizationDigest") String authorizationDigest,
                                                @Param("repairFingerprint") String repairFingerprint);

    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_case
            WHERE case_id = #{caseId}
            """)
    SyncAutopilotRecoveryCase selectByCaseId(@Param("caseId") Long caseId);

    /**
     * Returns the latest recovery case that references the requested execution in its bounded lineage.
     *
     * <p>The tenant and task predicates make this suitable for the public status projection after the service
     * has already checked execution ownership. Matching both root and current execution keeps the read stable if
     * a future connector creates a new execution per recovery cycle instead of re-queuing the same parent.</p>
     */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_case
            WHERE tenant_id = #{tenantId}
              AND sync_task_id = #{syncTaskId}
              AND (root_execution_id = #{executionId} OR current_execution_id = #{executionId})
            ORDER BY update_time DESC, case_id DESC
            LIMIT 1
            """)
    SyncAutopilotRecoveryCase selectLatestByTaskExecution(
            @Param("tenantId") Long tenantId,
            @Param("syncTaskId") Long syncTaskId,
            @Param("executionId") Long executionId);

    /**
     * 查找当前失败 execution 所属的正在执行恢复案例。
     *
     * <p>只返回 RECOVERY_STARTED，避免把已经恢复、取消或等待人工审批的历史案例重新拉回
     * 自动循环。ORDER BY 处理极少数迁移期重复数据时优先选择最新事实。</p>
     */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_case
            WHERE tenant_id = #{tenantId}
              AND sync_task_id = #{syncTaskId}
              AND current_execution_id = #{currentExecutionId}
              AND case_state = 'RECOVERY_STARTED'
            ORDER BY update_time DESC, case_id DESC
            LIMIT 1
            """)
    SyncAutopilotRecoveryCase selectRecoveringByCurrentExecution(
            @Param("tenantId") Long tenantId,
            @Param("syncTaskId") Long syncTaskId,
            @Param("currentExecutionId") Long currentExecutionId);

    /**
     * Inserts a case only if the same authorization and repair fingerprint have not already
     * opened one for the root execution. PostgreSQL ON CONFLICT avoids poisoning the surrounding
     * transaction with a duplicate-key exception during concurrent delivery.
     */
    @Insert("""
            INSERT INTO data_sync_autopilot_recovery_case (
                tenant_id, project_id, sync_task_id, root_execution_id, current_execution_id,
                execution_mode, authorization_digest, policy_digest, case_state, cycle, max_cycles,
                deadline_at, last_error_fingerprint, repeated_error_count, recovery_action, risk_level,
                repair_fingerprint, attention_reason, version, create_time, update_time
            ) VALUES (
                #{recoveryCase.tenantId}, #{recoveryCase.projectId}, #{recoveryCase.syncTaskId},
                #{recoveryCase.rootExecutionId}, #{recoveryCase.currentExecutionId},
                #{recoveryCase.executionMode}, #{recoveryCase.authorizationDigest},
                #{recoveryCase.policyDigest}, #{recoveryCase.caseState}, #{recoveryCase.cycle},
                #{recoveryCase.maxCycles}, #{recoveryCase.deadlineAt},
                #{recoveryCase.lastErrorFingerprint}, #{recoveryCase.repeatedErrorCount},
                #{recoveryCase.recoveryAction}, #{recoveryCase.riskLevel},
                #{recoveryCase.repairFingerprint}, #{recoveryCase.attentionReason},
                #{recoveryCase.version}, LOCALTIMESTAMP, LOCALTIMESTAMP
            )
            ON CONFLICT (tenant_id, sync_task_id, root_execution_id, authorization_digest, repair_fingerprint)
            DO NOTHING
            """)
    int insertIfAbsent(@Param("recoveryCase") SyncAutopilotRecoveryCase recoveryCase);

    /**
     * Advances exactly one legal state edge after the service has evaluated the state machine.
     *
     * <p>No updateById shortcut is permitted here: the expected state and optimistic version are
     * the database-side arbitration point for concurrent receipts.</p>
     */
    @Update("""
            UPDATE data_sync_autopilot_recovery_case
            SET case_state = #{targetState},
                current_execution_id = #{currentExecutionId},
                cycle = #{cycle},
                last_error_fingerprint = #{lastErrorFingerprint},
                repeated_error_count = #{repeatedErrorCount},
                attention_reason = #{attentionReason},
                version = version + 1,
                update_time = LOCALTIMESTAMP
            WHERE case_id = #{caseId}
              AND case_state = #{expectedState}
              AND version = #{expectedVersion}
            """)
    int transition(@Param("caseId") Long caseId,
                   @Param("expectedState") String expectedState,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("targetState") String targetState,
                   @Param("currentExecutionId") Long currentExecutionId,
                   @Param("cycle") Integer cycle,
                   @Param("lastErrorFingerprint") String lastErrorFingerprint,
                   @Param("repeatedErrorCount") Integer repeatedErrorCount,
                   @Param("attentionReason") String attentionReason);
}
