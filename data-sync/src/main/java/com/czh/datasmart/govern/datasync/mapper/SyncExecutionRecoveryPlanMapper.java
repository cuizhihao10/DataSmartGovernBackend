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

/**
 * 同步执行恢复计划 Mapper。
 *
 * <p>当前只需要 MyBatis-Plus 的基础 insert/select 能力。
 * 后续真实 worker SDK 接入时，可以再补按 executionId 领取计划、计划消费确认、过期计划清理等专用 SQL。
 */
@Mapper
public interface SyncExecutionRecoveryPlanMapper extends BaseMapper<SyncExecutionRecoveryPlan> {
}
