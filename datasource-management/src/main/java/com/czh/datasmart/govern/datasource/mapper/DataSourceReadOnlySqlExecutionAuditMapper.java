package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/04/28 21:18
 * @Description DataSmart Govern Backend - DataSourceReadOnlySqlExecutionAuditMapper.java
 * @Version:1.0.0
 *
 * 受控只读 SQL 执行审计 Mapper。
 *
 * Mapper 层保持轻量，只负责把审计实体写入数据库。
 * 审计记录的业务语义、字段截断、SQL 指纹和失败保护都在服务层完成，
 * 这样可以让持久化层专注于数据库访问，不混入业务决策。
 */
@Mapper
public interface DataSourceReadOnlySqlExecutionAuditMapper
        extends BaseMapper<DataSourceReadOnlySqlExecutionAudit> {
}
