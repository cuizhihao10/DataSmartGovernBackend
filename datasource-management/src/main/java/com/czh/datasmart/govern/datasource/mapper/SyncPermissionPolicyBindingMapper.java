package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyBinding;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionPolicyBindingMapper.java
 * @Version:1.0.0
 *
 * 同步权限策略绑定 Mapper。
 * 当前先复用 MyBatis-Plus 的基础 CRUD 能力，因为这一轮的重点是把权限绑定对象落成完整治理域，
 * 而不是一开始就引入复杂 SQL。
 */
@Mapper
public interface SyncPermissionPolicyBindingMapper extends BaseMapper<SyncPermissionPolicyBinding> {
}
