/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.DataSourceAuthorization;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据源授权 Mapper。
 *
 * <p>当前授权表的查询主要是按 datasourceId 查看授权清单、按 subject 匹配当前用户是否具备实例级访问权。
 * BaseMapper 已能覆盖增删改查；后续如果要做复杂审计报表、授权继承链路或批量导入冲突检测，再补 XML Mapper 更合适。</p>
 */
@Mapper
public interface DataSourceAuthorizationMapper extends BaseMapper<DataSourceAuthorization> {
}
