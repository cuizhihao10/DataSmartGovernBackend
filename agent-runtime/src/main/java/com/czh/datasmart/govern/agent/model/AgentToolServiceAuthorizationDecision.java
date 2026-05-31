/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentToolServiceAuthorizationDecision.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 工具服务间授权预检结论。
 *
 * <p>该枚举刻意不只保留 allowed/denied 两种结果，因为商业化系统里的授权链路常常会出现：
 * 功能尚未启用、仅做本地结构预览、远端权限中心不可用、远端明确拒绝等不同情况。
 * 如果全部压缩成 false，前端、审计台和调度器就无法解释“到底是未配置、网络故障，还是权限真的不足”。</p>
 */
public enum AgentToolServiceAuthorizationDecision {

    /**
     * 未进行授权预检。
     *
     * <p>常见原因是配置关闭。它不是“允许”，也不是“拒绝”，而是提醒调用方：
     * 当前 preview 不能作为真实执行前的权限依据。</p>
     */
    NOT_EVALUATED,

    /**
     * 本地结构预览认为上下文完整。
     *
     * <p>这只说明工具计划携带了足够的租户、项目、actor、动作和服务账号上下文，
     * 不等同于 permission-admin 已经授权。</p>
     */
    LOCAL_PREVIEW_ALLOWED,

    /**
     * 本地结构预览发现关键上下文缺失。
     *
     * <p>例如缺少 tenantId、projectId、actorId、toolCode 或动作集合。
     * 这种情况下即使后续接入 permission-admin，也很可能无法做出可靠判定。</p>
     */
    LOCAL_PREVIEW_REJECTED,

    /**
     * permission-admin 明确允许该服务账号动作。
     */
    PERMISSION_ADMIN_ALLOWED,

    /**
     * permission-admin 明确拒绝该服务账号动作。
     */
    PERMISSION_ADMIN_REJECTED,

    /**
     * permission-admin 调用失败或返回不可解释结果。
     *
     * <p>生产环境建议 fail-closed，即远端不可用时不要真实执行会产生副作用的工具；
     * 预览接口则会把该状态透明返回给调用方，方便运维定位。</p>
     */
    PERMISSION_ADMIN_UNAVAILABLE
}
