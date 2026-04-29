package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncGovernanceAlertMapper.java
 * @Version:1.0.0
 *
 * 同步治理告警 Mapper。
 */
@Mapper
public interface SyncGovernanceAlertMapper extends BaseMapper<SyncGovernanceAlert> {
}
