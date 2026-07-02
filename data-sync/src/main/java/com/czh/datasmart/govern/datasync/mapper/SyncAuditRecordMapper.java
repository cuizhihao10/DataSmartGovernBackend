/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncAuditRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 数据同步审计 Mapper。
 *
 * <p>审计记录通常比错误样本保留更久，因为它承担合规追溯责任。
 * 但审计表也不能无限增长，因此保留期必须配置化，并通过小批量删除降低维护风险。
 */
@Mapper
public interface SyncAuditRecordMapper extends BaseMapper<SyncAuditRecord> {

    /**
     * 删除超过保留期的历史审计记录。
     *
     * <p>注意：这里不区分动作类型统一清理，是当前阶段的基础策略。
     * 未来如果客户要求“导出/回放/删除类动作保留更久”，可以扩展为按 action_type 分层保留。
     *
     * @param expireBefore 创建时间早于该值的审计记录可以清理
     * @param limit 单轮删除上限
     * @return 本轮删除数量
     */
    @Delete("""
            WITH expired AS (
                SELECT id
                FROM data_sync_audit_record
                WHERE create_time < #{expireBefore}
                ORDER BY create_time ASC, id ASC
                LIMIT #{limit}
            )
            DELETE FROM data_sync_audit_record
            WHERE id IN (SELECT id FROM expired)
            """)
    int deleteExpiredAuditRecords(@Param("expireBefore") LocalDateTime expireBefore, @Param("limit") int limit);
}
