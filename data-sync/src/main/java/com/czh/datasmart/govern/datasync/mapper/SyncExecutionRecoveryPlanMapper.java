/**
 * @Author : Cui
 * @Date: 2026/06/27 02:28
 * @Description DataSmart Govern Backend - SyncExecutionRecoveryPlanMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 同步执行恢复计划 Mapper。
 *
 * <p>当前只需要 MyBatis-Plus 的基础 insert/select 能力。
 * 后续真实 worker SDK 接入时，可以再补按 executionId 领取计划、计划消费确认、过期计划清理等专用 SQL。
 */
@Mapper
public interface SyncExecutionRecoveryPlanMapper extends BaseMapper<SyncExecutionRecoveryPlan> {

    /**
     * 按 executionId 查询恢复计划。
     *
     * <p>replay/backfill 计划与新创建的 execution 是一对一关系，worker 认领 execution 后只需要使用 executionId
     * 就可以读取恢复契约。这里不按 taskId 查询，是为了避免同一任务多次 replay/backfill 时 worker 误拿到其它执行批次的计划。
     */
    @Select("""
            SELECT *
            FROM data_sync_execution_recovery_plan
            WHERE execution_id = #{executionId}
            LIMIT 1
            """)
    SyncExecutionRecoveryPlan selectByExecutionId(@Param("executionId") Long executionId);

    /**
     * 原子推进恢复计划状态。
     *
     * <p>状态更新必须带 expectedState 条件，不能直接 updateById 覆盖 planState。原因是 worker 可能因为网络重试、
     * 多实例并发、进程重启恢复等情况重复调用 claim/consume；如果没有 expectedState 条件，后到请求可能把较新的状态覆盖回旧状态。
     *
     * <p>返回值语义：
     * 1. 返回 1：当前请求成功完成状态推进；
     * 2. 返回 0：状态已经被其它请求推进，服务层需要重新读取并判断是否可按幂等成功处理。
     */
    @Update("""
            UPDATE data_sync_execution_recovery_plan
            SET plan_state = #{targetState},
                update_time = LOCALTIMESTAMP
            WHERE execution_id = #{executionId}
              AND plan_state = #{expectedState}
            """)
    int markPlanState(@Param("executionId") Long executionId,
                      @Param("expectedState") String expectedState,
                      @Param("targetState") String targetState);
}
