/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionRoleMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionRole;

/**
 * 角色 Mapper。
 *
 * <p>当前使用 MyBatis-Plus BaseMapper 提供基础 CRUD。
 * 后续如果要做复杂的“租户角色覆盖平台默认角色”查询，可以在这里增加自定义 SQL。
 */
public interface PermissionRoleMapper extends BaseMapper<PermissionRole> {
}
