/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - SyncActorContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 数据同步操作者上下文。
 *
 * <p>data-sync 会涉及源端读取、目标端写入、数据导出、历史回放和补数等高风险动作。
 * 因此从第一版开始，Service 方法就不应该只接收 taskId 或 templateId，而要携带调用者身份。
 *
 * @param tenantId 当前操作者所属租户
 * @param actorId 当前操作者 ID
 * @param actorRole 当前操作者角色
 * @param traceId 当前请求链路 ID
 * @param dataScopeLevel gateway 从 permission-admin 判定结果中透传的数据范围级别，例如 SELF、PROJECT、TENANT、PLATFORM
 * @param dataScopeExpression 数据范围表达式，当前 data-sync 先按 scopeLevel 落地，表达式保留给后续 JSON DSL/SQL 安全解析
 * @param authorizedProjectIds gateway 透传的项目授权集合；PROJECT 范围下用于生成 `project_id IN (...)` 查询条件
 * @param approvalRequired 当前动作或访问是否需要审批；查询链路先记录上下文，高风险写操作后续可据此进入审批流
 */
public record SyncActorContext(Long tenantId,
                               Long actorId,
                               String actorRole,
                               String traceId,
                               String dataScopeLevel,
                               String dataScopeExpression,
                               List<Long> authorizedProjectIds,
                               Boolean approvalRequired) {

    /**
     * 兼容旧调用点的构造方法。
     *
     * <p>部分后台调度器、单元测试或暂未经过 gateway 的内部调用，只能提供租户、操作者和 traceId。
     * 保留四参数构造可以避免为了接入数据范围而一次性改动所有非 HTTP 调用点。
     * 当 dataScopeLevel 为空时，data-sync 会根据角色做本地兜底推断。
     */
    public SyncActorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        this(tenantId, actorId, actorRole, traceId, null, null, List.of(), null);
    }

    /**
     * 兼容已经接入 dataScopeLevel/dataScopeExpression 但还未传授权项目集合的旧调用点。
     */
    public SyncActorContext(Long tenantId,
                            Long actorId,
                            String actorRole,
                            String traceId,
                            String dataScopeLevel,
                            String dataScopeExpression,
                            Boolean approvalRequired) {
        this(tenantId, actorId, actorRole, traceId, dataScopeLevel, dataScopeExpression, List.of(), approvalRequired);
    }
}
