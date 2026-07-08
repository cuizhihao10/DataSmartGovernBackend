/**
 * @Author : Cui
 * @Date: 2026/07/08 18:16
 * @Description DataSmart Govern Backend - ProjectMembershipView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;

import java.time.LocalDateTime;

/**
 * 项目成员授权前端视图。
 *
 * <p>为什么不直接把 {@link PermissionProjectMembership} 实体返回给页面？</p>
 *
 * <p>当前产品层级已经从早期的“租户 -> 应用 -> 工作空间 -> 项目/资源”收敛为
 * “租户 -> 项目 -> 资源”。数据库实体里仍然保留 {@code workspaceId}，是为了兼容历史审计、
 * 老 Keycloak claim、Agent 内部 workspace 语义以及既有数据迁移；但这些兼容字段不应该继续出现在
 * 普通用户和管理后台页面中，否则前端会误以为创建成员授权时还需要选择工作空间。</p>
 *
 * <p>因此本视图作为 Controller 对外返回的稳定合同，只暴露页面真正需要理解的字段：
 * 成员关系主键、租户、actor、项目、项目内角色、授权来源、启停状态和时间戳。
 * 如果未来后端实体继续增加内部字段，也应该先经过这个视图层判断是否适合暴露。</p>
 */
public record ProjectMembershipView(
        /**
         * 项目成员关系主键。
         *
         * <p>用于详情、更新、启用、禁用等成员管理操作。它只代表一条授权事实，
         * 不应该被前端当成用户 ID、项目 ID 或权限编码使用。</p>
         */
        Long membershipId,

        /**
         * 租户 ID。
         *
         * <p>平台管理员可能跨租户查看成员关系；租户管理员、项目负责人、运营和审计角色通常只能看到
         * 自己租户内的数据。租户边界仍由 service 层按 actor 上下文强校验。</p>
         */
        Long tenantId,

        /**
         * 被授权 actor ID。
         *
         * <p>actor 可以是普通用户、服务账号、机器人账号或外部身份映射后的主体，
         * 因此这里不命名为 userId，避免后续接入企业 IdP、Agent service account 时语义变窄。</p>
         */
        Long actorId,

        /**
         * 项目 ID。
         *
         * <p>这是用户可见资源归属的核心维度。datasource-management、data-sync、data-quality 等模块
         * 最终都会使用授权项目集合完成 {@code project_id} 数据范围过滤。</p>
         */
        Long projectId,

        /**
         * 项目内角色。
         *
         * <p>常见值包括 OWNER、MAINTAINER、MEMBER、VIEWER。PROJECT_OWNER 角色在修改成员时还会受到
         * “不能授予 OWNER”的额外限制，避免项目内权限无限扩散。</p>
         */
        String projectRole,

        /**
         * 授权来源。
         *
         * <p>用于审计和问题排查，例如 MANUAL 表示手工授权，IMPORT 表示批量导入，
         * IDP_GROUP 表示来自企业身份源的组同步，APPROVAL 表示来自审批流。</p>
         */
        String grantSource,

        /**
         * 是否启用。
         *
         * <p>禁用关系不会参与授权项目集合物化，但仍保留记录，方便审计员追溯“曾经授权过谁”。</p>
         */
        Boolean enabled,

        /**
         * 创建时间。
         *
         * <p>用于管理后台展示成员首次进入项目的时间，也可辅助审计授权时序。</p>
         */
        LocalDateTime createTime,

        /**
         * 更新时间。
         *
         * <p>用于判断成员授权最近一次变更时间，例如角色调整、启用/禁用或授权来源更新。</p>
         */
        LocalDateTime updateTime) {

    /**
     * 将数据库实体转换为前端视图。
     *
     * <p>注意这里故意不读取、不返回 {@code workspaceId}。如果历史数据中存在 workspaceId，
     * 页面也不应该感知它；新建和幂等授权路径会逐步把用户可见授权写成项目级 {@code workspaceId=null}。</p>
     *
     * @param membership 数据库中的项目成员授权实体。
     * @return 不含 workspace 兼容字段的页面视图；入参为空时返回空，方便调用方做空安全转换。
     */
    public static ProjectMembershipView from(PermissionProjectMembership membership) {
        if (membership == null) {
            return null;
        }
        return new ProjectMembershipView(
                membership.getId(),
                membership.getTenantId(),
                membership.getActorId(),
                membership.getProjectId(),
                membership.getProjectRole(),
                membership.getGrantSource(),
                membership.getEnabled(),
                membership.getCreateTime(),
                membership.getUpdateTime()
        );
    }
}
