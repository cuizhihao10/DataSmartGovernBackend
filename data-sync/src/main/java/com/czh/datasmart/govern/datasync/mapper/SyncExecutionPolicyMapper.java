/**
 * @Author : Cui
 * @Date: 2026/07/09 22:32
 * @Description DataSmart Govern Backend - SyncExecutionPolicyMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 执行策略 Mapper。
 *
 * <p>当前策略查询主要依赖 MyBatis-Plus 条件构造器，后续如果要做“策略命中分析”“批量导入导出”
 * 或“按连接器统计策略覆盖率”，可以在这里补充显式 SQL。</p>
 */
@Mapper
public interface SyncExecutionPolicyMapper extends BaseMapper<SyncExecutionPolicy> {
}
