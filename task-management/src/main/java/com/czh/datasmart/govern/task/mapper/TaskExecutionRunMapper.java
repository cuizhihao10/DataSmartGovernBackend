package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:05
 * @Description DataSmart Govern Backend - TaskExecutionRunMapper.java
 * @Version:1.0.0
 *
 * 任务执行记录 Mapper。
 *
 * <p>除了基础 CRUD，这里还提供“查询最大 runNo”“扫描超时执行”等执行调度相关 SQL。
 * 这些 SQL 属于任务执行基础设施，不适合散落在 Service 里手写。
 */
@Mapper
public interface TaskExecutionRunMapper extends BaseMapper<TaskExecutionRun> {

    /**
     * 查询某个任务当前最大执行序号。
     */
    @Select("""
            SELECT COALESCE(MAX(run_no), 0)
            FROM task_execution_run
            WHERE task_id = #{taskId}
            """)
    Long selectMaxRunNo(@Param("taskId") Long taskId);

    /**
     * 扫描已超时的运行中执行记录。
     *
     * <p>只扫描 RUNNING 且 lease_expire_time 早于当前时间的记录。
     * limit 用于避免一次恢复过多任务影响数据库和服务稳定性。
     *
     * <p>PostgreSQL 迁移说明：
     * 使用 {@code LOCALTIMESTAMP} 与 Java {@code LocalDateTime} / PostgreSQL {@code TIMESTAMP WITHOUT TIME ZONE}
     * 保持一致，避免数据库 session 时区把租约判断隐式转换成另一个时区。</p>
     */
    @Select("""
            SELECT *
            FROM task_execution_run
            WHERE state = 'RUNNING'
              AND lease_expire_time IS NOT NULL
              AND lease_expire_time < LOCALTIMESTAMP
            ORDER BY lease_expire_time ASC
            LIMIT #{limit}
            """)
    List<TaskExecutionRun> selectTimedOutRuns(@Param("limit") int limit);

    /**
     * 标记执行记录结束。
     */
    @Update("""
            UPDATE task_execution_run
            SET state = #{state},
                finished_at = LOCALTIMESTAMP,
                error_message = #{errorMessage},
                update_time = LOCALTIMESTAMP
            WHERE id = #{runId}
              AND state = 'RUNNING'
            """)
    int finishRunningRun(@Param("runId") Long runId,
                         @Param("state") String state,
                         @Param("errorMessage") String errorMessage);
}
