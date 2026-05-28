/**
 * @Author : Cui
 * @Date: 2026/05/10 19:32
 * @Description DataSmart Govern Backend - ProjectMembershipMutationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 项目成员授权变更结果。
 *
 * <p>管理后台通常需要在操作后立即展示“哪条成员关系被创建/更新/禁用”，
 * 因此返回核心定位字段和结果消息，而不是只返回布尔值。
 *
 * @param membershipId 成员关系主键。
 * @param tenantId 租户 ID。
 * @param actorId 被授权 actor ID。
 * @param projectId 项目 ID。
 * @param enabled 变更后的启用状态。
 * @param message 面向管理后台的简短结果说明。
 */
public record ProjectMembershipMutationResult(Long membershipId,
                                              Long tenantId,
                                              Long actorId,
                                              Long projectId,
                                              Boolean enabled,
                                              String message) {
}
