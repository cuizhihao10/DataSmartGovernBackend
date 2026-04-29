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
    SYNC_TASK,
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
    AUDIT_LOG,
    SYSTEM_SETTING,
    TENANT_SETTING,
    AI_RUNTIME,
    AGENT_WORKSPACE
}
