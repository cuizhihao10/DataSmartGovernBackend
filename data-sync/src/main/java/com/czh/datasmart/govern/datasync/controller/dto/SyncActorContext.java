/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - SyncActorContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;

import java.util.List;

/**
 * 数据同步操作上下文。
 *
 * <p>data-sync 会涉及源端读取、目标端写入、任务调度、失败重试、脏数据修复和运行审计等高风险动作。
 * 因此 Service 方法不应该只接收 taskId/templateId，而要携带“是谁、在哪个租户、哪个项目下操作”。
 * 这些上下文通常由 gateway 从登录态、项目切换器和 permission-admin 判定结果中注入。</p>
 *
 * @param tenantId 当前租户 ID。
 * @param projectId 当前项目 ID；用于新建模板时自动归属当前项目，而不是让用户手填项目主键。
 * @param workspaceId 历史兼容工作空间 ID。FlashSync 数据同步用户侧链路已经不再使用工作空间做资源归属；
 *                    该字段仅给旧执行记录、内部 worker 或 Agent 沙箱兼容，不应出现在新建任务、列表筛选和页面表单中。
 * @param actorId 当前操作人 ID。
 * @param actorRole 当前操作人角色。
 * @param traceId 当前请求链路 ID。
 * @param dataScopeLevel gateway 从 permission-admin 透传的数据范围级别，例如 SELF、PROJECT、TENANT、PLATFORM。
 * @param dataScopeExpression 数据范围表达式；当前仅作为审计和后续策略 DSL 预留，不直接拼接 SQL。
 * @param authorizedProjectIds PROJECT 范围下已经物化的授权项目集合；它主要用于列表过滤和详情可读性校验。
 * @param authorizedProjectRoles PROJECT 范围下已经物化的“项目 -> 项目内角色”集合；它用于判断当前用户在某项目下
 *                               是 READER 只读，还是 MANAGER/OWNER 可管理，避免前端隐藏按钮后仍可直调写接口。
 * @param approvalRequired 当前访问是否被上游标记为需要审批；新建普通同步任务不直接使用该字段进入审批流。
 */
public record SyncActorContext(Long tenantId,
                               Long projectId,
                               Long workspaceId,
                               Long actorId,
                               String actorRole,
                               String traceId,
                               String dataScopeLevel,
                               String dataScopeExpression,
                               List<Long> authorizedProjectIds,
                               List<PlatformAuthorizedProjectRole> authorizedProjectRoles,
                               Boolean approvalRequired) {

    /**
     * record 主构造的轻量规整。
     *
     * <p>权限 Header 解析遵循“安全容错、业务处 fail-closed”的策略：
     * 如果上游没有传项目集合或项目角色集合，这里统一变成空集合，避免下游出现空指针。
     * 真正需要写入、执行、删除、回放等管理动作时，{@code SyncDataScopeSupport}
     * 会根据是否处于显式 PROJECT 范围决定是否必须存在 MANAGER/OWNER/SERVICE 角色。</p>
     */
    public SyncActorContext {
        authorizedProjectIds = authorizedProjectIds == null ? List.of() : List.copyOf(authorizedProjectIds);
        authorizedProjectRoles = authorizedProjectRoles == null ? List.of() : List.copyOf(authorizedProjectRoles);
    }

    /**
     * 兼容旧调用点的四参构造方法。
     *
     * <p>后台调度器、worker loop、单元测试和部分内部回调只需要租户、操作者和 traceId。
     * 对这些调用点而言，项目可以为空，由业务对象自身的 projectId 继续约束读写范围；workspaceId 在用户侧新链路中保持为空。</p>
     */
    public SyncActorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        this(tenantId, null, null, actorId, actorRole, traceId, null, null, List.of(), List.of(), null);
    }

    /**
     * 兼容已接入 dataScopeLevel/dataScopeExpression 但还没有项目/工作空间 Header 的旧调用点。
     */
    public SyncActorContext(Long tenantId,
                            Long actorId,
                            String actorRole,
                            String traceId,
                            String dataScopeLevel,
                            String dataScopeExpression,
                            Boolean approvalRequired) {
        this(tenantId, null, null, actorId, actorRole, traceId,
                dataScopeLevel, dataScopeExpression, List.of(), List.of(), approvalRequired);
    }

    /**
     * 兼容历史八参构造方法。
     *
     * <p>这层重载非常重要：项目中已有不少测试和 worker 支撑类直接 new 该上下文。
     * 如果直接修改 record 主构造参数而不保留旧签名，会把本次“创建向导合同收口”扩散成大量无关重构。</p>
     */
    public SyncActorContext(Long tenantId,
                            Long actorId,
                            String actorRole,
                            String traceId,
                            String dataScopeLevel,
                            String dataScopeExpression,
                            List<Long> authorizedProjectIds,
                            Boolean approvalRequired) {
        this(tenantId, null, null, actorId, actorRole, traceId,
                dataScopeLevel, dataScopeExpression, authorizedProjectIds, List.of(), approvalRequired);
    }

    /**
     * 兼容历史十参构造方法。
     *
     * <p>项目里已有不少测试、调度器和 worker 支撑类直接构造
     * {@code SyncActorContext(tenantId, projectId, workspaceId, ...)}。
     * 本轮新增项目角色快照不能强迫这些内部调用点立刻全部改造，否则会把“权限模型增强”
     * 扩散成大规模无关重构。因此保留旧签名，并把项目角色集合置为空；
     * 显式经过 gateway 的 HTTP 请求会由 Header 解析器填充真实角色。</p>
     */
    public SyncActorContext(Long tenantId,
                            Long projectId,
                            Long workspaceId,
                            Long actorId,
                            String actorRole,
                            String traceId,
                            String dataScopeLevel,
                            String dataScopeExpression,
                            List<Long> authorizedProjectIds,
                            Boolean approvalRequired) {
        this(tenantId, projectId, workspaceId, actorId, actorRole, traceId,
                dataScopeLevel, dataScopeExpression, authorizedProjectIds, List.of(), approvalRequired);
    }
}
