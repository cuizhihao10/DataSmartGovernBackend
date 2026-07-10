/**
 * @Author : Cui
 * @Date: 2026/07/10 14:00
 * @Description DataSmart Govern Backend - PermissionTenantMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 租户主数据 Mapper。
 *
 * <p>租户 ID 必须由数据库序列分配，不能让前端填写，也不能使用 max+1，避免多实例并发开租产生主键冲突。</p>
 */
@Mapper
public interface PermissionTenantMapper extends BaseMapper<PermissionTenant> {

    @Select("SELECT nextval('permission_admin.permission_tenant_id_seq')")
    Long nextTenantId();
}
