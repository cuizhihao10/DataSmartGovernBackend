/**
 * @Author : Cui
 * @Date: 2026/08/11 23:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoverySidecarCompensation.java
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
 * Durable replay request for an Autopilot sidecar transaction that failed before the normal trigger outbox
 * could prove completion.
 *
 * <p>This is deliberately not a second Kafka outbox. It records only which local sidecar call must be retried:
 * a failed-execution trigger or a successful-execution finalization. A scheduler reloads the authoritative task
 * and execution and invokes the existing trigger publisher, which remains the only component allowed to create
 * and deliver a Kafka trigger outbox event. The stable {@code compensationKey} makes repeated catches of the
 * same sidecar exception reuse one row.</p>
 *
 * <p>The row stores low-sensitive, normalized error codes only. It never stores exception messages, SQL,
 * endpoint URLs, source records, credentials, model output, or a transport payload. Conditional mapper updates
 * own the retry state so multiple data-sync instances can safely compete to replay the same request.</p>
 */
@Data
@TableName("data_sync_autopilot_recovery_sidecar_compensation")
public class SyncAutopilotRecoverySidecarCompensation {

    /** Surrogate key used solely by conditional state-update SQL. */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Deterministic idempotency key derived from operation, task, execution, and safe failure fingerprint. */
    private String compensationKey;

    /** Closed operation name: {@code TRIGGER_FAILURE} or {@code SUCCESS_FINALIZATION}. */
    private String operation;

    /** Authoritative task identifier reloaded before a replay invokes the existing sidecar publisher. */
    private Long syncTaskId;

    /** Authoritative execution identifier reloaded and scope-checked before replay. */
    private Long syncExecutionId;

    /** Optional normalized primary error code needed only for a failed-execution trigger replay. */
    private String errorCode;

    /** JSON array of bounded normalized issue codes; never raw exception text or source data. */
    private String issueCodesJson;

    /** PENDING, DISPATCHING, RETRY_WAIT, RESOLVED, or DEAD_LETTER. */
    private String compensationState;

    /** Random token owned by the current scheduler claim; stale claimants cannot complete a newer claim. */
    private String claimToken;

    /** Number of conditional replay claims already made for this row. */
    private Integer attemptCount;

    /** Bounded retry budget captured when this row is first created. */
    private Integer maxAttemptCount;

    /** Earliest retry time after a replay-side exception. */
    private LocalDateTime nextRetryAt;

    /** Time a scheduler instance last claimed this row. */
    private LocalDateTime lastAttemptAt;

    /** Time the sidecar publisher returned normally for the replayed request. */
    private LocalDateTime resolvedAt;

    /** Time the bounded replay budget was exhausted. */
    private LocalDateTime deadLetterAt;

    /** Stable low-sensitive code describing the latest replay failure category. */
    private String lastErrorCode;

    /** Fixed low-sensitive summary; never an exception message. */
    private String lastErrorSummary;

    /** Database-managed creation time of the compensation request. */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** Database-managed time of the most recent retry-state transition. */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
