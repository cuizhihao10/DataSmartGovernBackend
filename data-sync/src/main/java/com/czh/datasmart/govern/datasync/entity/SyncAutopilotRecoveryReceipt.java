/**
 * @Author : Cui
 * @Date: 2026/08/11 00:15
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryReceipt.java
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
 * Durable idempotency receipt for one recovery-case decision or state transition.
 *
 * <p>Its global receiptId uniqueness lets an at-least-once caller retry safely across process
 * restarts and service instances. The receipt contains only case references, digest, type, state,
 * and version; it does not duplicate recovery inputs or execution payloads.</p>
 *
 * <p>The receipt is a two-phase audit fact: {@code PROCESSING} means one transaction reserved the idempotency
 * key, and {@code COMPLETED} means the matching case mutation committed with the recorded result. Lombok
 * accessors exist for ORM mapping only; callers must not update this entity directly to forge a replay result
 * or bypass case-service digest/version checks.</p>
 */
@Data
@TableName("data_sync_autopilot_recovery_receipt")
public class SyncAutopilotRecoveryReceipt {

    /** Surrogate database key; receiptId is the business idempotency key. */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Caller supplied stable idempotency receipt identifier. */
    private String receiptId;

    /** Recovery case protected by this receipt. */
    private Long caseId;

    /** SHA-256 digest of the low-sensitive receipt request facts. */
    private String receiptDigest;

    /** SyncAutopilotRecoveryReceiptType enum name. */
    private String receiptType;

    /** PROCESSING until the conditional case update succeeds, then COMPLETED. */
    private String receiptState;

    /** Resulting case state for a completed receipt. */
    private String resultingCaseState;

    /** Resulting optimistic case version for a completed receipt. */
    private Long resultingVersion;

    /** Database-managed creation time of the receipt reservation. */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** Database-managed time at which processing/result fields were last updated. */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
