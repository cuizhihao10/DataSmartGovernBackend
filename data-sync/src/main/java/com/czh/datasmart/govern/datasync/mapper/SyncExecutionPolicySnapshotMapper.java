/**
 * @Author : Cui
 * @Date: 2026/07/09 22:33
 * @Description DataSmart Govern Backend - SyncExecutionPolicySnapshotMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicySnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 执行策略快照 Mapper。
 *
 * <p>快照查询通常按 taskId + executionId 精确定位，因此基础 BaseMapper 已足够。
 * 如果后续运营台需要统计“哪些任务长期使用高 channel”或“哪些连接器策略导致大量超时”，再补充聚合 SQL。</p>
 */
@Mapper
public interface SyncExecutionPolicySnapshotMapper extends BaseMapper<SyncExecutionPolicySnapshot> {
}
