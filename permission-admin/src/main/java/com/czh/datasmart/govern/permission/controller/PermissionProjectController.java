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
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectView;
import com.czh.datasmart.govern.permission.service.PermissionProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}
