/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * DataSync worker 命令 outbox Mapper。
 *
 * <p>真正的并发幂等由数据库唯一键承担：commandId、idempotencyKey 和 outboxId 都不能重复。
 * Mapper 保持薄层，只负责 MyBatis-Plus CRUD，业务规则放在 Service 中，避免 SQL 层和业务层互相泄漏。</p>
 */
@Mapper
public interface DataSyncWorkerCommandOutboxMapper extends BaseMapper<DataSyncWorkerCommandOutbox> {
}
