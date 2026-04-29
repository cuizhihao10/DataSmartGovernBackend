package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.config.SyncPermissionPolicyProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncMenuPolicyView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionPolicySnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncRoutePolicyView;
import com.czh.datasmart.govern.datasource.service.SyncPermissionBindingService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionPolicyService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncAdminMenu;
import com.czh.datasmart.govern.datasource.support.SyncAdminRoutePolicy;
import com.czh.datasmart.govern.datasource.support.SyncDataScopeLevel;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionBindingType;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/4/24 11:12
 * @Description DataSmart Govern Backend - SyncPermissionPolicyServiceImpl.java
 * @Version:1.0.0
 *
 * 本地权限策略快照服务实现。
 * 这一层的职责是把“数据库绑定 + 配置绑定 + 代码默认推导”三层语义组合成一个最终快照。
 *
 * 当前解析优先级明确为：
 * 1. 租户级数据库绑定；
 * 2. 平台全局数据库绑定；
 * 3. application.yml 中的配置覆盖；
 * 4. 代码默认推导。
 *
 * 这样设计的原因是：
 * - 数据库绑定更接近真实后台治理操作；
 * - 配置绑定更适合开发期默认值和本地演示；
 * - 代码默认推导则保证模块在完全没有外部配置时仍能开箱即用。
 */
@Service
@RequiredArgsConstructor
public class SyncPermissionPolicyServiceImpl implements SyncPermissionPolicyService {

    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final SyncPermissionPolicyProperties syncPermissionPolicyProperties;
    private final SyncPermissionBindingService syncPermissionBindingService;

    @Override
    public SyncPermissionPolicySnapshot buildSnapshot(Long actorId,
                                                      String actorRole,
                                                      Long actorTenantId,
                                                      Long targetTenantId) {
        SyncPermissionContext context = buildPermissionContext(actorId, actorRole, actorTenantId, targetTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY);

        Long resolvedTargetTenantId = resolveTargetTenantId(actorRole, actorTenantId, targetTenantId);
        SyncDataScopeLevel dataScopeLevel = resolveDataScopeLevel(actorRole, resolvedTargetTenantId);

        SyncPermissionPolicySnapshot snapshot = new SyncPermissionPolicySnapshot();
        snapshot.setActorId(actorId);
        snapshot.setActorRole(ActorRole.fromValue(actorRole).name());
        snapshot.setActorTenantId(actorTenantId);
        snapshot.setTargetTenantId(resolvedTargetTenantId);
        snapshot.setDataScopeLevel(dataScopeLevel.name());
        snapshot.setMenus(buildMenuViews(actorRole, actorTenantId, resolvedTargetTenantId));
        snapshot.setRoutes(buildRouteViews(actorRole, actorTenantId, resolvedTargetTenantId));
        snapshot.setAdminOnlyActions(resolveAdminOnlyActions(actorRole, resolvedTargetTenantId));
        snapshot.setApprovalRecommendedActions(resolveApprovalRecommendedActions(actorRole, resolvedTargetTenantId));
        snapshot.setSummary(buildSummary(actorRole, dataScopeLevel, resolvedTargetTenantId));
        return snapshot;
    }

    /**
     * 构建菜单视图。
     * 菜单可见性优先读数据库绑定；若没有显式绑定，再回退到配置或默认路由推导。
     */
    private List<SyncMenuPolicyView> buildMenuViews(String actorRole, Long actorTenantId, Long targetTenantId) {
        List<String> effectiveMenus = resolveEffectiveBindings(targetTenantId, actorRole,
                SyncPermissionBindingType.MENU, syncPermissionPolicyProperties.getRoleMenuBindings());
        return Arrays.stream(SyncAdminMenu.values())
                .map(menu -> {
                    SyncMenuPolicyView view = new SyncMenuPolicyView();
                    view.setMenuCode(menu.getCode());
                    view.setMenuTitle(menu.getTitle());
                    view.setRoutePath(menu.getRoutePath());
                    view.setDescription(menu.getDescription());
                    view.setVisible(resolveMenuVisibility(menu, effectiveMenus, actorRole, actorTenantId, targetTenantId));
                    return view;
                })
                .toList();
    }

