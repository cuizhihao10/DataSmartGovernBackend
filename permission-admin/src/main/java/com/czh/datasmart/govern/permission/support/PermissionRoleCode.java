/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionRoleCode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.support;

/**
 * 平台推荐角色编码。
 *
 * <p>真实商业产品不能只停留在 USER / ADMIN 两种身份。
 * 数据治理场景里，“能看数据”“能执行任务”“能审批高风险动作”“能管理租户”“能看审计日志”是不同权限维度。
 * 因此这里先沉淀一组推荐角色，后续数据库仍允许客户按租户扩展自定义角色。
 */
public enum PermissionRoleCode {

    /**
     * 普通用户：通常只能查看和操作自己有数据范围授权的资源。
     */
    ORDINARY_USER,

    /**
     * 项目负责人：通常能管理某个项目、空间或业务域下的任务、模板和成员。
     */
    PROJECT_OWNER,

    /**
     * 运营人员：通常能查看运行状态、处理告警、触发重试或恢复，但不一定能修改系统级策略。
     */
    OPERATOR,

    /**
     * 审计员：通常拥有只读审计视角，可以查看操作轨迹，但不应直接执行业务变更。
     */
    AUDITOR,

    /**
     * 租户管理员：管理本租户内角色、成员、菜单和数据范围，但不能跨租户操作。
     */
    TENANT_ADMINISTRATOR,

    /**
     * 平台管理员：管理全平台策略、系统设置和跨租户治理能力，权限最高也最需要审计。
     */
    PLATFORM_ADMINISTRATOR,

    /**
     * 服务账号：用于执行器、调度器、Agent、内部系统调用；必须和人类用户区分审计。
     */
    SERVICE_ACCOUNT
}
