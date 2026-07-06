/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncExecutionMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 同步执行记录 Mapper。
 */
@Mapper
public interface SyncExecutionMapper extends BaseMapper<SyncExecution> {

    /**
     * 查询下一条可认领的同步执行记录。
     *
     * <p>当前以 execution 表作为轻量队列：
     * 1. 只认领 QUEUED 状态；
     * 2. queued_at 为空或已到期才可认领，支持 defer 后延迟回队列；
     * 3. 可选 tenantId 过滤，为租户专属 worker 和租户公平调度预留边界。
     */
    @Select("""
            <script>
            SELECT *
            FROM data_sync_execution
            WHERE execution_state = 'QUEUED'
              AND (queued_at IS NULL OR queued_at &lt;= LOCALTIMESTAMP)
            <if test="tenantId != null">
              AND tenant_id = #{tenantId}
            </if>
            ORDER BY queued_at ASC, id ASC
            LIMIT 1
            </script>
            """)
    SyncExecution selectNextClaimCandidate(@Param("tenantId") Long tenantId);

    /**
     * 原子认领同步执行记录。
     *
     * <p>WHERE execution_state='QUEUED' 是并发裁决的关键。
     * 多个 worker 同时看到同一条候选记录时，只有一个能成功把状态改成 RUNNING。
     *
     * <p>这里虽然没有动态 if 标签，也仍然显式使用 {@code <script>} 包住 SQL。
     * 原因是注解 SQL 中如果直接写 {@code <=}，MyBatis XML 语言驱动可能把它当成未闭合标签；
     * 但如果只写 {@code &lt;=} 而不进入脚本解析，JDBC 又会收到字面量 {@code &lt;=}。
     * 因此“脚本包装 + XML 转义”是注解 Mapper 中表达比较符的稳定写法。
     */
    @Update("""
            <script>
            UPDATE data_sync_execution
            SET execution_state = 'RUNNING',
                executor_id = #{executorId},
                started_at = COALESCE(started_at, LOCALTIMESTAMP),
                heartbeat_time = LOCALTIMESTAMP,
                lease_expire_time = LOCALTIMESTAMP + (#{leaseSeconds} * INTERVAL '1 second'),
                update_time = LOCALTIMESTAMP
            WHERE id = #{executionId}
              AND execution_state = 'QUEUED'
              AND (queued_at IS NULL OR queued_at &lt;= LOCALTIMESTAMP)
            </script>
            """)
    int claimQueuedExecution(@Param("executionId") Long executionId,
                             @Param("executorId") String executorId,
                             @Param("leaseSeconds") long leaseSeconds);

    /**
     * 执行器心跳续租。
     */
    @Update("""
            UPDATE data_sync_execution
            SET records_read = #{recordsRead},
                records_written = #{recordsWritten},
                heartbeat_time = LOCALTIMESTAMP,
                lease_expire_time = LOCALTIMESTAMP + (#{leaseSeconds} * INTERVAL '1 second'),
                update_time = LOCALTIMESTAMP
            WHERE id = #{executionId}
              AND execution_state = 'RUNNING'
              AND executor_id = #{executorId}
            """)
    int heartbeatLease(@Param("executionId") Long executionId,
                       @Param("executorId") String executorId,
                       @Param("recordsRead") Long recordsRead,
                       @Param("recordsWritten") Long recordsWritten,
                       @Param("leaseSeconds") long leaseSeconds);

    /**
     * 执行器主动退避当前执行记录。
     *
     * <p>这里不用 MyBatis-Plus 的 updateById，是因为退避需要显式把 executor_id、heartbeat_time、lease_expire_time 清空。
     * MyBatis-Plus 默认更新策略通常不会把 null 字段写入数据库，如果用实体更新，容易出现“代码里设为 null 但数据库仍保留旧租约”的隐性 bug。
     *
     * <p>当 defer_count 达到 maxDeferCount 后，execution 不再回到 QUEUED，而是进入 FAILED：
     * 1. 防止故障任务在队列中无限循环；
     * 2. 释放 worker 资源；
     * 3. 让任务主状态进入 AWAITING_OPERATOR_ACTION，由运营人员判断是否修配置、扩容、重跑或取消。
     */
    @Update("""
            UPDATE data_sync_execution
            SET execution_state = CASE
                    WHEN COALESCE(defer_count, 0) + 1 >= #{maxDeferCount} THEN 'FAILED'
                    ELSE 'QUEUED'
                END,
                queued_at = CASE
                    WHEN COALESCE(defer_count, 0) + 1 >= #{maxDeferCount} THEN NULL
                    ELSE LOCALTIMESTAMP + (#{delaySeconds} * INTERVAL '1 second')
                END,
                finished_at = CASE
                    WHEN COALESCE(defer_count, 0) + 1 >= #{maxDeferCount} THEN LOCALTIMESTAMP
                    ELSE finished_at
                END,
                executor_id = NULL,
                heartbeat_time = NULL,
                lease_expire_time = NULL,
                defer_count = COALESCE(defer_count, 0) + 1,
                error_summary = #{reason},
                update_time = LOCALTIMESTAMP
            WHERE id = #{executionId}
              AND execution_state = 'RUNNING'
              AND executor_id = #{executorId}
            """)
    int deferRunningExecution(@Param("executionId") Long executionId,
                              @Param("executorId") String executorId,
                              @Param("delaySeconds") long delaySeconds,
                              @Param("reason") String reason,
                              @Param("maxDeferCount") int maxDeferCount);

