package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncPermissionAction.java
 * @Version:1.0.0
 *
 * 同步域权限动作枚举。
 * 这一层的目标，是把“角色能做什么”从角色枚举自身的方法里拆出来，
 * 改成更接近企业权限中心的“资源 + 动作”表达方式。
 *
 * 当前已经覆盖了：
 * - 元数据查看；
 * - 模板和任务管理；
 * - 审批与管理员强制控制；
 * - 队列治理；
 * - 告警治理；
 * - 执行器回调；
 * - 权限策略查看与管理。
 */
public enum SyncPermissionAction {
    VIEW_STRUCTURE,
    VIEW_SAMPLE,
    EXECUTE_READ_ONLY_QUERY,
    MANAGE,
    CREATE,
    VIEW_POLICY,
    MANAGE_POLICY,
    UPDATE_OWNED,
    UPDATE_ANY,
    OPERATE_OWNED,
    OPERATE_ANY,
    APPROVE,
    ADMIN_OVERRIDE,
    VIEW_QUEUE_HEALTH,
    SCAN_QUEUE_AGING,
    VIEW_ALERT,
    ACKNOWLEDGE_ALERT,
    RESOLVE_ALERT,
    DISPATCH_ALERT,
    CLAIM,
    HEARTBEAT,
    REPORT_PROGRESS,
    REPORT_RESULT
}
