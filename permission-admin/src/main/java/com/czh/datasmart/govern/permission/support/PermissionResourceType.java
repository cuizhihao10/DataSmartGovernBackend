/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionResourceType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.support;

/**
 * 权限资源类型。
 *
 * <p>资源类型用于把“权限判定”从单纯 URL 扩展到业务语义。
 * 例如同一个 POST 请求，创建同步任务、强制取消任务、导出敏感报告的风险完全不同，
 * 所以后续 permission-admin 需要同时理解 route、resourceType、action 和 dataScope。
 */
public enum PermissionResourceType {
    MENU,
    ROUTE,
    DATASOURCE,
    /**
     * 同步模板资源。
     *
     * <p>同步模板描述“源端、目标端、字段映射、写入策略、批大小、重试策略”等可复用配置。
     * 它和具体任务不同：模板变更可能影响后续所有基于该模板创建或运行的同步任务，
     * 因此真实产品里通常需要给模板配置、模板校验、模板复制、模板禁用单独授权。
     */
    SYNC_TEMPLATE,
    /**
     * 同步任务资源。
     *
     * <p>同步任务是 data-sync 模块面向业务用户的核心对象，负责表达“按什么触发方式执行哪份模板”。
     * 普通用户、项目负责人、运营人员和管理员都可能访问同步任务，但数据范围通常不同：
     * 普通用户看自己的任务，项目负责人看项目任务，租户管理员看本租户任务，平台管理员看全平台任务。
     */
    SYNC_TASK,
    /**
     * 同步执行资源。
     *
     * <p>同步执行代表一次具体运行，包括执行器认领、心跳、checkpoint、成功/失败回调和租约恢复。
     * 这些接口通常由服务账号或运营人员调用，不能和普通同步任务查询混为一类，否则机器身份和人类运营动作会难以审计。
     */
    SYNC_EXECUTION,
    /**
     * 同步事故资源。
     *
     * <p>事故记录承载故障接手、分派、解决、关闭和复盘等流程。
     * 它可能暴露任务失败原因、数据源信息、执行器状态甚至客户业务影响，因此需要区别于普通同步任务查看权限。
     */
    SYNC_INCIDENT,
    /**
     * 同步运营动作资源。
     *
     * <p>该类型用于人工介入、强制重跑、取消、归档、过期租约恢复等高风险控制面动作。
     * 它的存在让 gateway 和 permission-admin 可以将“看同步任务”和“处理同步事故/恢复卡死执行”拆成不同权限面。
     */
    SYNC_OPERATION,
    QUALITY_RULE,
    TASK,
    /**
     * 任务运营资源。
     *
     * <p>它与普通 TASK 的差异在于：TASK 通常代表“用户自己的任务定义、执行记录、进度和日志”，
     * 而 TASK_OPERATION 代表“队列健康、死信任务、延期任务、执行器租约、批量恢复”等运维视角。
     * 这些信息可能暴露跨任务、跨执行器甚至跨租户的运行状态，因此不能简单复用普通任务查看权限。
     * 将它独立成资源类型后，gateway 可以把 /api/task/operations/** 映射到该语义，
     * permission-admin 也能为运营人员、租户管理员、平台管理员配置更严格的数据范围和审计策略。
     */
    TASK_OPERATION,
    /**
     * 任务草稿资源。
     *
     * <p>任务草稿位于 Agent/模板/人工配置与真实任务队列之间。
     * 它需要独立授权：普通 TASK 权限不一定允许审批或转换草稿，TASK_OPERATION 权限也不应默认覆盖草稿编辑。
     * 独立资源类型可以让 permission-admin 后续配置“创建草稿、提交审批、审批通过、拒绝、转换真实任务”等按钮级动作。</p>
     */
    TASK_DRAFT,
    /**
     * 项目成员授权资源。
     *
     * <p>该资源保护 `permission_project_membership` 管理面。
     * 它看起来属于“系统设置”，但业务风险更具体：一旦成员授权被错误扩大，
     * 下游 datasource、data-sync、data-quality 的 PROJECT 数据范围都会随之扩大。
     * 因此把它单独建模，后续可以配置项目成员导入、启用、禁用、导出审计等按钮级权限。
     */
    PROJECT_MEMBERSHIP,
    AUDIT_LOG,
    SYSTEM_SETTING,
    TENANT_SETTING,
    AI_RUNTIME,
    AGENT_WORKSPACE
}
