/**
 * @Author : Cui
 * @Date: 2026/07/09 01:42
 * @Description DataSmart Govern Backend - AuthorizationSubjectCandidateView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.entity.PermissionRole;

/**
 * 授权主体候选视图。
 *
 * <p>该响应对象刻意对齐 datasource-management 的实例级授权入参：
 * {@code subjectType + subjectId + subjectName + subjectRole} 可以直接填入授权请求。
 * 这样前端弹窗只需要“搜索候选 -> 勾选候选 -> 选择 VIEW/USE/MANAGE 动作”，不需要再理解
 * permission-admin 内部表结构，也不需要让用户手工输入 actorId 或 roleCode。</p>
 *
 * <p>安全边界：
 * 1. 返回的是低敏影子身份信息，不返回密码、token、Keycloak client secret、provider 管理地址；
 * 2. email 只返回脱敏值，避免授权弹窗扩散个人敏感信息；
 * 3. {@code selectable=false} 的候选仅用于解释“为什么这个主体不建议授权”，前端默认应禁止选择。</p>
 *
 * @param subjectType 授权主体类型，当前为 USER 或 ROLE。
 * @param subjectId 授权主体 ID。USER 为 DataSmart actorId 字符串；ROLE 为角色编码。
 * @param subjectName 面向页面展示的主体名称，USER 通常是 username，ROLE 通常是 roleName。
 * @param subjectRole 主体角色快照。USER 为 actorRole，ROLE 为 roleCode。
 * @param actorType 主体类型快照。USER 候选来自影子身份；ROLE 候选固定为 ROLE。
 * @param tenantId 主体所属租户。平台内置角色 tenantId=0，租户用户为实际租户 ID。
 * @param projectId 查询上下文项目 ID，用于提醒前端该候选来自哪个项目授权范围。
 * @param username 用户名，仅 USER 有值。
 * @param maskedEmail 脱敏邮箱，仅 USER 有值。
 * @param status 主体状态，USER 为 ACTIVE/DISABLED，ROLE 为 ACTIVE/DISABLED。
 * @param sourceType 候选来源表或来源类型，方便排查候选为什么出现。
 * @param selectable 是否建议前端允许选择该候选。
 * @param disabledReason 不可选原因或账号禁用原因，便于页面给出友好提示。
 */
public record AuthorizationSubjectCandidateView(String subjectType,
                                                String subjectId,
                                                String subjectName,
                                                String subjectRole,
                                                String actorType,
                                                Long tenantId,
                                                Long projectId,
                                                String username,
                                                String maskedEmail,
                                                String status,
                                                String sourceType,
                                                Boolean selectable,
                                                String disabledReason) {

    /**
     * 从 DataSmart 影子身份构建 USER 候选。
     *
     * @param user 影子身份记录。
     * @param projectId 当前候选查询的项目上下文。
     * @param maskedEmail 已脱敏邮箱。
     * @return 可用于资源授权弹窗展示的 USER 候选。
     */
    public static AuthorizationSubjectCandidateView fromUser(PermissionIdentityUser user,
                                                             Long projectId,
                                                             String maskedEmail) {
        boolean active = user != null && "ACTIVE".equalsIgnoreCase(user.getStatus());
        return new AuthorizationSubjectCandidateView(
                "USER",
                user == null || user.getActorId() == null ? null : String.valueOf(user.getActorId()),
                user == null ? null : user.getUsername(),
                user == null ? null : user.getActorRole(),
                user == null ? null : user.getActorType(),
                user == null ? null : user.getTenantId(),
                projectId,
                user == null ? null : user.getUsername(),
                maskedEmail,
                user == null ? null : user.getStatus(),
                "PERMISSION_IDENTITY_USER",
                active,
                active ? null : user == null ? null : user.getDisabledReason()
        );
    }

    /**
     * 从 permission_role 构建 ROLE 候选。
     *
     * @param role 角色记录。
     * @param projectId 当前候选查询的项目上下文。
     * @return 可用于资源授权弹窗展示的 ROLE 候选。
     */
    public static AuthorizationSubjectCandidateView fromRole(PermissionRole role, Long projectId) {
        boolean enabled = role != null && Boolean.TRUE.equals(role.getEnabled());
        return new AuthorizationSubjectCandidateView(
                "ROLE",
                role == null ? null : role.getRoleCode(),
                role == null ? null : role.getRoleName(),
                role == null ? null : role.getRoleCode(),
                "ROLE",
                role == null ? null : role.getTenantId(),
                projectId,
                null,
                null,
                enabled ? "ACTIVE" : "DISABLED",
                "PERMISSION_ROLE",
                enabled,
                enabled ? null : "角色已禁用，不建议继续授权给业务资源。"
        );
    }
}
