package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncExecutionMapper.java
 * @Version:1.0.0
 *
 * 同步执行记录 Mapper。
 */
@Mapper
public interface SyncExecutionMapper extends BaseMapper<SyncExecution> {
}
