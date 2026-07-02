package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskMapper.java
 * @Version:1.0.0
 *
 * 任务主表 Mapper。
 * 当前继承 BaseMapper 即可满足模块现阶段的大部分读写需求。
 * 这样做的好处是：
 * 1. 基础增删改查不需要重复写样板 SQL。
 * 2. Service 层仍然可以围绕 Mapper 构建明确业务规则。
 * 3. 后续如果出现复杂检索、统计报表，再在这个接口上扩展自定义方法即可。
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    /**
     * 查询下一条可认领任务。
     *
     * <p>当前把 PENDING 和“已到期的 DEFERRED”视作轻量队列，按优先级和入队时间取最早的一条：
     * 1. HIGH 优先于 MEDIUM，MEDIUM 优先于 LOW；
     * 2. 同一优先级内按 queued_time 先进先出，queued_time 为空时回退 create_time；
     * 3. taskType 可选，便于不同执行器只领取自己支持的任务类型。
     * 4. DEFERRED 任务只有 queued_time 到期后才会重新进入认领范围，避免资源不足时被立即反复认领。
     *
     * <p>PostgreSQL 迁移说明：
     * MySQL 的 FIELD(priority, ...) 在 PostgreSQL 中不可用，因此这里使用 CASE WHEN 显式表达优先级顺序。
     * 这种写法虽然比 FIELD 长一点，但它是标准 SQL 思路，优先级含义也更容易被学习和排障。</p>
     *
     * <p>这不是最终高吞吐队列方案，但能支撑第一版“执行器认领 + 租约 + 心跳”闭环。
     */
    @Select("""
            <script>
            SELECT *
            FROM task
            WHERE status IN ('PENDING', 'DEFERRED')
              AND (queued_time IS NULL OR queued_time &lt;= LOCALTIMESTAMP)
            <if test="taskType != null and taskType != ''">
              AND type = #{taskType}
            </if>
            <if test="tenantId != null">
              AND tenant_id = #{tenantId}
            </if>
            <if test="ownerId != null">
              AND owner_id = #{ownerId}
            </if>
            <if test="projectId != null">
              AND project_id = #{projectId}
            </if>
            ORDER BY CASE priority
                       WHEN 'HIGH' THEN 1
                       WHEN 'MEDIUM' THEN 2
                       WHEN 'LOW' THEN 3
                       ELSE 4
                     END,
                     COALESCE(queued_time, create_time) ASC,
                     create_time ASC
            LIMIT 1
            </script>
            """)
    Task selectNextClaimCandidate(@Param("taskType") String taskType,
                                  @Param("tenantId") Long tenantId,
                                  @Param("ownerId") Long ownerId,
                                  @Param("projectId") Long projectId);

    /**
     * 执行器声明任务租约。
     *
     * <p>WHERE status IN ('PENDING','DEFERRED') 是并发安全的关键：
     * 多个执行器同时看到同一候选任务时，只有第一个成功把状态改成 RUNNING 的执行器真正认领成功。
     * 同时再次判断 queued_time 到期，避免候选查询和条件更新之间任务被管理员或其他流程改成未来延迟时间。
     *
     * <p>PostgreSQL 迁移说明：
     * 租约过期时间使用 {@code LOCALTIMESTAMP + (秒数 * INTERVAL '1 second')} 表达。
     * 这等价于 MySQL DATE_ADD(NOW(), INTERVAL n SECOND)，但不会把 SQL 继续绑定在 MySQL 专属函数上。</p>
     */
    @Update("""
            UPDATE task
            SET status = 'RUNNING',
                current_executor_id = #{executorId},
                lease_expire_time = LOCALTIMESTAMP + (#{leaseSeconds} * INTERVAL '1 second'),
                heartbeat_time = LOCALTIMESTAMP,
                start_time = COALESCE(start_time, LOCALTIMESTAMP),
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
              AND status IN ('PENDING', 'DEFERRED')
              AND (queued_time IS NULL OR queued_time <= LOCALTIMESTAMP)
            """)
    int claimTask(@Param("taskId") Long taskId,
                  @Param("executorId") String executorId,
                  @Param("leaseSeconds") long leaseSeconds);

    /**
     * 执行器心跳续租。
     *
     * <p>只有当前持有租约的 executorId 才能续租，避免其他执行器误更新任务进度。
     * 续租 SQL 同样使用 PostgreSQL interval 表达式，保持和认领逻辑一致。</p>
     */
    @Update("""
            UPDATE task
            SET progress = #{progress},
                checkpoint = #{checkpoint},
                heartbeat_time = LOCALTIMESTAMP,
                lease_expire_time = LOCALTIMESTAMP + (#{leaseSeconds} * INTERVAL '1 second'),
                update_time = LOCALTIMESTAMP
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND current_executor_id = #{executorId}
            """)
    int heartbeatLease(@Param("taskId") Long taskId,
                       @Param("executorId") String executorId,
                       @Param("progress") Integer progress,
                       @Param("checkpoint") String checkpoint,
                       @Param("leaseSeconds") long leaseSeconds);
}
