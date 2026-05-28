/**
 * @Author : Cui
 * @Date: 2026/05/08 23:03
 * @Description DataSmart Govern Backend - SyncCallbackIdempotencyMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncCallbackIdempotency;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * data-sync 回调幂等 Mapper。
 *
 * <p>幂等能力的核心由数据库唯一索引承担：
 * `tenant_id + action + scope_key + idempotency_key`。
 * Mapper 只需要提供基础 CRUD，唯一键冲突由支撑组件捕获后转化为“重复请求”。
 */
@Mapper
public interface SyncCallbackIdempotencyMapper extends BaseMapper<SyncCallbackIdempotency> {

    /**
     * 按保留期清理历史幂等记录。
     *
     * <p>为什么这里不用 MyBatis-Plus 的 remove(wrapper)：
     * 1. 幂等表在生产环境会持续写入，如果一次删除无限制数据，容易造成长事务、锁等待和 binlog 峰值；
     * 2. MySQL 支持 `DELETE ... ORDER BY ... LIMIT ...`，可以按时间顺序小批量清理；
     * 3. 调度器周期性调用本方法，比一次性清空更适合商用系统的温和后台维护。
     *
     * @param expireBefore 过期边界时间，last_seen_time 早于该时间的记录视为可清理
     * @param limit 单次最多删除数量，由配置类做安全边界裁剪
     * @return 本次实际删除的记录数
     */
    @Delete("""
            DELETE FROM data_sync_callback_idempotency
            WHERE last_seen_time < #{expireBefore}
            ORDER BY last_seen_time ASC
            LIMIT #{limit}
            """)
    int deleteExpiredRecords(@Param("expireBefore") LocalDateTime expireBefore, @Param("limit") int limit);
}
