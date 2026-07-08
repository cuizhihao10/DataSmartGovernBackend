/**
 * @Author : Cui
 * @Date: 2026/07/08 23:38
 * @Description DataSmart Govern Backend - PermissionProjectController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectDeletionCheckResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectStatusChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectUpdateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectView;
import com.czh.datasmart.govern.permission.service.PermissionProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目主数据控制器。
 *
 * <p>这是去掉用户可见 workspace 后非常关键的控制面入口：
 * 前端应先通过本接口查询当前用户可切换项目，再把选中的 projectId 作为系统上下文传给数据源、同步任务、
 * 数据质量和 Agent 相关接口。这样页面不再要求用户填写租户 ID、项目 ID、工作空间 ID 这些内部字段。</p>
 *
 * <p>路径兼容策略：
 * 1. `/permissions/projects` 适合服务本地直连调试；</p>
 * <p>2. `/api/permission/projects` 适合当前 gateway 外部访问；</p>
 * <p>3. 两者最终都调用同一套 Service，保证授权、审计和分页口径一致。</p>
 */
@RestController
@RequestMapping({"/permissions/projects", "/api/permission/projects"})
@RequiredArgsConstructor
public class PermissionProjectController {

    private final PermissionProjectService projectService;

    /**
     * 分页查询当前操作者可见项目。
     *
     * <p>前端项目切换器建议调用该接口：普通用户和项目负责人只会看到自己被授权的项目；
     * 租户管理员、运营、审计可以看到租户范围内项目；平台管理员可以跨租户查询。</p>
     */
    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<PermissionProjectView>> pageProjects(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long applicationId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean onlyMine,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        PermissionProjectQueryCriteria criteria = new PermissionProjectQueryCriteria(
                tenantId, applicationId, projectId, projectCode, projectName, status, onlyMine, current, size);
        return PlatformApiResponse.success(projectService.pageProjects(criteria,
                actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 查询项目详情。
     *
     * <p>该接口会在 Service 层重新校验当前 actor 是否可见该项目，不能因为知道 projectId 就越权读取。</p>
     */
    @GetMapping("/{projectId}")
    public PlatformApiResponse<PermissionProjectView> getProject(
            @PathVariable Long projectId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(projectService.getProject(projectId,
                actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 创建项目。
     *
     * <p>成功后服务层会自动给负责人写入 OWNER 成员关系。
     * 前端拿到 projectId 后即可切换到该项目，再继续创建源端/目标端数据源和同步任务。</p>
     */
    @PostMapping
    public PlatformApiResponse<PermissionProjectMutationResult> createProject(
            @Valid @RequestBody PermissionProjectCreateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目创建成功",
                projectService.createProject(request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 从 gateway 注入的 Header 构建操作者上下文。
     */
    /**
     * 更新项目基础资料。
     *
     * <p>该接口服务于项目设置页。它只修改项目展示名称、稳定编码、项目类型、负责人快照和低敏描述，
     * 不修改 tenant/application/workspace，也不隐式调整项目成员授权。这样可以把“项目信息维护”和
     * “成员权限治理”拆成两条清晰审计链路。</p>
     */
    @PutMapping("/{projectId}")
    public PlatformApiResponse<PermissionProjectMutationResult> updateProject(
            @PathVariable Long projectId,
            @Valid @RequestBody PermissionProjectUpdateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目更新成功",
                projectService.updateProject(projectId, request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 启用已禁用项目。
     *
     * <p>启用入口只负责 DISABLED -> ACTIVE，不恢复 ARCHIVED 项目。归档项目通常意味着已经完成资源下线和审计留存，
     * 如果未来确实需要恢复，应设计独立的“归档恢复”流程并要求更高权限。</p>
     */
    @PostMapping("/{projectId}/activate")
    public PlatformApiResponse<PermissionProjectMutationResult> activateProject(
            @PathVariable Long projectId,
            @Valid @RequestBody(required = false) PermissionProjectStatusChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目已启用",
                projectService.activateProject(projectId, request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 禁用项目。
     *
     * <p>禁用适合临时冻结项目：例如客户合同暂停、项目存在风险、或管理员需要短期阻止继续创建新资源。
     * 它不删除历史数据，也不强制停止已有同步执行；同步执行侧是否继续运行由 data-sync 的任务状态控制。</p>
     */
    @PostMapping("/{projectId}/disable")
    public PlatformApiResponse<PermissionProjectMutationResult> disableProject(
            @PathVariable Long projectId,
            @Valid @RequestBody(required = false) PermissionProjectStatusChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目已禁用",
                projectService.disableProject(projectId, request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 删除前占用检查。
     *
     * <p>前端点击删除按钮时建议先调用该接口，展示“还有多少数据源/模板/任务需要先处理”。
     * 该接口不返回具体资源名称，避免 permission-admin 越权暴露其他微服务业务明细。</p>
     */
    @GetMapping("/{projectId}/deletion-check")
    public PlatformApiResponse<PermissionProjectDeletionCheckResponse> checkProjectDeletion(
            @PathVariable Long projectId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(projectService.checkProjectDeletion(projectId,
                actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 归档项目。
     *
     * <p>归档会把项目从默认列表隐藏，适合项目完成、客户退租前资源清理完毕、或长期不再使用的场景。
     * 归档前必须没有活动数据源、启用模板和非归档任务。</p>
     */
    @PostMapping("/{projectId}/archive")
    public PlatformApiResponse<PermissionProjectMutationResult> archiveProject(
            @PathVariable Long projectId,
            @Valid @RequestBody(required = false) PermissionProjectStatusChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目已归档",
                projectService.archiveProject(projectId, request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 删除项目。
     *
     * <p>当前删除不是物理 DELETE，而是执行归档式删除。原因是项目 ID 已经进入数据源、同步任务、审计和 Agent 上下文，
     * 物理删除会破坏历史可追溯性。前端仍可以把它呈现为“删除到不可见”。</p>
     */
    @DeleteMapping("/{projectId}")
    public PlatformApiResponse<PermissionProjectMutationResult> deleteProject(
            @PathVariable Long projectId,
            @Valid @RequestBody(required = false) PermissionProjectStatusChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("项目已删除",
                projectService.deleteProject(projectId, request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}
