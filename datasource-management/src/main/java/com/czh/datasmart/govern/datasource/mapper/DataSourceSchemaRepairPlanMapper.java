package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.DataSourceSchemaRepairPlan;
import org.apache.ibatis.annotations.Mapper;

/** Persists low-sensitive schema repair plans and their terminal state. */
@Mapper
public interface DataSourceSchemaRepairPlanMapper extends BaseMapper<DataSourceSchemaRepairPlan> {
}
