/**
 * @Author : Cui
 * @Date: 2026/05/09 20:36
 * @Description DataSmart Govern Backend - SyncDataVisibility.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * data-sync 查询数据可见范围。
 *
 * <p>这个 record 是把 permission-admin 的“数据范围策略”翻译成 data-sync 可以直接落地的查询条件。
 * 它不会暴露到 Controller，也不会写入数据库，只在 Service 组装 MyBatis 查询条件时使用。
 *
 * @param tenantId 最终租户范围；为空表示允许跨租户查询，通常只应授予平台管理员
 * @param projectId 最终项目范围；为空表示当前阶段不按项目收敛
 * @param authorizedProjectIds PROJECT 范围下 permission-admin 物化出的项目集合；为空集合表示无授权项目或未启用物化链路
 * @param workspaceId 最终工作空间范围；为空表示当前阶段不按工作空间收敛
 * @param projectScopeEnforced 是否必须按 authorizedProjectIds 强制收敛；只有 gateway 明确透传 PROJECT 范围时才为 true
 * @param selfOnly 是否只能访问自己创建、负责或被分派的资源
 * @param scopeLevel 本次采用的数据范围级别，便于调试和注释说明
 * @param scopeExpression permission-admin 返回的原始范围表达式，当前保留用于审计和后续 DSL 演进
 * @param approvalRequired 当前访问是否需要审批；本轮查询链路只携带上下文，后续高风险写操作可用它触发审批
 */
public record SyncDataVisibility(Long tenantId,
                                 Long projectId,
                                 List<Long> authorizedProjectIds,
                                 Long workspaceId,
                                 boolean projectScopeEnforced,
                                 boolean selfOnly,
                                 String scopeLevel,
                                 String scopeExpression,
                                 boolean approvalRequired) {
}
