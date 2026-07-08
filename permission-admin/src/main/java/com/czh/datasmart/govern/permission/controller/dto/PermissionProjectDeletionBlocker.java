/**
 * @Author : Cui
 * @Date: 2026/07/08 23:58
 * @Description DataSmart Govern Backend - PermissionProjectDeletionBlocker.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 项目删除/归档阻断项。
 *
 * <p>这里不返回具体数据源名称、任务名称或 SQL 等明细，只返回资源类别和数量。
 * 明细查询应由对应业务模块在权限控制下提供，permission-admin 只负责告诉调用方“为什么不能删除”。</p>
 */
public record PermissionProjectDeletionBlocker(
        /**
         * 阻断资源类型，例如 DATASOURCE、DATA_SYNC_TEMPLATE、DATA_SYNC_TASK。
         */
        String resourceType,

        /**
         * 阻断数量。
         */
        long count,

        /**
         * 面向用户的低敏说明。
         */
        String message) {
}
