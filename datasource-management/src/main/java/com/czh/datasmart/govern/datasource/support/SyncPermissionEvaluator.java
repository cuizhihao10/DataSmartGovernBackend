package com.czh.datasmart.govern.datasource.support;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/4/20 22:16
 * @Description DataSmart Govern Backend - SyncPermissionEvaluator.java
 * @Version:1.0.0
 *
 * 同步域本地权限评估器。
 * 它不是最终的统一权限中心，而是 datasource-management 在当前阶段的本地策略适配层。
 *
 * 这层存在的意义有三点：
 * 1. 先把“保护什么资源、执行什么动作、由谁操作”从业务代码里显式抽出来；
 * 2. 在统一 permission-admin 模块尚未接入前，先让本模块具备可读、可演进的权限边界；
 * 3. 把租户范围、负责人范围这些商用产品必须考虑的治理语义提前沉淀为稳定接口。
 *
 * 当前权限判定分成两层：
 * - 第一层：角色是否具备访问某个资源动作的基础资格；
 * - 第二层：在具备基础资格后，是否满足租户范围和 owned-data 范围要求。
 *
 * 这样可以比纯 `if (role == xxx)` 更接近企业级权限模型，也能为后续接统一权限中心减少返工。
 */
@Component
public class SyncPermissionEvaluator {

    /**
     * 本地权限矩阵。
     * 第一层按资源分类，第二层按动作分类，最终映射到允许的角色集合。
     */
    private static final Map<SyncPermissionResource, Map<SyncPermissionAction, EnumSet<ActorRole>>> MATRIX =
            new EnumMap<>(SyncPermissionResource.class);

    static {
        register(SyncPermissionResource.DATASOURCE_METADATA, SyncPermissionAction.VIEW_STRUCTURE,
                ActorRole.PROJECT_OWNER, ActorRole.OPERATOR, ActorRole.AUDITOR,
                ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);
        register(SyncPermissionResource.DATASOURCE_METADATA, SyncPermissionAction.VIEW_SAMPLE,
                ActorRole.OPERATOR, ActorRole.AUDITOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.DATASOURCE_READONLY_QUERY, SyncPermissionAction.EXECUTE_READ_ONLY_QUERY,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR,
                ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);

        register(SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE,
                ActorRole.PROJECT_OWNER, ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);

        register(SyncPermissionResource.SYNC_TASK, SyncPermissionAction.CREATE,
                ActorRole.PROJECT_OWNER, ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR,
                ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);
        register(SyncPermissionResource.SYNC_TASK, SyncPermissionAction.UPDATE_OWNED, ActorRole.PROJECT_OWNER);
        register(SyncPermissionResource.SYNC_TASK, SyncPermissionAction.UPDATE_ANY,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_TASK, SyncPermissionAction.OPERATE_OWNED, ActorRole.PROJECT_OWNER);
        register(SyncPermissionResource.SYNC_TASK, SyncPermissionAction.OPERATE_ANY,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR,
                ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);

        register(SyncPermissionResource.SYNC_APPROVAL, SyncPermissionAction.APPROVE,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);

        register(SyncPermissionResource.SYNC_ADMIN, SyncPermissionAction.ADMIN_OVERRIDE,
                ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);

        register(SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
                ActorRole.OPERATOR, ActorRole.AUDITOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY,
                ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.APPROVE,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);

        register(SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.VIEW_QUEUE_HEALTH,
                ActorRole.OPERATOR, ActorRole.AUDITOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.SCAN_QUEUE_AGING,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);

        register(SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.VIEW_ALERT,
                ActorRole.OPERATOR, ActorRole.AUDITOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.ACKNOWLEDGE_ALERT,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.RESOLVE_ALERT,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT,
                ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);

        register(SyncPermissionResource.SYNC_ALERT_DELIVERY, SyncPermissionAction.VIEW_ALERT,
                ActorRole.OPERATOR, ActorRole.AUDITOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);
        register(SyncPermissionResource.SYNC_ALERT_DELIVERY, SyncPermissionAction.DISPATCH_ALERT,
                ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR);

        register(SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.CLAIM,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);
        register(SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.HEARTBEAT,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);
        register(SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.REPORT_PROGRESS,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);
        register(SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.REPORT_RESULT,
                ActorRole.OPERATOR, ActorRole.TENANT_ADMINISTRATOR, ActorRole.PLATFORM_ADMINISTRATOR, ActorRole.SERVICE_ACCOUNT);
    }

