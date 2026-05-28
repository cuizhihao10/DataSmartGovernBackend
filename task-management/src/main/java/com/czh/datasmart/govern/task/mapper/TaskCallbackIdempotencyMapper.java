package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.TaskCallbackIdempotency;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/05/07 21:12
 * @Description DataSmart Govern Backend - TaskCallbackIdempotencyMapper.java
 * @Version:1.0.0
 *
 * 任务执行器回调幂等 Mapper。
 *
 * <p>该 Mapper 目前只需要 MyBatis-Plus 的基础 CRUD 能力。
 * 幂等能力的关键不在复杂 SQL，而在数据库表上的唯一索引：
 * task_id + action + idempotency_key。
 * 应用层会先尝试插入一条 PROCESSING 记录，插入成功代表“我是第一个处理该回调的请求”，
 * 插入触发唯一键冲突则代表“该回调已经被处理或正在处理”，从而可以安全地按重复请求返回成功。</p>
 */
@Mapper
public interface TaskCallbackIdempotencyMapper extends BaseMapper<TaskCallbackIdempotency> {
}
