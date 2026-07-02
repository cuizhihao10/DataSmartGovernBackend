/**
 * @Author : Cui
 * @Date: 2026/05/25 00:00
 * @Description DataSmart Govern Backend - TaskDraftMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.TaskDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 任务草稿 Mapper。
 *
 * <p>草稿第一版以 MyBatis-Plus BaseMapper 为主，避免过早写复杂 SQL。
 * 当后续审批工作台需要“按状态分组、按来源工具筛选、按审批超时排序”时，再在这里补自定义查询。</p>
 */
@Mapper
public interface TaskDraftMapper extends BaseMapper<TaskDraft> {

    /**
     * 抢占草稿转换权。
     *
     * <p>这是草稿转换幂等/并发保护的第一道闸门。
     * 只有当前状态仍是 APPROVED 的草稿，才能被原子更新为 CONVERTING。
     * 如果多个请求同时转换同一草稿，数据库会保证只有一个请求更新成功；失败的请求不能再创建真实任务。</p>
     *
     * @param draftId 草稿 ID。
     * @param expectedStatus 期望前置状态，当前应为 APPROVED。
     * @param nextStatus 抢占后的中间状态，当前应为 CONVERTING。
     * @return 受影响行数，1 表示抢占成功，0 表示状态已被其他请求改变。
     *
     * <p>PostgreSQL 迁移说明：
     * 使用 LOCALTIMESTAMP 刷新 update_time，和 V1 DDL 中的 TIMESTAMP WITHOUT TIME ZONE 对齐。</p>
     */
    @Update("""
            UPDATE task_draft
            SET status = #{nextStatus},
                update_time = LOCALTIMESTAMP
            WHERE id = #{draftId}
              AND status = #{expectedStatus}
            """)
    int markConverting(@Param("draftId") Long draftId,
                       @Param("expectedStatus") String expectedStatus,
                       @Param("nextStatus") String nextStatus);

    /**
     * 标记草稿转换完成。
     *
     * <p>该更新只允许 CONVERTING -> CONVERTED。
     * convertedTaskId 是幂等读取的关键：后续重复转换请求如果发现草稿已经 CONVERTED，
     * 可以直接返回这条真实任务，而不是再次创建任务。</p>
     */
    @Update("""
            UPDATE task_draft
            SET status = #{convertedStatus},
                converted_task_id = #{taskId},
                convert_time = LOCALTIMESTAMP,
                update_time = LOCALTIMESTAMP
            WHERE id = #{draftId}
              AND status = #{convertingStatus}
            """)
    int markConverted(@Param("draftId") Long draftId,
                      @Param("taskId") Long taskId,
                      @Param("convertingStatus") String convertingStatus,
                      @Param("convertedStatus") String convertedStatus);
}
