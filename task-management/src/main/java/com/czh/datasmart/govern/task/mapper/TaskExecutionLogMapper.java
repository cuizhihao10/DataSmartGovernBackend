package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskExecutionLogMapper.java
 * @Version:1.0.0
 *
 * 任务执行日志表 Mapper。
 * 这张表不是用来展示任务“最终状态”的，而是用来记录任务“是怎么走到这个状态的”。
 * 因此它是任务追踪、审计和问题复盘的重要基础设施。
 */
@Mapper
public interface TaskExecutionLogMapper extends BaseMapper<TaskExecutionLog> {
}
