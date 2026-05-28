/**
 * @Author : Cui
 * @Date: 2026/05/10 19:35
 * @Description DataSmart Govern Backend - PermissionProjectMembershipController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipBatchUpsertRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipStateChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipUpdateRequest;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.service.PermissionProjectMembershipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目成员授权管理控制器。
 *
 * <p>这是 PROJECT 数据范围的管理入口。
 * datasource-management、data-sync、data-quality 等模块只负责消费“授权项目集合”，
 * 但授权项目集合的来源必须在 permission-admin 中可维护、可审计、可查询。
 *
 * <p>路由设计：
 * 1. `/permissions/project-memberships` 用于服务本地调试；
 * 2. `/api/permission/project-memberships` 用于兼容当前 gateway 外部路径；
 * 3. 网关侧会把 `/api/permission/**` 改写到 `/permissions/**`，保留双路径能降低迁移期集成风险。
 */
@RestController
@RequestMapping({"/permissions/project-memberships", "/api/permission/project-memberships"})
@RequiredArgsConstructor
public class PermissionProjectMembershipController {

    private final PermissionProjectMembershipService projectMembershipService;

    /**
     * 分页查询项目成员授权。
     *
     * <p>典型后台场景：
     * 1. 租户管理员按项目查看成员列表；
     * 2. 审计员按 actorId 追踪某人为什么能访问某些项目；
     * 3. 运营人员排查 PROJECT 数据范围为空导致用户看不到数据的问题；
     * 4. 平台管理员跨租户抽样检查授权来源和禁用状态。
     */
    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<PermissionProjectMembership>> pageProjectMemberships(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String projectRole,
            @RequestParam(required = false) String grantSource,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        ProjectMembershipQueryCriteria criteria = new ProjectMembershipQueryCriteria(
                tenantId, actorId, projectId, workspaceId, projectRole, grantSource, enabled, current, size);
        return PlatformApiResponse.success(projectMembershipService.pageProjectMemberships(criteria,
                actorContext(actorTenantId, requestActorId, requestActorRole, traceId)), traceId);
    }

    /**
     * 查询项目成员授权详情。
     *
     * <p>详情接口会在服务层重新校验租户和项目范围，不能因为调用者知道主键就绕过列表过滤。
     */
    @GetMapping("/{membershipId}")
    public PlatformApiResponse<PermissionProjectMembership> getProjectMembership(
            @PathVariable Long membershipId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(projectMembershipService.getProjectMembership(membershipId,
                actorContext(actorTenantId, requestActorId, requestActorRole, traceId)), traceId);
    }

    /**
     * 新增或幂等更新项目成员授权。
     *
     * <p>同一个 tenantId + actorId + projectId 已存在时会更新，而不是报唯一键冲突。
     * 这使它可以安全承接组织同步重试、Excel 导入重跑和管理后台重复提交。
     */
    @PostMapping
    public PlatformApiResponse<ProjectMembershipMutationResult> grantOrUpdateProjectMembership(
            @Valid @RequestBody ProjectMembershipCreateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目成员授权已保存",
                projectMembershipService.grantOrUpdateProjectMembership(request,
                        actorContext(actorTenantId, requestActorId, requestActorRole, traceId)),
                traceId);
    }

    /**
     * 批量新增或幂等更新项目成员授权。
     *
     * <p>该接口面向真实商业场景中的组织同步、项目台账导入、审批流批量授权等场景。
     * 当前采用同步事务和 200 条上限；如果未来客户项目规模更大，应升级为异步导入任务并提供导入结果文件。
     */
    @PostMapping("/batch-upsert")
    public PlatformApiResponse<List<ProjectMembershipMutationResult>> batchGrantOrUpdateProjectMemberships(
            @Valid @RequestBody ProjectMembershipBatchUpsertRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目成员授权批量保存完成",
                projectMembershipService.batchGrantOrUpdateProjectMemberships(request,
                        actorContext(actorTenantId, requestActorId, requestActorRole, traceId)),
                traceId);
    }

    /**
     * 更新项目成员授权。
     *
     * <p>该接口只修改角色、空间、来源和启用状态，不修改 tenantId/actorId/projectId。
     * 这种约束能保证历史审计记录始终指向同一条授权关系。
     */
    @PutMapping("/{membershipId}")
    public PlatformApiResponse<ProjectMembershipMutationResult> updateProjectMembership(
            @PathVariable Long membershipId,
            @Valid @RequestBody ProjectMembershipUpdateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目成员授权已更新",
                projectMembershipService.updateProjectMembership(membershipId, request,
                        actorContext(actorTenantId, requestActorId, requestActorRole, traceId)),
                traceId);
    }

    /**
     * 启用项目成员授权。
     *
     * <p>启用后，该关系会重新参与授权项目集合物化。
     */
    @PostMapping("/{membershipId}/enable")
    public PlatformApiResponse<ProjectMembershipMutationResult> enableProjectMembership(
            @PathVariable Long membershipId,
            @Valid @RequestBody(required = false) ProjectMembershipStateChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目成员授权已启用",
                projectMembershipService.enableProjectMembership(membershipId, request,
                        actorContext(actorTenantId, requestActorId, requestActorRole, traceId)),
                traceId);
    }

    /**
     * 禁用项目成员授权。
     *
     * <p>禁用不会删除记录，因此审计员仍能看到“这个人曾经被授权过该项目”。
     */
    @PostMapping("/{membershipId}/disable")
    public PlatformApiResponse<ProjectMembershipMutationResult> disableProjectMembership(
            @PathVariable Long membershipId,
            @Valid @RequestBody(required = false) ProjectMembershipStateChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目成员授权已禁用",
                projectMembershipService.disableProjectMembership(membershipId, request,
                        actorContext(actorTenantId, requestActorId, requestActorRole, traceId)),
                traceId);
    }

    /**
     * 从 gateway 注入的 Header 构建操作者上下文。
     *
     * <p>Controller 只做上下文组装，真正的角色、租户和项目范围校验全部放在 service 层，
     * 这样未来如果从 REST 切换到消息或内部调用，也可以复用同一套业务校验。
     */
    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}
