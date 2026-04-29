/**
 * @Author : Cui
 * @Date: 2026/04/27 20:10
 * @Description DataSmart Govern Backend - PermissionEventOutboxMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 权限事件 outbox Mapper。
 *
 * <p>这里除了 MyBatis-Plus 基础 CRUD，还提供定时投递器需要的“待发送查询”和“状态推进”SQL。
 * 状态推进使用条件更新，是为了避免多线程或多实例投递器重复处理同一条事件。
 */
public interface PermissionEventOutboxMapper extends BaseMapper<PermissionEventOutbox> {

    /**
     * 查询当前可投递的事件。
     *
     * <p>PENDING 表示从未发送；FAILED 表示之前发送失败但仍未超过最大重试次数。
     * next_retry_time 用于控制退避重试，避免 Kafka 故障时无节制重试。
     */
    @Select("""
            SELECT *
            FROM permission_event_outbox
            WHERE status IN ('PENDING', 'FAILED')
              AND attempt_count < max_attempts
              AND (next_retry_time IS NULL OR next_retry_time <= NOW())
            ORDER BY create_time ASC
            LIMIT #{batchSize}
            """)
    List<PermissionEventOutbox> selectDispatchable(@Param("batchSize") int batchSize);

    /**
     * 尝试声明某条事件的发送权。
     *
     * <p>只有 PENDING/FAILED 状态能进入 SENDING。
     * 如果返回 0，说明该事件已经被其他投递线程或实例抢先处理。
     */
    @Update("""
            UPDATE permission_event_outbox
            SET status = 'SENDING',
                update_time = NOW()
            WHERE id = #{id}
              AND status IN ('PENDING', 'FAILED')
            """)
    int markSending(@Param("id") Long id);

    /**
     * 标记事件发送成功。
     */
    @Update("""
            UPDATE permission_event_outbox
            SET status = 'SENT',
                sent_time = NOW(),
                update_time = NOW(),
                last_error = NULL
            WHERE id = #{id}
            """)
    int markSent(@Param("id") Long id);

    /**
     * 标记事件发送失败并设置下次重试时间。
     */
    @Update("""
            UPDATE permission_event_outbox
            SET status = CASE WHEN attempt_count + 1 >= max_attempts THEN 'DEAD' ELSE 'FAILED' END,
                attempt_count = attempt_count + 1,
                last_error = #{lastError},
                next_retry_time = DATE_ADD(NOW(), INTERVAL #{retryDelaySeconds} SECOND),
                update_time = NOW()
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("lastError") String lastError,
                   @Param("retryDelaySeconds") long retryDelaySeconds);

    /**
     * 恢复长时间卡在 SENDING 的事件。
     *
     * <p>如果服务在发送过程中崩溃，事件可能停留在 SENDING。
     * 该 SQL 会把超时的 SENDING 事件拉回 FAILED，允许后续继续重试。
     */
    @Update("""
            UPDATE permission_event_outbox
            SET status = 'FAILED',
                last_error = 'SENDING timeout recovered by dispatcher',
                next_retry_time = NOW(),
                update_time = NOW()
            WHERE status = 'SENDING'
              AND update_time < DATE_SUB(NOW(), INTERVAL #{sendingTimeoutSeconds} SECOND)
            """)
    int recoverStaleSending(@Param("sendingTimeoutSeconds") long sendingTimeoutSeconds);

    /**
     * 人工把失败或已忽略事件重新放回待投递队列。
     *
     * <p>该 SQL 只允许从 FAILED、DEAD、IGNORED 回到 PENDING：
     * 1. SENT 已经投递成功，不应重复发送；
     * 2. SENDING 可能正在被投递器处理，直接抢占会造成并发语义不清；
     * 3. PENDING 本来就在等待投递，不需要人工重置。
     *
     * <p>attempt_count 重置为 0，是为了给人工修复后的事件一个完整重试窗口。
     */
    @Update("""
            UPDATE permission_event_outbox
            SET status = 'PENDING',
                attempt_count = 0,
                next_retry_time = NOW(),
                last_error = #{reason},
                update_time = NOW()
            WHERE id = #{id}
              AND status IN ('FAILED', 'DEAD', 'IGNORED')
            """)
    int markManualRetry(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 人工忽略待处理或失败事件。
     *
     * <p>只允许忽略 PENDING、FAILED、DEAD：
     * SENDING 事件可能正在发送，忽略它会和投递器并发冲突；
     * SENT 事件已经成功投递，忽略没有业务意义；
     * IGNORED 事件已经被处置，不需要重复操作。
     */
    @Update("""
            UPDATE permission_event_outbox
            SET status = 'IGNORED',
                last_error = #{reason},
                update_time = NOW()
            WHERE id = #{id}
              AND status IN ('PENDING', 'FAILED', 'DEAD')
            """)
    int markIgnored(@Param("id") Long id, @Param("reason") String reason);
}