    /**
     * 扫描租约已经过期的 RUNNING 执行记录。
     *
     * <p>过期租约通常意味着 worker 失联、进程崩溃、网络隔离或执行器长时间无心跳。
     * 这里先按 lease_expire_time 从早到晚扫描一批，为后续定时恢复和运维恢复复用。
     */
    @Select("""
            <script>
            SELECT *
            FROM data_sync_execution
            WHERE execution_state = 'RUNNING'
              AND lease_expire_time IS NOT NULL
              AND lease_expire_time &lt; LOCALTIMESTAMP
            <if test="tenantId != null">
              AND tenant_id = #{tenantId}
            </if>
            ORDER BY lease_expire_time ASC, id ASC
            LIMIT #{limit}
            </script>
            """)
    List<SyncExecution> selectExpiredRunningLeases(@Param("tenantId") Long tenantId,
                                                   @Param("limit") int limit);

    /**
     * 将过期 RUNNING 执行记录恢复回 QUEUED，或在超过退避上限后转入 FAILED。
     *
     * <p>WHERE 条件再次确认状态与租约时间，避免扫描后执行器刚好完成或续租时被错误回收。
     * CASE 表达式负责把“可恢复”和“需要人工介入”合并成一次原子更新，避免服务层先读 deferCount 再写状态时发生并发竞争。
     *
     * <p>同 {@link #claimQueuedExecution(Long, String, long)}，这里用 {@code <script>} 让
     * {@code &lt;} 在执行前被 MyBatis 还原为 PostgreSQL 能理解的比较符。
     */
    @Update("""
            <script>
            UPDATE data_sync_execution
            SET execution_state = CASE
                    WHEN COALESCE(defer_count, 0) + 1 >= #{maxDeferCount} THEN 'FAILED'
                    ELSE 'QUEUED'
                END,
                queued_at = CASE
                    WHEN COALESCE(defer_count, 0) + 1 >= #{maxDeferCount} THEN NULL
                    ELSE LOCALTIMESTAMP
                END,
                finished_at = CASE
                    WHEN COALESCE(defer_count, 0) + 1 >= #{maxDeferCount} THEN LOCALTIMESTAMP
                    ELSE finished_at
                END,
                executor_id = NULL,
                heartbeat_time = NULL,
                lease_expire_time = NULL,
                defer_count = COALESCE(defer_count, 0) + 1,
                error_summary = #{reason},
                update_time = LOCALTIMESTAMP
            WHERE id = #{executionId}
              AND execution_state = 'RUNNING'
              AND lease_expire_time IS NOT NULL
              AND lease_expire_time &lt; LOCALTIMESTAMP
            </script>
            """)
    int requeueExpiredLease(@Param("executionId") Long executionId,
                            @Param("reason") String reason,
                            @Param("maxDeferCount") int maxDeferCount);

    /**
     * 将已经终结的父 execution 重新放回队列，用于对象级失败重试。
     *
     * <p>该方法只服务于 OBJECT_LIST 对象级账本恢复，不是通用 retry：</p>
     * <p>1. WHERE 条件只允许 PARTIALLY_SUCCEEDED/FAILED，避免把仍在 RUNNING 的 execution 被人工重置；</p>
     * <p>2. executor_id、heartbeat_time、lease_expire_time 必须清空，否则 worker 可能误判该 execution 仍被旧执行器持有；</p>
     * <p>3. finished_at 必须清空，因为这条 execution 即将重新进入运行窗口，不再是完整终态事实；</p>
     * <p>4. queued_at 写当前时间，让普通 worker claim 查询可以立即认领。</p>
     *
     * <p>为什么不新建 execution：对象级恢复要复用同一父 execution 下的对象账本，fan-out 才能跳过已成功对象。
     * 如果新建 execution，就需要复制成功/失败对象账本、建立 parentExecutionId 关系和新的汇总语义，当前收敛阶段
     * 会明显增加控制面复杂度。</p>
     */
    @Update("""
            UPDATE data_sync_execution
            SET execution_state = 'QUEUED',
                queued_at = LOCALTIMESTAMP,
                finished_at = NULL,
                executor_id = NULL,
                heartbeat_time = NULL,
                lease_expire_time = NULL,
                error_summary = #{reason},
                update_time = LOCALTIMESTAMP
            WHERE id = #{executionId}
              AND execution_state IN ('PARTIALLY_SUCCEEDED', 'FAILED')
            """)
    int requeueTerminalObjectLevelRetry(@Param("executionId") Long executionId,
                                        @Param("reason") String reason);
}
