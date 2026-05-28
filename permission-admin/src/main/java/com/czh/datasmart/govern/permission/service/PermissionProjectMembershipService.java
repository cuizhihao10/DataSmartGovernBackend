/**
 * @Author : Cui
 * @Date: 2026/05/10 19:34
 * @Description DataSmart Govern Backend - PermissionProjectMembershipService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipBatchUpsertRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipStateChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipUpdateRequest;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;

import java.util.List;

/**
 * 项目成员授权管理服务。
 *
 * <p>该服务是 PROJECT 数据范围的管理闭环：
 * 业务模块现在已经能消费 `X-DataSmart-Authorized-Project-Ids`，
 * 但这些项目 ID 必须来自一个可管理、可审计、可查询、可批量导入的权限事实表。
 * 本服务正是对 `permission_project_membership` 的控制面封装。
 *
 * <p>服务边界：
 * 1. 只管理“actor 与 project/workspace 的授权关系”；
 * 2. 不直接管理项目基础资料，项目名称、项目状态、项目负责人画像应属于未来 project-service 或 tenant-service；
 * 3. 不直接下发 gateway 缓存事件，当前权限判定会实时查询成员表；后续如果引入授权快照缓存，再在这里补 outbox 事件。
 */
public interface PermissionProjectMembershipService {

    /**
     * 分页查询项目成员授权。
     *
     * @param criteria 查询条件。
     * @param actorContext 当前操作者上下文，用于控制租户边界和项目负责人可见范围。
     * @return 项目成员授权分页。
     */
    PlatformPageResponse<PermissionProjectMembership> pageProjectMemberships(ProjectMembershipQueryCriteria criteria,
                                                                             PermissionActorContext actorContext);

    /**
     * 查询项目成员授权详情。
     *
     * @param membershipId 成员关系主键。
     * @param actorContext 当前操作者上下文。
     * @return 成员关系详情。
     */
    PermissionProjectMembership getProjectMembership(Long membershipId, PermissionActorContext actorContext);

    /**
     * 新增或幂等更新项目成员授权。
     *
     * @param request 新增请求。
     * @param actorContext 当前操作者上下文。
     * @return 变更结果。
     */
    ProjectMembershipMutationResult grantOrUpdateProjectMembership(ProjectMembershipCreateRequest request,
                                                                   PermissionActorContext actorContext);

    /**
     * 批量新增或幂等更新项目成员授权。
     *
     * @param request 批量请求。
     * @param actorContext 当前操作者上下文。
     * @return 每条成员关系的变更结果。
     */
    List<ProjectMembershipMutationResult> batchGrantOrUpdateProjectMemberships(ProjectMembershipBatchUpsertRequest request,
                                                                               PermissionActorContext actorContext);

    /**
     * 更新项目成员授权的可变属性。
     *
     * @param membershipId 成员关系主键。
     * @param request 更新请求。
     * @param actorContext 当前操作者上下文。
     * @return 变更结果。
     */
    ProjectMembershipMutationResult updateProjectMembership(Long membershipId,
                                                            ProjectMembershipUpdateRequest request,
                                                            PermissionActorContext actorContext);

    /**
     * 启用项目成员授权。
     *
     * @param membershipId 成员关系主键。
     * @param request 状态变更原因。
     * @param actorContext 当前操作者上下文。
     * @return 变更结果。
     */
    ProjectMembershipMutationResult enableProjectMembership(Long membershipId,
                                                            ProjectMembershipStateChangeRequest request,
                                                            PermissionActorContext actorContext);

    /**
     * 禁用项目成员授权。
     *
     * @param membershipId 成员关系主键。
     * @param request 状态变更原因。
     * @param actorContext 当前操作者上下文。
     * @return 变更结果。
     */
    ProjectMembershipMutationResult disableProjectMembership(Long membershipId,
                                                             ProjectMembershipStateChangeRequest request,
                                                             PermissionActorContext actorContext);
}
