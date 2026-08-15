/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncAgentExecutionCorrelationMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncAgentExecutionCorrelation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Agent 与同步 execution 关联事实 Mapper。
 *
 * <p>插入使用 PostgreSQL 的唯一约束和 {@code ON CONFLICT DO NOTHING}，所以 Kafka 或 HTTP 重放不会产生
 * 第二条关联记录；查询服务只读，不会因画图而改变任何执行状态。</p>
 */
@Mapper
public interface SyncAgentExecutionCorrelationMapper extends BaseMapper<SyncAgentExecutionCorrelation> {

    /** 按同一 execution 和 audit 身份查找已经存在的关联。 */
    @Select("""
            SELECT *
            FROM data_sync_agent_execution_correlation
            WHERE tenant_id = #{tenantId}
              AND sync_execution_id = #{syncExecutionId}
              AND audit_id = #{auditId}
            LIMIT 1
            """)
    SyncAgentExecutionCorrelation selectByIdentity(@Param("tenantId") Long tenantId,
                                                    @Param("syncExecutionId") Long syncExecutionId,
                                                    @Param("auditId") String auditId);

    /** 查询某次 execution 最近写入的 Agent 关联，恢复重跑仍可沿用根 execution 的 Agent 身份。 */
    @Select("""
            SELECT *
            FROM data_sync_agent_execution_correlation
            WHERE tenant_id = #{tenantId}
              AND sync_task_id = #{syncTaskId}
              AND sync_execution_id = #{syncExecutionId}
            ORDER BY id DESC
            LIMIT 1
            """)
    SyncAgentExecutionCorrelation selectLatestByExecution(@Param("tenantId") Long tenantId,
                                                            @Param("syncTaskId") Long syncTaskId,
                                                            @Param("syncExecutionId") Long syncExecutionId);

    /** 幂等写入跨域关联；冲突时保留第一条已审计身份。 */
    @Insert("""
            INSERT INTO data_sync_agent_execution_correlation
                (tenant_id, project_id, sync_task_id, sync_execution_id, command_id, entry_mode,
                 session_id, run_id, audit_id, trace_id, create_time, update_time)
            VALUES
                (#{record.tenantId}, #{record.projectId}, #{record.syncTaskId}, #{record.syncExecutionId}, #{record.commandId}, #{record.entryMode},
                 #{record.sessionId}, #{record.runId}, #{record.auditId}, #{record.traceId}, LOCALTIMESTAMP, LOCALTIMESTAMP)
            ON CONFLICT (tenant_id, sync_execution_id, audit_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("record") SyncAgentExecutionCorrelation record);
}