    /**
     * 基础资格判定。
     * 这一层只回答“这个角色理论上能不能做这个动作”，不回答“能不能操作这个具体对象”。
     */
    public boolean canAccess(String actorRole, SyncPermissionResource resource, SyncPermissionAction action) {
        ActorRole role = ActorRole.fromValue(actorRole);
        Map<SyncPermissionAction, EnumSet<ActorRole>> actionMap = MATRIX.get(resource);
        if (actionMap == null) {
            return false;
        }
        EnumSet<ActorRole> allowedRoles = actionMap.get(action);
        return allowedRoles != null && allowedRoles.contains(role);
    }

    /**
     * 带上下文的权限判定。
     * 这一层会继续补租户范围和 owned-data 范围判断。
     */
    public boolean canAccess(SyncPermissionContext context,
                             SyncPermissionResource resource,
                             SyncPermissionAction action) {
        if (context == null || !canAccess(context.getActorRole(), resource, action)) {
            return false;
        }

        ActorRole role = ActorRole.fromValue(context.getActorRole());
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return true;
        }

        if (context.getResourceTenantId() != null
                && context.getActorTenantId() != null
                && !context.getActorTenantId().equals(context.getResourceTenantId())) {
            return false;
        }

        if (action == SyncPermissionAction.UPDATE_OWNED || action == SyncPermissionAction.OPERATE_OWNED) {
            return isOwnedByActor(context);
        }
        return true;
    }

    /**
     * 基础资格断言。
     */
    public void assertAllowed(String actorRole, SyncPermissionResource resource, SyncPermissionAction action) {
        if (!canAccess(actorRole, resource, action)) {
            throw new IllegalStateException("当前角色无权限执行该动作: role="
                    + ActorRole.fromValue(actorRole).name()
                    + ", resource=" + resource.name()
                    + ", action=" + action.name());
        }
    }

    /**
     * 带上下文的权限断言。
     */
    public void assertAllowed(SyncPermissionContext context,
                              SyncPermissionResource resource,
                              SyncPermissionAction action) {
        if (!canAccess(context, resource, action)) {
            throw new IllegalStateException("当前操作者无权限执行该动作: role="
                    + ActorRole.fromValue(context.getActorRole()).name()
                    + ", actorTenantId=" + context.getActorTenantId()
                    + ", resourceTenantId=" + context.getResourceTenantId()
                    + ", resource=" + resource.name()
                    + ", action=" + action.name());
        }
    }

    /**
     * 推导当前操作者在本地同步治理域里的默认数据范围级别。
     * 这不是最终统一权限中心的唯一数据范围模型，但它能先回答三个关键问题：
     * 1. 当前角色默认看到的是自己、租户内，还是平台级数据；
     * 2. 菜单和路由快照在返回给前端时应该如何标注数据边界；
     * 3. 后续接入 permission-admin 时，本地已有的角色语义如何映射到“数据范围”维度。
     */
    public SyncDataScopeLevel resolveDataScopeLevel(String actorRole) {
        ActorRole role = ActorRole.fromValue(actorRole);
        return switch (role) {
            case PLATFORM_ADMINISTRATOR -> SyncDataScopeLevel.PLATFORM;
            case TENANT_ADMINISTRATOR, OPERATOR, AUDITOR, SERVICE_ACCOUNT -> SyncDataScopeLevel.TENANT;
            case PROJECT_OWNER, ORDINARY_USER -> SyncDataScopeLevel.OWNED;
        };
    }

    /**
     * owned-data 判定。
     * 当前把“负责人命中”或“创建人命中”都视为最小 owned 范围，
     * 这样能兼容当前仓库里“负责人”和“创建人”并存的任务治理语义。
     */
    private boolean isOwnedByActor(SyncPermissionContext context) {
        if (context.getActorId() == null) {
            return false;
        }
        return context.getActorId().equals(context.getResourceOwnerId())
                || context.getActorId().equals(context.getResourceCreatedBy());
    }

    private static void register(SyncPermissionResource resource,
                                 SyncPermissionAction action,
                                 ActorRole... roles) {
        EnumSet<ActorRole> allowedRoles = EnumSet.noneOf(ActorRole.class);
        if (roles != null) {
            for (ActorRole role : roles) {
                if (role != null) {
                    allowedRoles.add(role);
                }
            }
        }
        MATRIX.computeIfAbsent(resource, key -> new EnumMap<>(SyncPermissionAction.class))
                .put(action, allowedRoles);
    }
}
