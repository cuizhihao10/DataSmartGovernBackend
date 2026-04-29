/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAdminController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionMatrixView;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyEnabledRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyMutationRequest;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionMenu;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.service.PermissionAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * 权限管理控制器。
 *
 * <p>Controller 的职责是定义对外 API 契约，不直接承载复杂权限规则。
 * 当前接口面向三类调用方：
 * 1. 管理后台：查询角色、菜单、路由策略、数据范围和权限矩阵；
 * 2. gateway：调用 evaluate 做路由级访问判定；
 * 3. 业务服务：未来可调用数据范围策略，决定某个角色能访问哪些业务数据。
 *
 * <p>当前同时支持 /permissions 和 /api/permission 两组前缀：
 * 1. /permissions 适合模块本地直连调试；
 * 2. /api/permission 适合当前网关尚未统一 RewritePath 之前的转发访问。
 *
 * <p>后续建议在 gateway 统一补齐路径重写规则，把外部 /api/permission/** 稳定转换为后端 /permissions/**，
 * 到那时可以逐步收敛掉双前缀兼容。
 */
@RestController
@RequestMapping({"/permissions", "/api/permission"})
@RequiredArgsConstructor
public class PermissionAdminController {

    private final PermissionAdminService permissionAdminService;

    /**
     * 查询角色列表。
     *
     * @param tenantId 租户 ID；为空或 0 表示只看平台默认角色，非 0 表示平台默认角色 + 租户角色。
     * @param traceId 网关传入的链路追踪 ID。
     */
    @GetMapping("/roles")
    public PlatformApiResponse<List<PermissionRole>> listRoles(
            @RequestParam(required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(permissionAdminService.listRoles(tenantId), traceId);
    }

    /**
     * 查询角色可见菜单。
     *
     * <p>这个接口主要服务管理后台菜单渲染，也能帮助我们验证“角色 -> 菜单”的矩阵是否符合预期。
     */
    @GetMapping("/menus")
    public PlatformApiResponse<List<PermissionMenu>> listMenus(
            @RequestParam(required = false) Long tenantId,
            @RequestParam String roleCode,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(permissionAdminService.listMenus(tenantId, roleCode), traceId);
    }

    /**
     * 查询角色路由策略。
     *
     * <p>gateway 后续可以将这些策略缓存到本地或 Redis，用于低延迟路由授权。
     */
    @GetMapping("/route-policies")
    public PlatformApiResponse<List<PermissionRoutePolicy>> listRoutePolicies(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false, defaultValue = "false") Boolean includeDisabled,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(permissionAdminService.listRoutePolicies(tenantId, roleCode, includeDisabled), traceId);
    }

    /**
     * 创建路由策略。
     *
     * <p>这是权限中心从“只读种子数据”走向“可运营权限管理”的关键接口。
     * 管理后台可以通过它新增某个角色对某段 API 的 ALLOW 或 DENY 策略。
     *
     * <p>安全边界：
     * 1. Controller 只负责接收请求和提取操作者上下文；
     * 2. Service 继续校验操作者是否有权管理目标租户；
     * 3. 所有创建都会写入权限审计记录；
     * 4. 后续应在成功后发布策略变更事件，让 gateway 授权缓存自动失效。
     */
    @PostMapping("/route-policies")
    public PlatformApiResponse<PermissionRoutePolicy> createRoutePolicy(
            @Valid @RequestBody PermissionRoutePolicyMutationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        PermissionActorContext actorContext = new PermissionActorContext(actorTenantId, actorId, actorRole, traceId);
        return PlatformApiResponse.success("路由策略创建成功",
                permissionAdminService.createRoutePolicy(request, actorContext),
                traceId);
    }

    /**
     * 更新路由策略。
     *
     * <p>更新路由策略可能扩大权限，也可能收紧权限，因此它和创建一样属于高风险管理动作。
     * 当前第一版直接更新；未来更商业化的方式是对高风险变更进入审批流，
     * 例如普通 ALLOW 策略可直接变更，跨租户策略、ANY 方法、/api/** 范围策略需要审批。
     */
    @PutMapping("/route-policies/{policyId}")
    public PlatformApiResponse<PermissionRoutePolicy> updateRoutePolicy(
            @PathVariable Long policyId,
            @Valid @RequestBody PermissionRoutePolicyMutationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        PermissionActorContext actorContext = new PermissionActorContext(actorTenantId, actorId, actorRole, traceId);
        return PlatformApiResponse.success("路由策略更新成功",
                permissionAdminService.updateRoutePolicy(policyId, request, actorContext),
                traceId);
    }

    /**
     * 启用或禁用路由策略。
     *
     * <p>当前不提供物理删除接口，原因是权限策略需要可追溯、可恢复。
     * 禁用可以让策略立即不再参与判定，同时保留历史记录，适合商业系统审计和事故复盘。
     */
    @PatchMapping("/route-policies/{policyId}/enabled")
    public PlatformApiResponse<PermissionRoutePolicy> changeRoutePolicyEnabled(
            @PathVariable Long policyId,
            @Valid @RequestBody PermissionRoutePolicyEnabledRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        PermissionActorContext actorContext = new PermissionActorContext(actorTenantId, actorId, actorRole, traceId);
        return PlatformApiResponse.success("路由策略启停状态已更新",
                permissionAdminService.changeRoutePolicyEnabled(policyId, request, actorContext),
                traceId);
    }

    /**
     * 查询角色数据范围策略。
     *
     * <p>业务模块最终会根据数据范围策略追加查询条件，例如 tenant_id、owner_id、project_id 等。
     */
    @GetMapping("/data-scopes")
    public PlatformApiResponse<List<PermissionDataScopePolicy>> listDataScopes(
            @RequestParam(required = false) Long tenantId,
            @RequestParam String roleCode,
            @RequestParam(required = false) String resourceType,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(permissionAdminService.listDataScopePolicies(tenantId, roleCode, resourceType), traceId);
    }

    /**
     * 查询权限矩阵总览。
     *
     * <p>这是当前阶段最适合学习和验收的接口之一：
     * 它能一次性看到角色、菜单、路由策略和数据范围，为后续补管理后台或联调网关提供清晰依据。
     */
    @GetMapping("/matrix")
    public PlatformApiResponse<PermissionMatrixView> loadMatrix(
            @RequestParam(required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(permissionAdminService.loadMatrix(tenantId), traceId);
    }

    /**
     * 权限判定接口。
     *
     * <p>这是 permission-admin 面向 gateway 和业务服务的关键接口。
     * 调用方提交角色、方法、路径、资源类型和动作，权限中心返回是否允许、命中策略、数据范围和审批要求。
     */
    @PostMapping("/evaluate")
    public PlatformApiResponse<PermissionDecisionResult> evaluate(
            @Valid @RequestBody PermissionDecisionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("权限判定完成", permissionAdminService.evaluate(request, traceId), traceId);
    }
}
