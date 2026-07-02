/**
 * @Author : Cui
 * @Date: 2026/07/02 04:10
 * @Description DataSmart Govern Backend - PermissionProjectMembershipCopySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;

/**
 * 项目成员授权实体的审计前快照复制器。
 *
 * <p>更新前复制完整业务字段，使审计支持可以比较 before/after；复制对象不写数据库，也不改变原实体。
 */
final class PermissionProjectMembershipCopySupport {

    private PermissionProjectMembershipCopySupport() {
    }

    static PermissionProjectMembership copyOf(PermissionProjectMembership source) {
        if (source == null) {
            return null;
        }
        PermissionProjectMembership copy = new PermissionProjectMembership();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setActorId(source.getActorId());
        copy.setProjectId(source.getProjectId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setProjectRole(source.getProjectRole());
        copy.setGrantSource(source.getGrantSource());
        copy.setEnabled(source.getEnabled());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        return copy;
    }
}
