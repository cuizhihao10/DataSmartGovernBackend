/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityRuleMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质量规则 Mapper。
 * 当前直接继承 BaseMapper 即可覆盖规则管理所需的大部分基础 CRUD。
 * 如果后续出现复杂统计、联表分析或报表查询，再在这里扩展自定义 SQL。
 */
@Mapper
public interface QualityRuleMapper extends BaseMapper<QualityRule> {
}
