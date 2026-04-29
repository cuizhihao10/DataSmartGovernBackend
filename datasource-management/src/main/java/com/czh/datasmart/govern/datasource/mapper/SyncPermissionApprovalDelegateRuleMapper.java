package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionApprovalDelegateRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalDelegateRuleMapper.java
 * @Version:1.0.0
 *
 * 权限审批委托规则 Mapper。
 * 当前阶段主要承担基础 CRUD 和条件查询职责，
 * 复杂的审批决策语义继续留在 service 层，避免 SQL 层承担过多业务解释逻辑。
 */
@Mapper
public interface SyncPermissionApprovalDelegateRuleMapper extends BaseMapper<SyncPermissionApprovalDelegateRule> {
}
