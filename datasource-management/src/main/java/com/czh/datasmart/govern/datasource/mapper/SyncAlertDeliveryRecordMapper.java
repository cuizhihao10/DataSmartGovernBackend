package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncAlertDeliveryRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:05
 * @Description DataSmart Govern Backend - SyncAlertDeliveryRecordMapper.java
 * @Version:1.0.0
 *
 * 治理告警投递记录 Mapper。
 * 当前主要承担简单的单表查询和插入职责，
 * 让每次告警投递都能形成持久化审计轨迹。
 */
@Mapper
public interface SyncAlertDeliveryRecordMapper extends BaseMapper<SyncAlertDeliveryRecord> {
}
