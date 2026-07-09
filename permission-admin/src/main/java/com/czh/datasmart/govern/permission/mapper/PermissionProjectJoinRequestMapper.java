/**
 * @Author : Cui
 * @Date: 2026/07/10 20:41
 * @Description DataSmart Govern Backend - PermissionProjectJoinRequestMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionProjectJoinRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for project join request workflow facts.
 */
@Mapper
public interface PermissionProjectJoinRequestMapper extends BaseMapper<PermissionProjectJoinRequest> {
}
