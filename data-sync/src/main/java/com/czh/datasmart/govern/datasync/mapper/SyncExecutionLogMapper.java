/**
 * @Author : Cui
 * @Date: 2026/07/09 18:48
 * @Description DataSmart Govern Backend - SyncExecutionLogMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据同步执行日志 Mapper。
 *
 * <p>普通分页查询可以直接使用 MyBatis-Plus 的 {@link BaseMapper#selectPage}。
 * 本 Mapper 目前不额外声明复杂 SQL，是为了让执行日志保持“事实写入 + 简单查询”的稳定边界；
 * 后续如果要做运行日志聚合、按阶段统计耗时、按执行器统计吞吐，再在这里补只读查询即可。</p>
 */
@Mapper
public interface SyncExecutionLogMapper extends BaseMapper<SyncExecutionLog> {
}
