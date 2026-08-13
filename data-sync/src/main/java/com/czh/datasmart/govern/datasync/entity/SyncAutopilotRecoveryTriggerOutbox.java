/**
 * @Author : Cui
 * @Date: 2026/08/11 18:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerOutbox.java
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
 * data-sync 本地持久化的 Autopilot 恢复触发 outbox。
 *
 * <p>先写 outbox 再发 Kafka，可覆盖“执行已经失败，但进程在发送消息前崩溃”的窗口。eventId
 * 唯一约束让同一失败回调被重复投递时复用原记录；状态更新使用条件 SQL，避免多个实例同时
 * 发送同一事件。</p>
 *
 * <p>Lombok accessors support ORM materialization only. Dispatcher code must claim and update this entity with
 * mapper conditions rather than saving a stale object, because {@code outboxState}, attempts, and retry times
 * are the durable concurrency protocol. The payload is a fixed low-sensitive event contract, never a place to
 * store broker credentials, source records, SQL, raw errors, or caller-chosen routing.</p>
 */
@Data
@TableName("data_sync_autopilot_recovery_trigger_outbox")
public class SyncAutopilotRecoveryTriggerOutbox {

    /** Surrogate database key used only by conditional claim/update SQL. */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 由失败执行、轮次和错误指纹计算的稳定幂等 ID。 */
    private String eventId;

    /** Tenant boundary copied from the authoritative failed sync task. */
    private Long tenantId;
    /** Optional project boundary copied from the authoritative failed sync task. */
    private Long projectId;
    /** Task used as the fixed Kafka partition key and durable ownership reference. */
    private Long syncTaskId;
    /** First failed execution in the bounded recovery lineage. */
    private Long rootExecutionId;
    /** Execution whose failure produced this particular trigger event. */
    private Long currentExecutionId;
    /** One-based recovery cycle represented by this immutable event payload. */
    private Integer cycle;

    /** 低敏事件 JSON；字段集合由 SyncAutopilotRecoveryTriggerEvent 固定。 */
    private String payloadJson;

    /** PENDING、DISPATCHING、RETRY_WAIT、DELIVERED 或 DEAD_LETTER。 */
    private String outboxState;
    /** Number of broker delivery attempts already claimed for this row. */
    private Integer attemptCount;
    /** Bounded retry budget captured when the row was created. */
    private Integer maxAttemptCount;
    /** Earliest time a RETRY_WAIT row may be selected again. */
    private LocalDateTime nextRetryAt;
    /** Time the row was last conditionally claimed for dispatch. */
    private LocalDateTime lastAttemptAt;
    /** Broker acknowledgement time for the terminal DELIVERED state. */
    private LocalDateTime deliveredAt;
    /** Time retry budget was exhausted and the row entered DEAD_LETTER. */
    private LocalDateTime deadLetterAt;
    /** Stable low-sensitive error code for the most recent failed delivery. */
    private String lastErrorCode;
    /** Fixed low-sensitive summary; never an exception body, payload, or broker credential. */
    private String lastErrorSummary;

    /**
     * SHA-256 binding of the authenticated consumer-result facts, calculated only by data-sync.
     *
     * <p>The digest lets a repeated callback prove it carries the same status, reason, case, and execution
     * facts without storing a Python response, model explanation, exception body, or any raw consumer payload.</p>
     */
    private String consumerResultDigest;
    /** Strict server-validated consumer outcome enum such as RECOVERY_STARTED or ATTENTION_REQUIRED. */
    private String consumerResultStatus;
    /** Short uppercase reason code only; model prose and raw failure text are never persisted here. */
    private String consumerResultReasonCode;
    /** Optional data-sync recovery case identified by the consumer result when one was durably created. */
    private Long consumerResultCaseId;
    /** Model-selected SEARCH/SKIP decision; null only when planning did not complete. */
    private String retrievalDecision;
    /** Bounded planner strategy code such as RAG or STRUCTURED_DIAGNOSTIC. */
    private String retrievalStrategy;
    /** Number of grounded RAG evidence IDs; zero for SKIP. */
    private Integer retrievalEvidenceCount;
    /** SHA-256 digest of grounded RAG evidence IDs; never a document body or model answer. */
    private String retrievalEvidenceDigest;
    /** First durable acceptance time of the consumer result; idempotent replays preserve this timestamp. */
    private LocalDateTime consumedAt;

    /** Database-managed creation time of the durable failure-to-event handoff record. */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** Database-managed time of the most recent state/attempt update. */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
