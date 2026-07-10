/**
 * @Author : Cui
 * @Date: 2026/07/08 23:20
 * @Description DataSmart Govern Backend - PermissionProjectView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionProject;

import java.time.LocalDateTime;

/**
 * 前端项目视图。
 *
 * <p>不要把 {@link PermissionProject} 原样返回给页面，原因是实体里仍然存在 defaultWorkspaceId 等迁移期兼容字段。
 * 当前产品页面只需要理解“租户 -> 项目”，因此视图层主动隐藏工作空间字段，让前端项目切换器、数据源创建页、
 * 同步任务创建页都沿着同一套简洁合同实现。</p>
 */
public record PermissionProjectView(
        /**
         * 项目 ID。
         *
         * <p>前端选中项目后，gateway 或前端上下文会把它作为 X-DataSmart-Project-Id 传给业务服务。</p>
         */
        Long projectId,

        /**
         * 租户 ID。
         *
         * <p>租户当前仍可只暴露 ID；项目必须暴露名称和编码，方便用户理解自己正在操作哪个业务项目。</p>
         */
        Long tenantId,

        /**
         * 租户名称。平台管理员跨租户切换项目时用于区分同名项目，页面无需把 tenantId 当显示名称。
         */
        String tenantName,

        /**
         * 项目编码。
         */
        String projectCode,

        /**
         * 项目名称。
         */
        String projectName,

        /**
         * 项目类型。
         */
        String projectType,

        /**
         * 项目状态。
         */
        String status,

        /**
         * 默认负责人 actorId。
         */
        Long ownerActorId,

        /**
         * 项目描述。
         */
        String description,

        /**
         * 创建时间。
         */
        LocalDateTime createTime,

        /**
         * 更新时间。
         */
        LocalDateTime updateTime) {

    /**
     * 从持久化实体转换为前端视图。
     *
     * <p>这里是隐藏 workspace 兼容字段的唯一出口。后续如果项目实体继续增加内部字段，
     * 也应该在这里逐一判断是否适合暴露给页面，而不是让 Controller 直接返回实体。</p>
     */
    public static PermissionProjectView from(PermissionProject project) {
        return from(project, null);
    }

    public static PermissionProjectView from(PermissionProject project, String tenantName) {
        if (project == null) {
            return null;
        }
        return new PermissionProjectView(
                project.getProjectId(),
                project.getTenantId(),
                tenantName,
                project.getProjectCode(),
                project.getProjectName(),
                project.getProjectType(),
                project.getStatus(),
                project.getOwnerActorId(),
                project.getDescription(),
                project.getCreateTime(),
                project.getUpdateTime()
        );
    }
}
