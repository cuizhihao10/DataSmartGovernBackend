/**
 * @Author : Cui
 * @Date: 2026/08/11 18:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerOutboxMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Autopilot trigger outbox 的并发安全数据库操作。
 *
 * <p>所有“认领、成功、失败”更新都带当前状态条件。这样两个 data-sync 实例即使同时扫描到
 * 同一条记录，也只有一个实例能把它从可投递状态改为 DISPATCHING。</p>
 */
@Mapper
public interface SyncAutopilotRecoveryTriggerOutboxMapper
        extends BaseMapper<SyncAutopilotRecoveryTriggerOutbox> {

    /** 按稳定 eventId 查询，供重复失败回调复用同一 outbox。 */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_trigger_outbox
            WHERE event_id = #{eventId}
            LIMIT 1
            """)
    SyncAutopilotRecoveryTriggerOutbox selectByEventId(@Param("eventId") String eventId);

    /**
     * Loads the exact immutable trigger that a consumer callback claims to have handled.
     *
     * <p>The callback has to supply both the event ID and the execution ID carried by the original event. Using
     * the pair prevents an event ID from being replayed against another execution lineage and gives the service a
     * durable source of truth before it accepts any consumer-result facts.</p>
     *
     * @param eventId stable outbox idempotency identifier
     * @param currentExecutionId execution identity copied from the original outbox event
     * @return matching outbox row, or {@code null} when the callback does not belong to a known trigger
     */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_trigger_outbox
            WHERE event_id = #{eventId}
              AND current_execution_id = #{currentExecutionId}
            LIMIT 1
            """)
    SyncAutopilotRecoveryTriggerOutbox selectByEventIdAndCurrentExecutionId(
            @Param("eventId") String eventId,
            @Param("currentExecutionId") Long currentExecutionId);

    /**
     * Loads the latest durable trigger/result fact for a task execution without returning payload JSON.
     *
     * <p>The mapper materializes the entity because dispatch code already owns that mapping, but the public query
     * service explicitly projects only finite state codes, counters, and timestamps.</p>
     */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_trigger_outbox
            WHERE tenant_id = #{tenantId}
              AND sync_task_id = #{syncTaskId}
              AND (root_execution_id = #{executionId} OR current_execution_id = #{executionId})
            ORDER BY update_time DESC, id DESC
            LIMIT 1
            """)
    SyncAutopilotRecoveryTriggerOutbox selectLatestByTaskExecution(
            @Param("tenantId") Long tenantId,
            @Param("syncTaskId") Long syncTaskId,
            @Param("executionId") Long executionId);

    /**
     * 并发插入时使用 ON CONFLICT DO NOTHING，避免重复键异常污染调用方事务。
     */
    @Insert("""
            INSERT INTO data_sync_autopilot_recovery_trigger_outbox (
                event_id, tenant_id, project_id, sync_task_id, root_execution_id,
                current_execution_id, cycle, payload_json, outbox_state, attempt_count,
                max_attempt_count, next_retry_at, create_time, update_time
            ) VALUES (
                #{outbox.eventId}, #{outbox.tenantId}, #{outbox.projectId}, #{outbox.syncTaskId},
                #{outbox.rootExecutionId}, #{outbox.currentExecutionId}, #{outbox.cycle},
                #{outbox.payloadJson}, #{outbox.outboxState}, #{outbox.attemptCount},
                #{outbox.maxAttemptCount}, #{outbox.nextRetryAt}, LOCALTIMESTAMP, LOCALTIMESTAMP
            )
            ON CONFLICT (event_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("outbox") SyncAutopilotRecoveryTriggerOutbox outbox);

    /**
     * 查询到期记录，同时回收因进程崩溃而长期停在 DISPATCHING 的记录。
     */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_trigger_outbox
            WHERE (
                    outbox_state IN ('PENDING', 'RETRY_WAIT')
                    AND (next_retry_at IS NULL OR next_retry_at <= LOCALTIMESTAMP)
                  )
               OR (
                    outbox_state = 'DISPATCHING'
                    AND last_attempt_at IS NOT NULL
                    AND last_attempt_at < LOCALTIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
                  )
            ORDER BY COALESCE(next_retry_at, create_time) ASC, id ASC
            LIMIT #{limit}
            """)
    List<SyncAutopilotRecoveryTriggerOutbox> selectDue(
            @Param("limit") int limit,
            @Param("staleSeconds") long staleSeconds);

    /** 原子认领投递权，并在认领时递增真实尝试次数。 */
    @Update("""
            UPDATE data_sync_autopilot_recovery_trigger_outbox
            SET outbox_state = 'DISPATCHING',
                attempt_count = COALESCE(attempt_count, 0) + 1,
                last_attempt_at = LOCALTIMESTAMP,
                update_time = LOCALTIMESTAMP
            WHERE id = #{id}
              AND (
                    (
                      outbox_state IN ('PENDING', 'RETRY_WAIT')
                      AND (next_retry_at IS NULL OR next_retry_at <= LOCALTIMESTAMP)
                    )
                    OR
                    (
                      outbox_state = 'DISPATCHING'
                      AND last_attempt_at IS NOT NULL
                      AND last_attempt_at < LOCALTIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
                    )
                  )
            """)
    int markDispatching(@Param("id") Long id, @Param("staleSeconds") long staleSeconds);

    /** Kafka broker 确认后将记录标记为最终 DELIVERED。 */
    @Update("""
            UPDATE data_sync_autopilot_recovery_trigger_outbox
            SET outbox_state = 'DELIVERED',
                delivered_at = LOCALTIMESTAMP,
                next_retry_at = NULL,
                last_error_code = NULL,
                last_error_summary = NULL,
                update_time = LOCALTIMESTAMP
            WHERE id = #{id}
              AND outbox_state = 'DISPATCHING'
            """)
    int markDelivered(@Param("id") Long id);

    /**
     * Atomically persists the first authenticated consumer result for one exact trigger.
     *
     * <p>All consumer-result columns must still be null for this update to win. A second callback therefore
     * cannot overwrite the first result: its caller reloads the row and either replays an equal server-computed
     * digest or fails closed when the facts differ. This write never receives a raw model response or arbitrary
     * payload, only the already validated short codes, IDs, evidence count, and evidence-ID digest.</p>
     *
     * @param eventId stable outbox event identity
     * @param currentExecutionId execution bound to the original event
     * @param consumerResultDigest server-computed SHA-256 binding of the callback facts
     * @param consumerResultStatus server-whitelisted consumer result status
     * @param consumerResultReasonCode short server-validated reason code
     * @param consumerResultCaseId optional recovery case created by the consumer
     * @param retrievalDecision optional SEARCH/SKIP decision when planning completed
     * @param retrievalStrategy optional bounded planner strategy code
     * @param retrievalEvidenceCount optional grounded evidence count
     * @param retrievalEvidenceDigest optional SHA-256 evidence-ID digest
     * @return {@code 1} only for the first accepted result; {@code 0} means an existing result or identity race
     */
    @Update("""
            UPDATE data_sync_autopilot_recovery_trigger_outbox
            SET consumer_result_digest = #{consumerResultDigest},
                consumer_result_status = #{consumerResultStatus},
                consumer_result_reason_code = #{consumerResultReasonCode},
                consumer_result_case_id = #{consumerResultCaseId},
                retrieval_decision = #{retrievalDecision},
                retrieval_strategy = #{retrievalStrategy},
                retrieval_evidence_count = #{retrievalEvidenceCount},
                retrieval_evidence_digest = #{retrievalEvidenceDigest},
                consumed_at = LOCALTIMESTAMP,
                update_time = LOCALTIMESTAMP
            WHERE event_id = #{eventId}
              AND current_execution_id = #{currentExecutionId}
              AND consumer_result_digest IS NULL
              AND consumer_result_status IS NULL
              AND consumer_result_reason_code IS NULL
              AND consumer_result_case_id IS NULL
              AND retrieval_decision IS NULL
              AND retrieval_strategy IS NULL
              AND retrieval_evidence_count IS NULL
              AND retrieval_evidence_digest IS NULL
              AND consumed_at IS NULL
            """)
    int markConsumerResultIfAbsent(
            @Param("eventId") String eventId,
            @Param("currentExecutionId") Long currentExecutionId,
            @Param("consumerResultDigest") String consumerResultDigest,
            @Param("consumerResultStatus") String consumerResultStatus,
            @Param("consumerResultReasonCode") String consumerResultReasonCode,
            @Param("consumerResultCaseId") Long consumerResultCaseId,
            @Param("retrievalDecision") String retrievalDecision,
            @Param("retrievalStrategy") String retrievalStrategy,
            @Param("retrievalEvidenceCount") Integer retrievalEvidenceCount,
            @Param("retrievalEvidenceDigest") String retrievalEvidenceDigest);

    /** 记录可重试失败或最终死信；错误正文不会写入数据库。 */
    @Update("""
            UPDATE data_sync_autopilot_recovery_trigger_outbox
            SET outbox_state = #{targetState},
                next_retry_at = #{nextRetryAt},
                dead_letter_at = #{deadLetterAt},
                last_error_code = #{errorCode},
                last_error_summary = #{errorSummary},
                update_time = LOCALTIMESTAMP
            WHERE id = #{id}
              AND outbox_state = 'DISPATCHING'
            """)
    int markFailure(@Param("id") Long id,
                    @Param("targetState") String targetState,
                    @Param("nextRetryAt") LocalDateTime nextRetryAt,
                    @Param("deadLetterAt") LocalDateTime deadLetterAt,
                    @Param("errorCode") String errorCode,
                    @Param("errorSummary") String errorSummary);
}
