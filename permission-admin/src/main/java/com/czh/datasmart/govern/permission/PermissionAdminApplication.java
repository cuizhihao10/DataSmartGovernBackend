/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAdminApplication.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 权限与管理中心启动类。
 *
 * <p>permission-admin 是 DataSmart Govern 从“若干业务模块各自维护权限”走向“平台统一治理”的关键模块。
 * 它不会直接执行数据同步、质量检测或任务调度，而是提供这些模块共同依赖的权限事实：
 * 角色有哪些、角色能看到哪些菜单、能访问哪些路由、能操作哪些资源、数据范围边界是什么、哪些高风险动作需要审批。
 *
 * <p>当前版本先落地模块骨架和基础查询/判定 API，后续可以继续扩展：
 * 1. 与 gateway 集成，做路由级授权；
 * 2. 与 datasource-management 集成，承接已有本地权限策略；
 * 3. 与 task-management 集成，控制强制重试、取消、优先级调整等操作；
 * 4. 与 observability 或 audit-center 集成，沉淀权限变更审计。
 */
@SpringBootApplication
@MapperScan("com.czh.datasmart.govern.permission.mapper")
@EnableScheduling
public class PermissionAdminApplication {

    /**
     * Spring Boot 标准启动入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(PermissionAdminApplication.class, args);
    }
}