    /**
     * 构建路由视图。
     * 路由访问与菜单一样，也是“数据库绑定优先，其次配置，最后默认推导”。
     */
    private List<SyncRoutePolicyView> buildRouteViews(String actorRole, Long actorTenantId, Long targetTenantId) {
        List<String> effectiveRoutes = resolveEffectiveBindings(targetTenantId, actorRole,
                SyncPermissionBindingType.ROUTE, syncPermissionPolicyProperties.getRoleRouteBindings());
        return Arrays.stream(SyncAdminRoutePolicy.values())
                .map(policy -> {
                    SyncRoutePolicyView view = new SyncRoutePolicyView();
                    view.setHttpMethod(policy.getHttpMethod());
                    view.setPath(policy.getPath());
                    view.setResource(policy.getResource().name());
                    view.setAction(policy.getAction().name());
                    view.setMenuCode(policy.getMenu().getCode());
                    view.setRecommendedScope(policy.getRecommendedScope().name());
                    view.setAccessible(resolveRouteAccess(policy, effectiveRoutes, actorRole, actorTenantId, targetTenantId));
                    view.setDescription(policy.getDescription());
                    return view;
                })
                .toList();
    }

    /**
     * 显式菜单绑定存在时，以绑定结果为准；否则使用路由推导。
     */
    private boolean resolveMenuVisibility(SyncAdminMenu menu,
                                          List<String> configuredMenus,
                                          String actorRole,
                                          Long actorTenantId,
                                          Long targetTenantId) {
        if (!configuredMenus.isEmpty()) {
            return configuredMenus.stream().map(this::normalize).anyMatch(normalize(menu.getCode())::equals);
        }
        return hasAnyAccessibleRoute(menu, actorRole, actorTenantId, targetTenantId);
    }

    /**
     * 显式路由绑定存在时，以绑定结果为准；否则回退到权限评估器默认判断。
     */
    private boolean resolveRouteAccess(SyncAdminRoutePolicy policy,
                                       List<String> configuredRoutes,
                                       String actorRole,
                                       Long actorTenantId,
                                       Long targetTenantId) {
        if (!configuredRoutes.isEmpty()) {
            return configuredRoutes.stream()
                    .map(this::normalize)
                    .anyMatch(normalize(policy.name())::equals);
        }
        return syncPermissionEvaluator.canAccess(
                buildPermissionContext(null, actorRole, actorTenantId, targetTenantId),
                policy.getResource(),
                policy.getAction());
    }

    /**
     * 判断某个菜单下是否至少存在一条默认可访问路由。
     */
    private boolean hasAnyAccessibleRoute(SyncAdminMenu menu,
                                          String actorRole,
                                          Long actorTenantId,
                                          Long targetTenantId) {
        return Arrays.stream(SyncAdminRoutePolicy.values())
                .filter(policy -> policy.getMenu() == menu)
                .anyMatch(policy -> syncPermissionEvaluator.canAccess(
                        buildPermissionContext(null, actorRole, actorTenantId, targetTenantId),
                        policy.getResource(),
                        policy.getAction()));
    }

    /**
     * 构建权限上下文。
     */
    private SyncPermissionContext buildPermissionContext(Long actorId,
                                                         String actorRole,
                                                         Long actorTenantId,
                                                         Long targetTenantId) {
        return SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(resolveTargetTenantId(actorRole, actorTenantId, targetTenantId))
                .build();
    }

