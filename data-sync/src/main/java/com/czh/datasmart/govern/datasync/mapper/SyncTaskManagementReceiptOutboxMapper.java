/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - SyncTaskManagementReceiptOutboxMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncTaskManagementReceiptOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * task-management receipt outbox Mapper。
 *
 * <p>基础 insert/select 交给 MyBatis-Plus，涉及“领取投递权、标记成功、标记失败重试”的状态推进使用显式 SQL。
 * 这样能把并发裁决条件写在数据库 UPDATE 中，而不是先读后写，避免多实例同时投递同一 receipt。</p>
 */
@Mapper
public interface SyncTaskManagementReceiptOutboxMapper extends BaseMapper<SyncTaskManagementReceiptOutbox> {

    /**
     * 按稳定 receiptId 查询 outbox。
     */
    @Select("""
            SELECT *
            FROM data_sync_task_management_receipt_outbox
            WHERE receipt_id = #{receiptId}
            LIMIT 1
            """)
    SyncTaskManagementReceiptOutbox selectByReceiptId(@Param("receiptId") String receiptId);

    /**
     * 查询本轮可投递的 outbox。
     *
     * <p>除了 PENDING/RETRY_WAIT，本 SQL 还会捞取“卡在 DELIVERING 且 last_attempt_at 已过期”的记录。
     * 这是为了处理服务进程在调用 task-management 过程中崩溃的场景：记录已经被标记为 DELIVERING，
     * 但还没来得及写 DELIVERED 或 RETRY_WAIT，后台调度器需要能把它重新纳入补偿。</p>
     */
    @Select("""
            SELECT *
            FROM data_sync_task_management_receipt_outbox
            WHERE (
                    outbox_state IN ('PENDING', 'RETRY_WAIT')
                    AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                  )
               OR (
                    outbox_state = 'DELIVERING'
                    AND last_attempt_at IS NOT NULL
                    AND last_attempt_at < DATE_SUB(NOW(), INTERVAL #{staleDeliveringSeconds} SECOND)
                  )
            ORDER BY COALESCE(next_retry_at, create_time) ASC, id ASC
            LIMIT #{limit}
            """)
    List<SyncTaskManagementReceiptOutbox> selectDueReceipts(@Param("limit") int limit,
                                                            @Param("staleDeliveringSeconds") long staleDeliveringSeconds);

    /**
     * 原子领取投递权。
     *
     * <p>返回 1 表示当前实例拿到了投递权；返回 0 表示其它实例已经抢先处理或记录尚未到重试时间。
     * attempt_count 在领取时递增，而不是失败后递增，是为了统计“真实发起过几次投递尝试”。</p>
     */
    @Update("""
            UPDATE data_sync_task_management_receipt_outbox
            SET outbox_state = 'DELIVERING',
                attempt_count = COALESCE(attempt_count, 0) + 1,
                last_attempt_at = NOW(),
                update_time = NOW()
            WHERE id = #{id}
              AND (
                    (
                      outbox_state IN ('PENDING', 'RETRY_WAIT')
                      AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                    )
                    OR
                    (
                      outbox_state = 'DELIVERING'
                      AND last_attempt_at IS NOT NULL
                      AND last_attempt_at < DATE_SUB(NOW(), INTERVAL #{staleDeliveringSeconds} SECOND)
                    )
                  )
            """)
    int markDelivering(@Param("id") Long id,
                       @Param("staleDeliveringSeconds") long staleDeliveringSeconds);

    /**
     * 标记投递成功。
     */
    @Update("""
            UPDATE data_sync_task_management_receipt_outbox
            SET outbox_state = 'DELIVERED',
                delivered_at = NOW(),
                next_retry_at = NULL,
                last_error_code = NULL,
                last_error_summary = NULL,
                update_time = NOW()
            WHERE id = #{id}
              AND outbox_state = 'DELIVERING'
            """)
    int markDelivered(@Param("id") Long id);

    /**
     * 标记失败后的下一状态。
     *
     * <p>targetState 可以是 RETRY_WAIT 或 DEAD_LETTER。进入 DEAD_LETTER 时 nextRetryAt 为空、deadLetterAt 非空；
     * 进入 RETRY_WAIT 时 nextRetryAt 非空、deadLetterAt 为空。</p>
     */
    @Update("""
            UPDATE data_sync_task_management_receipt_outbox
            SET outbox_state = #{targetState},
                next_retry_at = #{nextRetryAt},
                dead_letter_at = #{deadLetterAt},
                last_error_code = #{lastErrorCode},
                last_error_summary = #{lastErrorSummary},
                update_time = NOW()
            WHERE id = #{id}
              AND outbox_state = 'DELIVERING'
            """)
    int markDeliveryFailure(@Param("id") Long id,
                            @Param("targetState") String targetState,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt,
                            @Param("deadLetterAt") LocalDateTime deadLetterAt,
                            @Param("lastErrorCode") String lastErrorCode,
                            @Param("lastErrorSummary") String lastErrorSummary);
}
