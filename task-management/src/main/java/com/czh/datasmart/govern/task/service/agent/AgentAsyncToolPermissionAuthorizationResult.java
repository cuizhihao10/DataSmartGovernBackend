/**
 * @Author : Cui
 * @Date: 2026/06/01 23:58
 * @Description DataSmart Govern Backend - AgentAsyncToolPermissionAuthorizationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.List;

/**
 * permission-admin 返回给 Agent 异步工具 worker 的授权判定快照。
 *
 * <p>worker 只保留执行前复核真正需要的字段：是否允许、原因、命中效果、数据范围、项目集合、
 * 是否需要审批、策略版本和委托证据。它不感知权限中心表结构，也不持有菜单、角色、策略实体。
 * 这能让 permission-admin 独立演进，同时保持 task-management 的执行安全门稳定。</p>
 *
 * @param allowed true 表示 permission-admin 当前允许 worker 代表上游 actor 执行
 * @param reason 权限中心给出的可读原因，失败时会进入阻断说明
 * @param routeEffect 命中的路由策略效果，通常为 ALLOW 或 DENY
 * @param dataScopeLevel 数据范围等级，用于未来进一步校验项目/租户边界
 * @param authorizedProjectIds PROJECT 范围下物化出的项目集合
 * @param approvalRequired true 表示当前动作仍需审批，worker 不能直接执行副作用
 * @param policyVersion 权限中心当前命中的策略版本，用于和入箱/确认阶段快照比对
 * @param delegated 是否识别为服务账号委托调用
 * @param delegationEvidence 低敏委托证据摘要，不包含工具参数、SQL、prompt 或样本数据
 */
public record AgentAsyncToolPermissionAuthorizationResult(
        Boolean allowed,
        String reason,
        String routeEffect,
        String dataScopeLevel,
        List<Long> authorizedProjectIds,
        Boolean approvalRequired,
        String policyVersion,
        Boolean delegated,
        String delegationEvidence
) {

    public AgentAsyncToolPermissionAuthorizationResult {
        authorizedProjectIds = authorizedProjectIds == null ? List.of() : List.copyOf(authorizedProjectIds);
    }
}
