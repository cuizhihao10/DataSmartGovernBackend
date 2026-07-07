/**
 * @Author : Cui
 * @Date: 2026/05/07 21:29
 * @Description DataSmart Govern Backend - SyncTaskMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
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
     * 聚合同步任务分组汇总。
     *
     * <p>该 SQL 直接从任务主表聚合，不引入额外分组表。这样当前版本可以快速支撑任务分组卡片、
     * Agent “按业务域找任务” 和导入导出前的分组预览。查询层面仍然接收租户、项目、工作空间、SELF owner
     * 和 authorizedProjectIds，避免分组列表绕过数据范围。</p>
     *
     * <p>为什么只统计 group_code 非空的任务：
     * 未分组任务通常在普通任务列表中查看；分组列表关注“已经被用户或 Agent 归入某个业务集合”的任务。
     * 后续如果前端需要“未分组”卡片，可以增加一个显式 includeUngrouped 参数，而不是把 null 编码伪装成真实分组。</p>
     */
    @Select("""
            <script>
            SELECT
                tenant_id AS tenantId,
                project_id AS projectId,
                workspace_id AS workspaceId,
                COALESCE(NULLIF(group_code, ''), 'DEFAULT') AS groupCode,
                COALESCE(MAX(NULLIF(group_name, '')), '默认分组') AS groupName,
                COUNT(*) AS taskCount,
                SUM(CASE WHEN current_state NOT IN ('OFFLINE', 'RECYCLED', 'DELETED', 'ARCHIVED') THEN 1 ELSE 0 END) AS activeTaskCount,
                SUM(CASE WHEN current_state = 'SCHEDULED' THEN 1 ELSE 0 END) AS scheduledTaskCount,
                SUM(CASE WHEN current_state IN ('QUEUED', 'RUNNING', 'RETRYING') THEN 1 ELSE 0 END) AS runningTaskCount,
                SUM(CASE WHEN current_state IN ('FAILED', 'PARTIALLY_SUCCEEDED', 'AWAITING_OPERATOR_ACTION') THEN 1 ELSE 0 END) AS failedTaskCount,
                SUM(CASE WHEN current_state = 'RECYCLED' THEN 1 ELSE 0 END) AS recycledTaskCount,
                MAX(update_time) AS lastUpdateTime
            FROM data_sync_task
            WHERE current_state &lt;&gt; 'DELETED'
            <if test="tenantId != null">
              AND tenant_id = #{tenantId}
            </if>
            <if test="projectId != null">
              AND project_id = #{projectId}
            </if>
            <if test="workspaceId != null">
              AND workspace_id = #{workspaceId}
            </if>
            <if test="projectScopeEnforced and projectId == null">
              AND project_id IN
              <foreach collection="authorizedProjectIds" item="projectIdItem" open="(" separator="," close=")">
                #{projectIdItem}
              </foreach>
            </if>
            <if test="ownerId != null">
              AND owner_id = #{ownerId}
            </if>
            <if test="groupCode != null and groupCode != '' and groupCode != 'DEFAULT'">
              AND group_code = #{groupCode}
            </if>
            <if test="groupCode != null and groupCode == 'DEFAULT'">
              AND (group_code = 'DEFAULT' OR group_code IS NULL OR group_code = '')
            </if>
            GROUP BY tenant_id, project_id, workspace_id, COALESCE(NULLIF(group_code, ''), 'DEFAULT')
            ORDER BY MAX(update_time) DESC, COALESCE(NULLIF(group_code, ''), 'DEFAULT') ASC
            LIMIT #{limit}
            </script>
            """)
    List<SyncTaskGroupSummary> selectTaskGroupSummaries(@Param("tenantId") Long tenantId,
                                                        @Param("projectId") Long projectId,
                                                        @Param("workspaceId") Long workspaceId,
                                                        @Param("projectScopeEnforced") boolean projectScopeEnforced,
                                                        @Param("authorizedProjectIds") List<Long> authorizedProjectIds,
                                                        @Param("ownerId") Long ownerId,
                                                        @Param("groupCode") String groupCode,
                                                        @Param("limit") int limit);

    /**
     * 更新任务分组字段。
     *
     * <p>这里显式使用 SQL 而不是 updateById，是因为“移出分组”需要把 group_code/group_name 写成 NULL。
     * MyBatis-Plus 默认更新策略可能跳过 null 字段，导致用户认为已经移出分组，但数据库仍保留旧 groupCode。</p>
     */
    @Update("""
            UPDATE data_sync_task
            SET group_code = #{groupCode},
                group_name = #{groupName},
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int updateTaskGroup(@Param("taskId") Long taskId,
                        @Param("groupCode") String groupCode,
                        @Param("groupName") String groupName);

    /**
     * 删除分组时把任务批量迁回默认分组。
     *
     * <p>这是“删除分组不删除任务”的核心保护 SQL。分组只是运营组织视图，任务本身可能仍在等待调度、运行、失败待重试或已进入回收站；
     * 因此删除分组不能级联删除任务，也不能把任务 groupCode 置空导致前端不可见，而是统一回收到默认分组。</p>
     */
    @Update("""
            <script>
            UPDATE data_sync_task
            SET group_code = #{defaultGroupCode},
                group_name = #{defaultGroupName},
                update_time = LOCALTIMESTAMP
            WHERE tenant_id = #{tenantId}
              AND current_state &lt;&gt; 'DELETED'
              AND group_code IN
              <foreach collection="sourceGroupCodes" item="groupCode" open="(" separator="," close=")">
                #{groupCode}
              </foreach>
            <choose>
              <when test="projectId == null">
                AND project_id IS NULL
              </when>
              <otherwise>
                AND project_id = #{projectId}
              </otherwise>
            </choose>
            <choose>
              <when test="workspaceId == null">
                AND workspace_id IS NULL
              </when>
              <otherwise>
                AND workspace_id = #{workspaceId}
              </otherwise>
            </choose>
            </script>
            """)
    int reassignGroupsToDefault(@Param("tenantId") Long tenantId,
                                @Param("projectId") Long projectId,
                                @Param("workspaceId") Long workspaceId,
                                @Param("sourceGroupCodes") List<String> sourceGroupCodes,
                                @Param("defaultGroupCode") String defaultGroupCode,
                                @Param("defaultGroupName") String defaultGroupName);

    /**
     * 更新任务定义字段。
     *
     * <p>编辑/发布任务时不能简单调用 {@code updateById}，主要有三个原因：</p>
     * <p>1. 用户可能显式清空调度配置、分组或人工介入原因，需要把字段写成 NULL；</p>
     * <p>2. 调度字段是一组一致性字段，schedule_enabled=false 时 next_fire_time 必须同步清空；</p>
     * <p>3. 编辑调度配置后任务会退回 DRAFT，必须和 schedule_enabled、trigger_type、attention 标记在一个 SQL 里落地。</p>
     *
     * <p>该方法看起来参数较多，但它把“任务定义的一致性写入”集中在一个受控 SQL 入口里，
     * 比在多个 service 方法中散落实体 set + updateById 更可审计，也更方便后续加乐观锁或版本号。</p>
     */
    @Update("""
            UPDATE data_sync_task
            SET name = #{name},
                description = #{description},
                priority = #{priority},
                owner_id = #{ownerId},
                group_code = #{groupCode},
                group_name = #{groupName},
                schedule_config = #{scheduleConfig},
                schedule_enabled = #{scheduleEnabled},
                next_fire_time = #{nextFireTime},
                run_mode = #{runMode},
                trigger_type = #{triggerType},
                current_state = #{currentState},
                approval_state = #{approvalState},
                attention_required = #{attentionRequired},
                attention_reason = #{attentionReason},
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
            """)
    int updateTaskDefinition(@Param("taskId") Long taskId,
                             @Param("name") String name,
                             @Param("description") String description,
                             @Param("priority") String priority,
                             @Param("ownerId") Long ownerId,
                             @Param("groupCode") String groupCode,
                             @Param("groupName") String groupName,
                             @Param("scheduleConfig") String scheduleConfig,
                             @Param("scheduleEnabled") Boolean scheduleEnabled,
                             @Param("nextFireTime") LocalDateTime nextFireTime,
                             @Param("runMode") String runMode,
                             @Param("triggerType") String triggerType,
                             @Param("currentState") String currentState,
                             @Param("approvalState") String approvalState,
                             @Param("attentionRequired") Boolean attentionRequired,
                             @Param("attentionReason") String attentionReason);

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
