/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformActorType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

/**
 * 平台操作者类型。
 *
 * 角色回答“这个身份有什么权限”，操作者类型回答“这个身份是什么来源”。
 * 商业化系统中必须区分人类用户、服务账号、系统调度器和智能体，因为它们的审计要求、权限边界和风险等级不同。
 */
public enum PlatformActorType {
    /**
     * 人类用户，例如普通用户、项目负责人、租户管理员、平台管理员。
     */
    USER,

    /**
     * 服务账号，例如执行器、内部批处理任务、跨服务调用身份。
     */
    SERVICE_ACCOUNT,

    /**
     * 智能体身份，例如 agent-runtime 代表某个工作区执行工具调用。
     */
    AGENT,

    /**
     * 系统调度器身份，例如定时扫描、自动补偿、后台巡检。
     */
    SYSTEM_SCHEDULER
}
