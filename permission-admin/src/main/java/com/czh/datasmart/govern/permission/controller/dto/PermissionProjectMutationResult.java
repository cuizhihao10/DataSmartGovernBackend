/**
 * @Author : Cui
 * @Date: 2026/07/08 23:21
 * @Description DataSmart Govern Backend - PermissionProjectMutationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 项目变更结果。
 *
 * <p>创建项目后，前端通常需要立即把新项目加入项目切换器，并切换到该项目继续创建数据源或同步任务。
 * 因此结果里返回核心定位字段和用户可读消息，而不是只返回一个布尔值。</p>
 */
public record PermissionProjectMutationResult(
        /**
         * 新项目 ID。
         */
        Long projectId,

        /**
         * 项目所属租户。
         */
        Long tenantId,

        /**
         * 项目编码。
         */
        String projectCode,

        /**
         * 项目名称。
         */
        String projectName,

        /**
         * 项目状态。
         */
        String status,

        /**
         * 自动授予 OWNER 的负责人 actorId。
         */
        Long ownerActorId,

        /**
         * 面向前端的结果说明。
         */
        String message) {
}
