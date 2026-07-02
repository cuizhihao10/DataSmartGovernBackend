/**
 * @Author : Cui
 * @Date: 2026/05/08 22:23
 * @Description DataSmart Govern Backend - SyncIncidentRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 同步事故记录 Mapper。
 *
 * <p>当前先提供 MyBatis-Plus 基础 CRUD。事故记录后续如果接入查询、派单、状态流转和 SLA 统计，
 * 可以在这里继续补充显式 SQL，保持事故领域逻辑和任务/执行器逻辑解耦。
 */
@Mapper
public interface SyncIncidentRecordMapper extends BaseMapper<SyncIncidentRecord> {

    /**
     * 删除超过保留期的已关闭事故。
     *
     * <p>事故记录和普通日志不同，OPEN、ACKNOWLEDGED、RESOLVED 都还可能需要运营继续跟进。
     * 因此清理条件必须限定为 `incident_status = 'CLOSED'`，并且按 closed_at 判断保留期。
     * 这样可以避免后台清理任务误删尚未闭环的问题单。
     *
     * @param expireBefore 关闭时间早于该值的 CLOSED 事故可以清理
     * @param limit 单轮删除上限
     * @return 本轮删除数量
     */
    @Delete("""
            WITH expired AS (
                SELECT id
                FROM data_sync_incident_record
                WHERE incident_status = 'CLOSED'
                  AND closed_at IS NOT NULL
                  AND closed_at < #{expireBefore}
                ORDER BY closed_at ASC, id ASC
                LIMIT #{limit}
            )
            DELETE FROM data_sync_incident_record
            WHERE id IN (SELECT id FROM expired)
            """)
    int deleteExpiredClosedIncidents(@Param("expireBefore") LocalDateTime expireBefore, @Param("limit") int limit);

    /**
     * 确认事故已被接手。
     *
     * <p>只允许 OPEN -> ACKNOWLEDGED，避免已经解决或关闭的事故被重新确认造成状态倒退。
     */
    @Update("""
            UPDATE data_sync_incident_record
            SET incident_status = 'ACKNOWLEDGED',
                acknowledged_at = LOCALTIMESTAMP,
                update_time = LOCALTIMESTAMP
            WHERE id = #{incidentId}
              AND incident_status = 'OPEN'
            """)
    int acknowledgeIncident(@Param("incidentId") Long incidentId);

    /**
     * 分派事故负责人。
     *
     * <p>只要事故未关闭，都允许重新分派；这样可以支持值班交接、升级处理和跨团队转派。
     */
    @Update("""
            UPDATE data_sync_incident_record
            SET assigned_operator_id = #{assignedOperatorId},
                assigned_operator_role = #{assignedOperatorRole},
                update_time = LOCALTIMESTAMP
            WHERE id = #{incidentId}
              AND incident_status <> 'CLOSED'
            """)
    int assignIncident(@Param("incidentId") Long incidentId,
                       @Param("assignedOperatorId") Long assignedOperatorId,
                       @Param("assignedOperatorRole") String assignedOperatorRole);

    /**
     * 标记事故已解决。
     *
     * <p>RESOLVED 表示技术或运营问题已经处理完成，但还没有最终关闭；很多企业会保留关闭前复核步骤。
     */
    @Update("""
            UPDATE data_sync_incident_record
            SET incident_status = 'RESOLVED',
                resolution_summary = #{resolutionSummary},
                resolved_at = LOCALTIMESTAMP,
                update_time = LOCALTIMESTAMP
            WHERE id = #{incidentId}
              AND incident_status <> 'CLOSED'
            """)
    int resolveIncident(@Param("incidentId") Long incidentId,
                        @Param("resolutionSummary") String resolutionSummary);

    /**
     * 关闭事故。
     *
     * <p>CLOSED 是事故生命周期终态，用于运营报表、SLA 统计和历史归档。
     */
    @Update("""
            UPDATE data_sync_incident_record
            SET incident_status = 'CLOSED',
                resolution_summary = COALESCE(#{resolutionSummary}, resolution_summary),
                closed_at = LOCALTIMESTAMP,
                update_time = LOCALTIMESTAMP
            WHERE id = #{incidentId}
              AND incident_status <> 'CLOSED'
            """)
    int closeIncident(@Param("incidentId") Long incidentId,
                      @Param("resolutionSummary") String resolutionSummary);
}
