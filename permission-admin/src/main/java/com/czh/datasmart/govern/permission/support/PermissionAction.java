/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.support;

/**
 * 权限动作。
 *
 * <p>动作粒度决定权限系统能否从“能进页面”升级到“能点哪个按钮、能执行哪个高风险操作”。
 * 当前先定义通用动作，后续每个业务域可以在不破坏平台模型的前提下扩展更细动作。
 */
public enum PermissionAction {
    VIEW,
    CREATE,
    UPDATE,
    DELETE,
    EXECUTE,
    APPROVE,
    EXPORT,
    FORCE_RETRY,
    FORCE_CANCEL,
    PRIORITY_OVERRIDE,
    CONFIGURE,
    AUDIT
}
