/**
 * @Author : Cui
 * @Date: 2026/07/10 14:00
 * @Description DataSmart Govern Backend - PermissionApplicationMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 租户应用主数据 Mapper。
 */
@Mapper
public interface PermissionApplicationMapper extends BaseMapper<PermissionApplication> {

    @Select("SELECT nextval('permission_admin.permission_application_id_seq')")
    Long nextApplicationId();
}