    /**
     * 解析目标租户视角。
     * 平台管理员允许跨租户查看；其他角色只能查看自己租户或默认落到自己租户。
     */
    private Long resolveTargetTenantId(String actorRole, Long actorTenantId, Long targetTenantId) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return targetTenantId;
        }
        if (targetTenantId == null) {
            return actorTenantId;
        }
        if (actorTenantId != null && !actorTenantId.equals(targetTenantId)) {
            throw new IllegalStateException("当前角色不能跨租户查看本地权限策略快照");
        }
        return targetTenantId;
    }

    /**
     * 数据范围解析。
     * 单值绑定优先取数据库生效值，没有时再走配置和默认推导。
     */
    private SyncDataScopeLevel resolveDataScopeLevel(String actorRole, Long targetTenantId) {
        List<String> bindingValues = syncPermissionBindingService.resolveEffectiveBindingValues(
                targetTenantId, actorRole, SyncPermissionBindingType.DATA_SCOPE);
        if (!bindingValues.isEmpty()) {
            return SyncDataScopeLevel.valueOf(bindingValues.getFirst().trim().toUpperCase(Locale.ROOT));
        }
        String configuredScope = resolveSingleBinding(syncPermissionPolicyProperties.getRoleDataScopeBindings(), actorRole);
        if (configuredScope == null || configuredScope.isBlank()) {
            return syncPermissionEvaluator.resolveDataScopeLevel(actorRole);
        }
        return SyncDataScopeLevel.valueOf(configuredScope.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 管理员专属动作清单解析。
     */
    private List<String> resolveAdminOnlyActions(String actorRole, Long targetTenantId) {
        List<String> bindingValues = syncPermissionBindingService.resolveEffectiveBindingValues(
                targetTenantId, actorRole, SyncPermissionBindingType.ADMIN_ONLY_ACTION);
        if (!bindingValues.isEmpty()) {
            return bindingValues;
        }
        if (syncPermissionPolicyProperties.getAdminOnlyActions() != null
                && !syncPermissionPolicyProperties.getAdminOnlyActions().isEmpty()) {
            return syncPermissionPolicyProperties.getAdminOnlyActions();
        }
        return List.of(
                "FORCE_RETRY_TASK",
                "FORCE_CANCEL_TASK",
                "OVERRIDE_TASK_PRIORITY",
                "OVERRIDE_TASK_TIMEOUT",
                "DISPATCH_RETRYABLE_ALERTS",
                "REQUEUE_DEAD_LETTER_ALERT",
                "VIEW_CROSS_TENANT_POLICIES"
        );
    }

    /**
     * 建议走审批的动作清单解析。
     */
    private List<String> resolveApprovalRecommendedActions(String actorRole, Long targetTenantId) {
        List<String> bindingValues = syncPermissionBindingService.resolveEffectiveBindingValues(
                targetTenantId, actorRole, SyncPermissionBindingType.APPROVAL_RECOMMENDED_ACTION);
        if (!bindingValues.isEmpty()) {
            return bindingValues;
        }
        if (syncPermissionPolicyProperties.getApprovalRecommendedActions() != null
                && !syncPermissionPolicyProperties.getApprovalRecommendedActions().isEmpty()) {
            return syncPermissionPolicyProperties.getApprovalRecommendedActions();
        }
        return List.of(
                "APPROVE_SYNC_TASK",
                "DISABLE_HIGH_IMPACT_CONNECTOR",
                "CHANGE_GLOBAL_CONCURRENCY_POLICY",
                "EXECUTE_CROSS_TENANT_ADMIN_OVERRIDE"
        );
    }

    /**
     * 解析多值绑定。
     * 优先采用数据库生效值，没有时退回配置绑定。
     */
    private List<String> resolveEffectiveBindings(Long targetTenantId,
                                                  String actorRole,
                                                  SyncPermissionBindingType bindingType,
                                                  Map<String, List<String>> fallbackBindings) {
        List<String> databaseBindings = syncPermissionBindingService.resolveEffectiveBindingValues(
                targetTenantId, actorRole, bindingType);
        if (!databaseBindings.isEmpty()) {
            return databaseBindings;
        }
        return resolveRoleBinding(fallbackBindings, actorRole);
    }

    /**
     * 从角色绑定表里取列表型配置绑定。
     */
    private List<String> resolveRoleBinding(Map<String, List<String>> bindings, String actorRole) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        String exactKey = ActorRole.fromValue(actorRole).name();
        if (bindings.containsKey(exactKey) && bindings.get(exactKey) != null) {
            return bindings.get(exactKey);
        }
        return bindings.entrySet().stream()
                .filter(entry -> normalize(entry.getKey()).equals(normalize(actorRole)))
                .map(Map.Entry::getValue)
                .filter(value -> value != null)
                .findFirst()
                .orElse(List.of());
    }

    /**
     * 从角色绑定表里取单值配置绑定。
     */
    private String resolveSingleBinding(Map<String, String> bindings, String actorRole) {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        String exactKey = ActorRole.fromValue(actorRole).name();
        if (bindings.containsKey(exactKey)) {
            return bindings.get(exactKey);
        }
        return bindings.entrySet().stream()
                .filter(entry -> normalize(entry.getKey()).equals(normalize(actorRole)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 大小写归一化。
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 生成快照摘要。
     */
    private String buildSummary(String actorRole, SyncDataScopeLevel dataScopeLevel, Long targetTenantId) {
        return "当前角色="
                + ActorRole.fromValue(actorRole).name()
                + "，默认数据范围="
                + dataScopeLevel.name()
                + "，目标租户="
                + (targetTenantId == null ? "PLATFORM" : targetTenantId)
                + "。该快照按照“数据库绑定 -> application.yml -> 代码默认推导”的顺序解析权限语义。";
    }
}
