/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionDataScopeLevel.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.support;

/**
 * 数据范围级别。
 *
 * <p>RBAC 只回答“角色能不能做某个动作”，但数据治理产品还必须回答“能对哪些数据做这个动作”。
 * 这就是数据范围模型存在的原因。没有数据范围，租户隔离、项目隔离、数据源授权都会变得很脆。
 */
public enum PermissionDataScopeLevel {
    /**
     * 只能访问自己创建或被显式授权的资源。
     */
    SELF,

    /**
     * 可以访问项目、空间或业务域范围内的资源。
     */
    PROJECT,

    /**
     * 可以访问当前租户内资源。
     */
    TENANT,

    /**
     * 可以跨租户访问全平台资源。该级别通常只应授予平台管理员和少量服务账号。
     */
    PLATFORM
}
