/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityCheckReportMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质量检测报告 Mapper。
 * 当前报告表以写入和按规则查询为主，基础 Mapper 已足够支撑现阶段需求。
 */
@Mapper
public interface QualityCheckReportMapper extends BaseMapper<QualityCheckReport> {
}
