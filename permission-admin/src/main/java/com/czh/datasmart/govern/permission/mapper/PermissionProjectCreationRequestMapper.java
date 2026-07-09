/**
 * @Author : Cui
 * @Date: 2026/07/10 20:56
 * @Description DataSmart Govern Backend - PermissionProjectCreationRequestMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionProjectCreationRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper for project creation approval requests.
 */
@Mapper
public interface PermissionProjectCreationRequestMapper extends BaseMapper<PermissionProjectCreationRequest> {
}
