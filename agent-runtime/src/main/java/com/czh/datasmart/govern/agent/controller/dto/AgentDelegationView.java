/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentDelegationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面向用户与审计页面的低敏 Agent 委托范围。
 *
 * <p>委托表示“用户允许指定 Agent 在本次会话内尝试哪些动作”，不是新的 RBAC 角色，也不能突破用户原有权限。
 * 有效权限始终取用户权限、项目资源授权、工具治理策略和本委托范围的交集。</p>
 *
 * @param delegationId 本次委托的稳定审计 ID
 * @param agentId 接受委托的 Agent 身份，不等同于登录用户
 * @param userActorId 发出委托的登录用户 ID
 * @param tenantId 委托所属租户，禁止跨租户复用
 * @param projectId 委托所属项目，禁止切换项目后继续复用
 * @param toolCodes 委托允许触达的工具代码集合
 * @param actions 从工具绑定中归并出的允许动作集合
 * @param resourceScopes 目标服务与资源 ID 范围，例如 datasource-management:23
 * @param status 委托状态，例如 ACTIVE 或 REVOKED
 * @param issuedAt 委托签发时间
 * @param expiresAt 委托过期时间；为空表示仍由会话生命周期和撤销状态控制
 * @param revokedAt 委托撤销时间；未撤销时为空
 */
public record AgentDelegationView(String delegationId,
                                  String agentId,
                                  String userActorId,
                                  Long tenantId,
                                  Long projectId,
                                  List<String> toolCodes,
                                  List<String> actions,
                                  List<String> resourceScopes,
                                  String status,
                                  LocalDateTime issuedAt,
                                  LocalDateTime expiresAt,
                                  LocalDateTime revokedAt) {
}
