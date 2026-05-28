/**
 * @Author : Cui
 * @Date: 2026/05/10 13:29
 * @Description DataSmart Govern Backend - PermissionProjectMembershipMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;

/**
 * 用户项目授权关系 Mapper。
 *
 * <p>当前只需要 MyBatis-Plus 的基础 CRUD 能力即可完成授权项目集合查询。
 * 后续如果项目成员规模很大，可以在这里补自定义 SQL，例如按租户、用户、资源类型做分页、批量缓存预热或统计。
 */
public interface PermissionProjectMembershipMapper extends BaseMapper<PermissionProjectMembership> {
}
