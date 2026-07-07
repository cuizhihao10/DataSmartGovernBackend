/**
 * @Author : Cui
 * @Date: 2026/05/07 21:29
 * @Description DataSmart Govern Backend - SyncTaskMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务 Mapper。
 *
 * <p>普通 CRUD 继续交给 MyBatis-Plus，状态机相关的“带副作用更新”放在这里显式声明。
 * 原因是任务状态、人工介入标记和最近 executionId 是面向运营台的一组一致性字段，
 * 不能因为实体更新策略忽略 null 而出现 attentionRequired=false 但 attentionReason 仍残留的情况。
 */
@Mapper
public interface SyncTaskMapper extends BaseMapper<SyncTask> {

    /**
     * 扫描已经到期的定时同步任务。
     *
     * <p>该查询只找 {@code schedule_enabled=true + current_state=SCHEDULED + next_fire_time<=now} 的任务。
     * 这样可以清晰区分三类对象：</p>
     * <p>1. 普通手动任务：没有启用调度，不会被后台误触发；</p>
     * <p>2. 待审批或暂停任务：即使有 scheduleConfig，也不会在审批/暂停期间被触发；</p>
     * <p>3. 真正可调度任务：由后续 {@link #advanceScheduledTaskAfterDispatch} 使用 schedule_version 原子抢占。</p>
     *
     * <p>limit 由配置裁剪，避免一次扫描拉出过多任务造成调度抖动。多实例部署时，每个实例都可以扫描，
     * 但最终只有原子更新成功的实例能创建 execution。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM data_sync_task
            WHERE schedule_enabled = TRUE
              AND current_state = 'SCHEDULED'
              AND next_fire_time IS NOT NULL
              AND next_fire_time &lt;= #{now}
            <if test="tenantId != null">
              AND tenant_id = #{tenantId}
            </if>
            ORDER BY next_fire_time ASC, id ASC
            LIMIT #{limit}
            </script>
            """)
    List<SyncTask> selectDueScheduledTasks(@Param("tenantId") Long tenantId,
                                           @Param("now") LocalDateTime now,
                                           @Param("limit") int limit);

    /**
     * 抢占并推进一个到期定时任务的调度游标。
     *
     * <p>这是定时任务防重复触发的关键 SQL。服务层读取任务时拿到 scheduleVersion=N，
     * 只有仍然满足以下条件的实例才能更新成功：</p>
     * <p>1. 任务仍处于 SCHEDULED 且调度仍启用；</p>
     * <p>2. next_fire_time 仍然到期，说明没有其它实例已经把游标推进；</p>
     * <p>3. schedule_version 仍等于读取时的版本 N，说明没有其它调度器或运营动作改过调度状态。</p>
     *
     * <p>该方法既用于“创建 SCHEDULED execution 前的抢占”，也用于 misfirePolicy=SKIP 这类只推进游标、不创建
     * execution 的场景。lastExecutionId 允许为空，因为真正 execution ID 要在抢占成功后才能创建。</p>
     */
    @Update("""
            <script>
            UPDATE data_sync_task
            SET trigger_type = 'SCHEDULED',
                last_execution_id = COALESCE(#{lastExecutionId}, last_execution_id),
                last_fire_time = COALESCE(#{lastFireTime}, last_fire_time),
                next_fire_time = #{nextFireTime},
                schedule_misfire_count = COALESCE(schedule_misfire_count, 0) + #{misfireIncrement},
                schedule_dispatch_count = COALESCE(schedule_dispatch_count, 0) + #{dispatchIncrement},
                schedule_version = COALESCE(schedule_version, 0) + 1,
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
              AND schedule_enabled = TRUE
              AND current_state = 'SCHEDULED'
              AND next_fire_time IS NOT NULL
              AND next_fire_time &lt;= #{dueCutoff}
              AND COALESCE(schedule_version, 0) = #{expectedScheduleVersion}
            </script>
            """)
    int advanceScheduledTaskAfterDispatch(@Param("taskId") Long taskId,
                                          @Param("expectedScheduleVersion") Long expectedScheduleVersion,
                                          @Param("dueCutoff") LocalDateTime dueCutoff,
                                          @Param("lastFireTime") LocalDateTime lastFireTime,
                                          @Param("nextFireTime") LocalDateTime nextFireTime,
                                          @Param("lastExecutionId") Long lastExecutionId,
                                          @Param("dispatchIncrement") long dispatchIncrement,
                                          @Param("misfireIncrement") long misfireIncrement);

