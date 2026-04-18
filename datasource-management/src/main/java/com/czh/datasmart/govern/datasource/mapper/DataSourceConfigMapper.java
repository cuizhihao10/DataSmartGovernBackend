package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceConfigMapper.java
 * @Version:1.0.0
 *
 * 数据源配置 Mapper。
 * 当前阶段基础 CRUD 已足够支撑模块需求，后续如有复杂筛选、统计和联表分析，再在这里扩展自定义查询。
 */
@Mapper
public interface DataSourceConfigMapper extends BaseMapper<DataSourceConfig> {
}
