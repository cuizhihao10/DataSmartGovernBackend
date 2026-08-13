/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryQuarantineReceipt.java
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
 * Durable idempotency receipt for the autonomous quarantine side effect.
 *
 * <p>Only identifiers, counts, states, and SHA-256 bindings are stored. Selected IDs and source selectors stay
 * in the existing error-sample ledger and are never duplicated into this control-plane table.</p>
 */
@Data
@TableName("data_sync_autopilot_recovery_quarantine_receipt")
public class SyncAutopilotRecoveryQuarantineReceipt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String receiptId;
    private Long caseId;
    private String requestDigest;
    private String previewDigest;
    private String actionFingerprint;
    private Long syncTaskId;
    private Long executionId;
    /** User represented by the Agent Runtime service account. */
    private String representedActorId;
    /** Agent identity that made the bounded recovery decision. */
    private String agentId;
    /** Initial user authorization/delegation fact used by this autonomous action. */
    private String delegationId;
    private Integer selectedCount;
    private Integer affectedCount;
    private String operationState;
    private String receiptState;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