    /**
     * 在 SCHEDULED execution 创建成功后回写最近执行记录 ID。
     *
     * <p>调度服务先用 {@link #advanceScheduledTaskAfterDispatch} 推进游标并抢占任务，再创建 execution。
     * execution ID 只有插入后才能获得，因此需要第二步回写 last_execution_id。
     * 两步都处于同一个事务中：如果 execution 创建失败，前面的游标推进也会回滚。</p>
     */
    @Update("""
            UPDATE data_sync_task
            SET last_execution_id = #{lastExecutionId},
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markScheduledExecutionCreated(@Param("taskId") Long taskId,
                                      @Param("lastExecutionId") Long lastExecutionId);

    /**
     * 将任务标记为重新排队。
     *
     * <p>这个方法通常由 defer 或过期租约恢复触发。
     * 一旦 execution 被安全放回队列，任务就不应再展示为“需要人工介入”，因此这里显式清空 attention_reason。
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = 'QUEUED',
                last_execution_id = #{lastExecutionId},
                attention_required = FALSE,
                attention_reason = NULL,
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markQueuedAfterLeaseTransition(@Param("taskId") Long taskId,
                                       @Param("lastExecutionId") Long lastExecutionId);

    /**
     * 按生命周期控制动作刷新任务主状态。
     *
     * <p>为什么不直接在 Service 里使用 updateById：
     * 1. 暂停、恢复、重试、取消都需要同时更新 current_state、trigger_type、last_execution_id 和人工介入标记；
     * 2. attention_reason 需要被显式置空，而 MyBatis-Plus 的默认实体更新策略通常不会把 null 写入数据库；
     * 3. 生命周期动作属于审计敏感操作，最好通过一个明确的 SQL 入口表达“这是一次控制面状态流转”，而不是散落多个实体字段赋值。
     *
     * <p>参数语义：
     * - targetState：动作完成后的任务主状态，例如 PAUSED、QUEUED、RETRYING、CANCELLED；
     * - triggerType：本次动作是否要刷新触发来源，恢复和重试通常写 MANUAL，暂停和取消可传 null 保留原值；
     * - lastExecutionId：本次动作是否创建或关联了新的 execution，恢复和重试会写新 executionId，暂停和取消可保留原值。
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = #{targetState},
                trigger_type = COALESCE(#{triggerType}, trigger_type),
                last_execution_id = COALESCE(#{lastExecutionId}, last_execution_id),
                attention_required = FALSE,
                attention_reason = NULL,
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markLifecycleState(@Param("taskId") Long taskId,
                           @Param("targetState") String targetState,
                           @Param("triggerType") String triggerType,
                           @Param("lastExecutionId") Long lastExecutionId);

    /**
     * 按管理面动作刷新任务主状态。
     *
     * <p>该方法服务于 offline、recycle、hard-delete、manual-terminate 等“任务定义管理”动作，
     * 和 {@link #markLifecycleState} 的区别是它会显式控制 schedule_enabled、next_fire_time 和人工介入字段：</p>
     * <p>1. 下线、回收站、彻底删除、手工结束都不应继续被 task scheduler 扫描；</p>
     * <p>2. next_fire_time 必须允许写 NULL，否则一个已下线任务仍然携带旧调度游标，后续恢复/克隆时容易误触发；</p>
     * <p>3. attention_required/reason 在这些管理动作完成后应清理，避免回收站或下线列表继续显示“待人工处理”。</p>
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = #{targetState},
                schedule_enabled = #{scheduleEnabled},
                next_fire_time = #{nextFireTime},
                trigger_type = COALESCE(#{triggerType}, trigger_type),
                last_execution_id = COALESCE(#{lastExecutionId}, last_execution_id),
                attention_required = #{attentionRequired},
                attention_reason = #{attentionReason},
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markManagementState(@Param("taskId") Long taskId,
                            @Param("targetState") String targetState,
                            @Param("scheduleEnabled") Boolean scheduleEnabled,
                            @Param("nextFireTime") LocalDateTime nextFireTime,
                            @Param("triggerType") String triggerType,
                            @Param("lastExecutionId") Long lastExecutionId,
                            @Param("attentionRequired") Boolean attentionRequired,
                            @Param("attentionReason") String attentionReason);

    /**
     * 将任务标记为需要人工介入。
     *
     * <p>该状态通常意味着自动执行已经不再安全或不再经济，例如超过最大退避次数、worker 反复失联、
     * 目标端长期限流、连接器版本不兼容或同步配置存在结构性问题。
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = 'AWAITING_OPERATOR_ACTION',
                last_execution_id = #{lastExecutionId},
                attention_required = TRUE,
                attention_reason = #{attentionReason},
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markAwaitingOperatorAction(@Param("taskId") Long taskId,
                                   @Param("lastExecutionId") Long lastExecutionId,
                                   @Param("attentionReason") String attentionReason);

    /**
     * 运营人员确认已经看到人工介入任务。
     *
     * <p>确认动作不代表问题已经解决，因此任务仍然停留在 AWAITING_OPERATOR_ACTION。
     * 它的价值是让团队知道“这个问题已经有人接手”，后续可扩展为 assignee、SLA 计时和通知降噪。
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = 'AWAITING_OPERATOR_ACTION',
                attention_required = TRUE,
                attention_reason = #{attentionReason},
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markAttentionAcknowledged(@Param("taskId") Long taskId,
                                  @Param("attentionReason") String attentionReason);

    /**
     * 运营人员确认问题已处理，任务回到可配置/可运行状态。
     *
     * <p>resolve 不直接创建 execution，而是清理人工介入标记并回到 CONFIGURED。
     * 这样适合“先修配置、再由用户或调度器重新运行”的场景，避免修复动作和执行动作被强绑定。
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = 'CONFIGURED',
                attention_required = FALSE,
                attention_reason = NULL,
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markAttentionResolved(@Param("taskId") Long taskId);

    /**
     * 人工介入后直接重跑任务。
     *
     * <p>该方法会清空人工介入标记，并把最新 executionId 回写到任务主表。
     * 它与普通 runTask 的区别是：普通 runTask 不允许直接从 AWAITING_OPERATOR_ACTION 进入队列，
     * 必须通过运营动作显式确认“已经处理过问题，可以重试”。
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = 'QUEUED',
                trigger_type = 'MANUAL',
                last_execution_id = #{lastExecutionId},
                attention_required = FALSE,
                attention_reason = NULL,
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int markAttentionRerunQueued(@Param("taskId") Long taskId,
                                 @Param("lastExecutionId") Long lastExecutionId);

    /**
     * 人工介入任务关闭到终态。
     *
     * <p>cancel 和 archive 都会结束当前人工介入处理：
     * CANCELLED 表示任务不再继续执行；ARCHIVED 表示任务从日常运营列表中归档。
     */
    @Update("""
            UPDATE data_sync_task
            SET current_state = #{targetState},
                attention_required = FALSE,
                attention_reason = NULL,
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int closeAttentionTask(@Param("taskId") Long taskId,
                           @Param("targetState") String targetState);
}
