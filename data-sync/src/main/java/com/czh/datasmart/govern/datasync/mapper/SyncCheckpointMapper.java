/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncCheckpointMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 同步 checkpoint Mapper。
 *
 * <p>checkpoint 是同步恢复的关键状态，不是普通日志。清理时必须按时间和批次控制，
 * 避免一次性删除大量历史 checkpoint 造成数据库长事务或影响正在运行任务读取最新进度。
 */
@Mapper
public interface SyncCheckpointMapper extends BaseMapper<SyncCheckpoint> {

    /**
     * 删除超过保留期的历史 checkpoint。
     *
     * <p>使用 `checkpoint_time` 而不是 `create_time` 作为保留期依据，是因为 checkpoint 的业务含义是
     * “同步进度产生时间”。如果未来存在补录或迁移数据，create_time 可能晚于真实 checkpoint 时间。
     *
     * @param expireBefore checkpoint 业务时间早于该值的记录可以清理
     * @param limit 单轮删除上限，用于控制数据库压力
     * @return 本轮删除数量
     */
    @Delete("""
            WITH expired AS (
                SELECT id
                FROM data_sync_checkpoint
                WHERE checkpoint_time < #{expireBefore}
                ORDER BY checkpoint_time ASC, id ASC
                LIMIT #{limit}
            )
            DELETE FROM data_sync_checkpoint
            WHERE id IN (SELECT id FROM expired)
            """)
    int deleteExpiredCheckpoints(@Param("expireBefore") LocalDateTime expireBefore, @Param("limit") int limit);
}
