/**
 * @Author : Cui
 * @Date: 2026/07/08 23:18
 * @Description DataSmart Govern Backend - PermissionProjectQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 项目查询条件。
 *
 * <p>该 DTO 服务项目切换器、项目管理页和后续 Agent 会话上下文选择。
 * 它保留 tenant/application/status 等管理筛选条件，但 Service 会根据操作者角色追加真实可见范围：
 * 平台管理员可跨租户，租户管理员可看本租户，项目负责人和普通业务角色默认只看自己有成员授权的项目。</p>
 */
public record PermissionProjectQueryCriteria(
        /**
         * 租户 ID。
         *
         * <p>为空时按操作者租户解析；平台管理员可以显式筛选其他租户。</p>
         */
        Long tenantId,

        /**
         * 应用 ID。
         *
         * <p>普通项目切换器一般不需要传；多应用管理后台可以用它筛选某个产品应用下的项目。</p>
         */
        Long applicationId,

        /**
         * 项目 ID。
         *
         * <p>用于精确定位项目，也可服务详情跳转前的轻量过滤。</p>
         */
        Long projectId,

        /**
         * 项目编码。
         *
         * <p>精确匹配。编码适合脚本、导入导出和排障。</p>
         */
        String projectCode,

        /**
         * 项目名称关键字。
         *
         * <p>模糊匹配，服务项目切换器搜索框。</p>
         */
        String projectName,

        /**
         * 项目状态。
         *
         * <p>为空时默认排除 ARCHIVED；管理后台可以传 ACTIVE、DISABLED 或 ARCHIVED 做专项查询。</p>
         */
        String status,

        /**
         * 是否只看当前 actor 被成员关系授权的项目。
         *
         * <p>普通业务用户默认就是这种语义；租户管理员和平台管理员可通过该参数切换到“我的项目”视图。</p>
         */
        Boolean onlyMine,

        /**
         * 当前页码。
         */
        Long current,

        /**
         * 每页大小。
         */
        Long size) {
}
