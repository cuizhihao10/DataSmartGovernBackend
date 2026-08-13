/**
 * @Author : Cui
 * @Date: 2026/08/11 00:15
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryCase.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable, low-sensitive authorization and lifecycle fact for one autopilot recovery attempt.
 *
 * <p>The record stores references, counters, enum codes, and SHA-256 fingerprints only. It never
 * stores a policy body, authorization text, SQL, checkpoint values, source records, credentials,
 * prompts, model responses, or external endpoint details.</p>
 *
 * <p>Lombok generates ordinary ORM accessors for MyBatis-Plus, but lifecycle writes must still go through the
 * recovery-case service and its conditional mapper update. In particular, {@code version} is not a decorative
 * field: callers use it with a receipt-backed transition to prevent a stale instance from overwriting a newer
 * state. The entity itself performs no authorization, idempotency, or execution work.</p>
 */
@Data
@TableName("data_sync_autopilot_recovery_case")
public class SyncAutopilotRecoveryCase {

    /** Generated recovery case identifier exposed only as a control-plane reference. */
    @TableId(value = "case_id", type = IdType.AUTO)
    private Long caseId;

    /** Tenant boundary copied from the owning sync task. */
    private Long tenantId;

    /** Optional project boundary copied from the owning sync task. */
    private Long projectId;

    /** Owning sync task. */
    private Long syncTaskId;

    /** First failed execution in the recovery lineage. */
    private Long rootExecutionId;

    /** Latest execution currently represented by this recovery case. */
    private Long currentExecutionId;

    /** Always AUTOPILOT for this strictly scoped table. */
    private String executionMode;

    /** SHA-256 binding of the user-confirmed authorization identifier. */
    private String authorizationDigest;

    /** SHA-256 binding of the canonicalized task-local autopilot policy. */
    private String policyDigest;

    /** State from SyncAutopilotRecoveryCaseState. */
    private String caseState;

    /** Current attempted recovery cycle, starting at one. */
    private Integer cycle;

    /** Maximum recovery cycle budget copied from the evaluated policy. */
    private Integer maxCycles;

    /** Absolute deadline derived from the evaluated policy. */
    private LocalDateTime deadlineAt;

    /** SHA-256 fingerprint of the latest low-sensitive error fact. */
    private String lastErrorFingerprint;

    /** Count of repeated occurrences of the latest error fingerprint. */
    private Integer repeatedErrorCount;

    /** Whitelisted action enum name. */
    private String recoveryAction;

    /** Proposed risk enum name. */
    private String riskLevel;

    /** SHA-256 action/repair fingerprint used to bind idempotent recovery intent. */
    private String repairFingerprint;

    /** Stable reason code only when automatic recovery requires human attention. */
    private String attentionReason;

    /** Optimistic version checked by every state transition. */
    private Long version;

    /** Database-managed time at which the governed case was first persisted. */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** Database-managed time of the last successful case/receipt-related update. */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
