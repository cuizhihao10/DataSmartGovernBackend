/**
 * @Author : Cui
 * @Date: 2026/05/10 19:32
 * @Description DataSmart Govern Backend - ProjectMembershipQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 项目成员授权分页查询条件。
 *
 * <p>项目成员关系是 PROJECT 数据范围的“发卡处”：
 * permission-admin 在权限判定时会把某个 actor 拥有哪些 projectId 物化给 gateway，
 * gateway 再把项目集合透传给 datasource、data-sync、data-quality 等业务模块。
 * 因此管理后台必须能按租户、用户、项目、项目内角色、授权来源和启用状态查询这些关系。
 *
 * @param tenantId 目标租户。平台管理员可为空查询全平台；非平台管理员通常只能查询自身租户。
 * @param actorId 被授权人 ID。这里仍使用 actorId，是为了兼容人类用户、服务账号、机器人账号和外部身份映射。
 * @param projectId 项目 ID。用于定位某个项目下的成员清单。
 * @param workspaceId 工作空间 ID。当前 PROJECT 范围主要按项目收敛，workspaceId 先作为空间级授权扩展面保留。
 * @param projectRole 项目内角色，例如 OWNER、MAINTAINER、VIEWER、MEMBER。
 * @param grantSource 授权来源，例如 MANUAL、IMPORT、IDP_GROUP、APPROVAL。
 * @param enabled 是否启用。禁用关系不会参与授权项目集合物化，但仍保留审计线索。
 * @param current 页码，空值或小于 1 时使用默认第一页。
 * @param size 页大小，服务层会限制最大值，避免一次拉取过多成员记录。
 */
public record ProjectMembershipQueryCriteria(Long tenantId,
                                             Long actorId,
                                             Long projectId,
                                             Long workspaceId,
                                             String projectRole,
                                             String grantSource,
                                             Boolean enabled,
                                             Long current,
                                             Long size) {
}
