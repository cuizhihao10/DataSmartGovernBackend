/**
 * @Author : Cui
 * @Date: 2026/05/07 21:29
 * @Description DataSmart Govern Backend - SyncTemplateMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步模板 Mapper。
 *
 * <p>当前阶段继承 MyBatis-Plus BaseMapper 即可完成基础 CRUD。
 * 后续如果需要复杂查询，例如按连接器类型统计模板数量、按租户导出配置、按字段映射搜索模板，
 * 再在这里增加显式 SQL，避免过早引入复杂 Mapper XML。
 */
@Mapper
public interface SyncTemplateMapper extends BaseMapper<SyncTemplate> {
}
