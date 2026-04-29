package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyChangeRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:03
 * @Description DataSmart Govern Backend - SyncPermissionPolicyChangeRequestMapper.java
 * @Version:1.0.0
 *
 * 权限绑定变更申请 Mapper。
 */
@Mapper
public interface SyncPermissionPolicyChangeRequestMapper extends BaseMapper<SyncPermissionPolicyChangeRequest> {
}
