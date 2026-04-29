package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionGovernanceNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncPermissionGovernanceNotificationMapper.java
 * @Version:1.0.0
 *
 * 权限治理通知 Mapper。
 * 当前主要承担单表持久化和查询职责，业务解释继续放在 service 层。
 */
@Mapper
public interface SyncPermissionGovernanceNotificationMapper extends BaseMapper<SyncPermissionGovernanceNotification> {
}
