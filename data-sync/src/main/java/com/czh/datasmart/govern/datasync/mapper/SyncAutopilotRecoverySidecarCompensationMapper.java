/**
 * @Author : Cui
 * @Date: 2026/08/11 23:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoverySidecarCompensationMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoverySidecarCompensation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Conditional persistence operations for local Autopilot sidecar replay requests.
 *
 * <p>The insert is idempotent by compensation key. The claim, resolve, and retry updates require the expected
 * durable state, which prevents two data-sync instances from replaying the same row concurrently. Mapper SQL
 * only receives low-sensitive codes and timestamps; it has no column for an exception body or external payload.</p>
 */
@Mapper
public interface SyncAutopilotRecoverySidecarCompensationMapper
        extends BaseMapper<SyncAutopilotRecoverySidecarCompensation> {

    /** Finds an existing replay request by its deterministic idempotency key. */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_sidecar_compensation
            WHERE compensation_key = #{compensationKey}
            LIMIT 1
            """)
    SyncAutopilotRecoverySidecarCompensation selectByCompensationKey(
            @Param("compensationKey") String compensationKey);

    /** Inserts one replay request once, allowing concurrent duplicate catches to reuse the same durable row. */
    @Insert("""
            INSERT INTO data_sync_autopilot_recovery_sidecar_compensation (
                compensation_key, operation, sync_task_id, sync_execution_id, error_code, issue_codes_json,
                compensation_state, attempt_count, max_attempt_count, next_retry_at, create_time, update_time
            ) VALUES (
                #{compensation.compensationKey}, #{compensation.operation}, #{compensation.syncTaskId},
                #{compensation.syncExecutionId}, #{compensation.errorCode}, #{compensation.issueCodesJson},
                #{compensation.compensationState}, #{compensation.attemptCount},
                #{compensation.maxAttemptCount}, #{compensation.nextRetryAt}, LOCALTIMESTAMP, LOCALTIMESTAMP
            )
            ON CONFLICT (compensation_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("compensation") SyncAutopilotRecoverySidecarCompensation compensation);

    /** Selects retryable rows and abandoned claims in deterministic order for one bounded scheduler pass. */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_sidecar_compensation
            WHERE (
                    compensation_state IN ('PENDING', 'RETRY_WAIT')
                    AND (next_retry_at IS NULL OR next_retry_at <= LOCALTIMESTAMP)
                    AND COALESCE(attempt_count, 0) < max_attempt_count
                  )
               OR (
                    compensation_state = 'DISPATCHING'
                    AND last_attempt_at IS NOT NULL
                    AND last_attempt_at < LOCALTIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
                    AND COALESCE(attempt_count, 0) < max_attempt_count
                  )
            ORDER BY COALESCE(next_retry_at, create_time) ASC, id ASC
            LIMIT #{limit}
            """)
    List<SyncAutopilotRecoverySidecarCompensation> selectDue(
            @Param("limit") int limit,
            @Param("staleSeconds") long staleSeconds);

    /**
     * Dead-letters a stranded claim that has already consumed its final replay attempt.
     *
     * <p>A process can die after atomically incrementing {@code attempt_count} but before it records the replay
     * outcome. Reclaiming that row would exceed the persisted budget, while leaving it in {@code DISPATCHING}
     * would hide an operator-action condition. This conditional update resolves that narrow crash window before
     * a scheduler selects normal due work.</p>
     *
     * @param staleSeconds age after which a dispatching claim is considered abandoned
     * @param limit maximum number of exhausted rows finalized in one scheduler pass
     * @return number of rows newly made visible as dead letters
     */
    @Update("""
            WITH exhausted_claims AS (
                SELECT id
                FROM data_sync_autopilot_recovery_sidecar_compensation
                WHERE compensation_state = 'DISPATCHING'
                  AND COALESCE(attempt_count, 0) >= max_attempt_count
                  AND last_attempt_at IS NOT NULL
                  AND last_attempt_at < LOCALTIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
                ORDER BY last_attempt_at ASC, id ASC
                LIMIT #{limit}
                FOR UPDATE SKIP LOCKED
            )
            UPDATE data_sync_autopilot_recovery_sidecar_compensation compensation
            SET compensation_state = 'DEAD_LETTER',
                claim_token = NULL,
                next_retry_at = NULL,
                dead_letter_at = LOCALTIMESTAMP,
                last_error_code = 'AUTOPILOT_SIDECAR_REPLAY_BUDGET_EXHAUSTED',
                last_error_summary = 'Autopilot sidecar replay claim exceeded its retry budget',
                update_time = LOCALTIMESTAMP
            FROM exhausted_claims
            WHERE compensation.id = exhausted_claims.id
              AND compensation.compensation_state = 'DISPATCHING'
              AND COALESCE(compensation.attempt_count, 0) >= compensation.max_attempt_count
            """)
    int deadLetterExhaustedStaleClaims(@Param("staleSeconds") long staleSeconds, @Param("limit") int limit);

    /**
     * Claims one due row, increments its persisted attempt count, and assigns exclusive ownership to a token.
     *
     * <p>Every later success/failure transition must present the same token. This prevents a slow claimant whose
     * lease became stale from resolving or retrying a row after another instance has safely reclaimed it.</p>
     */
    @Update("""
            UPDATE data_sync_autopilot_recovery_sidecar_compensation
            SET compensation_state = 'DISPATCHING',
                claim_token = #{claimToken},
                attempt_count = COALESCE(attempt_count, 0) + 1,
                last_attempt_at = LOCALTIMESTAMP,
                update_time = LOCALTIMESTAMP
            WHERE id = #{id}
              AND (
                    (
                      compensation_state IN ('PENDING', 'RETRY_WAIT')
                      AND (next_retry_at IS NULL OR next_retry_at <= LOCALTIMESTAMP)
                      AND COALESCE(attempt_count, 0) < max_attempt_count
                    )
                    OR
                    (
                      compensation_state = 'DISPATCHING'
                      AND last_attempt_at IS NOT NULL
                      AND last_attempt_at < LOCALTIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
                      AND COALESCE(attempt_count, 0) < max_attempt_count
                    )
                  )
            """)
    int markDispatching(@Param("id") Long id,
                        @Param("staleSeconds") long staleSeconds,
                        @Param("claimToken") String claimToken);

    /** Marks a row resolved only when the caller still owns the corresponding durable claim token. */
    @Update("""
            UPDATE data_sync_autopilot_recovery_sidecar_compensation
            SET compensation_state = 'RESOLVED',
                claim_token = NULL,
                next_retry_at = NULL,
                resolved_at = LOCALTIMESTAMP,
                last_error_code = NULL,
                last_error_summary = NULL,
                update_time = LOCALTIMESTAMP
            WHERE id = #{id}
              AND compensation_state = 'DISPATCHING'
              AND claim_token = #{claimToken}
            """)
    int markResolved(@Param("id") Long id, @Param("claimToken") String claimToken);

    /** Converts an owned replay failure into retry-wait or a bounded terminal dead letter. */
    @Update("""
            UPDATE data_sync_autopilot_recovery_sidecar_compensation
            SET compensation_state = #{targetState},
                claim_token = NULL,
                next_retry_at = #{nextRetryAt},
                dead_letter_at = #{deadLetterAt},
                last_error_code = #{errorCode},
                last_error_summary = #{errorSummary},
                update_time = LOCALTIMESTAMP
            WHERE id = #{id}
              AND compensation_state = 'DISPATCHING'
              AND claim_token = #{claimToken}
            """)
    int markFailure(@Param("id") Long id,
                    @Param("claimToken") String claimToken,
                    @Param("targetState") String targetState,
                    @Param("nextRetryAt") LocalDateTime nextRetryAt,
                    @Param("deadLetterAt") LocalDateTime deadLetterAt,
                    @Param("errorCode") String errorCode,
                    @Param("errorSummary") String errorSummary);
}
