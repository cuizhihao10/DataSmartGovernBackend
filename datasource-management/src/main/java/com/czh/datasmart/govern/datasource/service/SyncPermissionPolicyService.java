package com.czh.datasmart.govern.datasource.service;

import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionPolicySnapshot;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:04
 * @Description DataSmart Govern Backend - SyncPermissionPolicyService.java
 * @Version:1.0.0
 *
 * 本地权限策略快照服务。
 * 这一层的目标不是替代未来统一 permission-admin，而是把当前 datasource-management
 * 已经存在的菜单、路由、资源动作、数据范围和绑定覆盖逻辑整理成一个“可解释的治理快照”。
 */
public interface SyncPermissionPolicyService {

    /**
     * 生成本地权限策略快照。
     *
     * @param actorId 发起查看的操作人 ID
     * @param actorRole 发起查看的角色
     * @param actorTenantId 发起查看者所属租户
     * @param targetTenantId 目标租户视角，为空时表示平台全局视角
     * @return 当前角色视角下的菜单、路由、动作和数据范围快照
     */
    SyncPermissionPolicySnapshot buildSnapshot(Long actorId,
                                               String actorRole,
                                               Long actorTenantId,
                                               Long targetTenantId);
}
