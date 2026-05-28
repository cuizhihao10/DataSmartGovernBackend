package com.czh.datasmart.govern.permission.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.permission.controller.dto.PermissionMatrixView;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionMenu;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.entity.PermissionRoleMenuBinding;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.mapper.PermissionDataScopePolicyMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionMenuMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMenuBindingMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoutePolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeCode;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.platformAndTenantIds;

/**
 * @Author : Cui
 * @Date: 2026/05/06 00:20
 * @Description DataSmart Govern Backend - PermissionQuerySupport.java
 * @Version:1.0.0
 *
 * 权限事实查询支持组件。
 *
 * <p>这里承载的是“权限事实”的读取：角色、菜单、路由策略、数据范围策略和权限矩阵。
 * 它不负责判断某次访问是否允许，也不负责修改策略。
 * 这种拆分让权限中心形成三条清晰能力线：
 * 查询事实、评估决策、变更策略。
 */
@Component
@RequiredArgsConstructor
public class PermissionQuerySupport {

    private final PermissionRoleMapper roleMapper;
    private final PermissionMenuMapper menuMapper;
    private final PermissionRoleMenuBindingMapper roleMenuBindingMapper;
    private final PermissionRoutePolicyMapper routePolicyMapper;
    private final PermissionDataScopePolicyMapper dataScopePolicyMapper;
    private final PermissionProjectMembershipMapper projectMembershipMapper;
    private final PermissionPolicyFactCache policyFactCache;

    /**
     * 查询租户可用角色。
     *
     * <p>平台默认角色和当前租户角色一起返回，支持“全局标准角色 + 租户自定义角色”的商业化模型。
     */
    public List<PermissionRole> listRoles(Long tenantId) {
        return roleMapper.selectList(new LambdaQueryWrapper<PermissionRole>()
                .in(PermissionRole::getTenantId, platformAndTenantIds(tenantId))
                .eq(PermissionRole::getEnabled, true)
                .orderByAsc(PermissionRole::getTenantId)
                .orderByAsc(PermissionRole::getRoleCode));
    }

    /**
     * 查询角色可见菜单。
     *
     * <p>菜单资源和角色授权分表维护，避免新增菜单时自动暴露给所有角色。
     */
    public List<PermissionMenu> listMenus(Long tenantId, String roleCode) {
        List<String> menuCodes = roleMenuBindingMapper.selectList(new LambdaQueryWrapper<PermissionRoleMenuBinding>()
                        .in(PermissionRoleMenuBinding::getTenantId, platformAndTenantIds(tenantId))
                        .eq(PermissionRoleMenuBinding::getRoleCode, roleCode)
                        .eq(PermissionRoleMenuBinding::getEnabled, true))
                .stream()
                .map(PermissionRoleMenuBinding::getMenuCode)
                .distinct()
                .toList();

        if (menuCodes.isEmpty()) {
            return List.of();
        }

        return menuMapper.selectList(new LambdaQueryWrapper<PermissionMenu>()
                .in(PermissionMenu::getMenuCode, menuCodes)
                .eq(PermissionMenu::getEnabled, true)
                .orderByAsc(PermissionMenu::getSortOrder)
                .orderByAsc(PermissionMenu::getMenuCode));
    }

    public List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode) {
        return listRoutePolicies(tenantId, roleCode, false);
    }

    /**
     * 查询路由策略。
     *
     * <p>roleCode 为空时返回目标租户范围内全部策略，服务管理后台列表。
     * includeDisabled=true 时连禁用策略一起返回，便于管理员审查历史配置。
     */
    public List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode, Boolean includeDisabled) {
        return policyFactCache.getRoutePolicies(tenantId, roleCode, includeDisabled,
                () -> selectRoutePoliciesFromDatabase(tenantId, roleCode, includeDisabled));
    }

    /**
     * 从数据库读取路由策略。
     *
     * <p>该方法只负责构建查询条件，不负责缓存判断。
     * 把数据库读取和缓存编排拆开，是为了让权限事实缓存未来替换为 Redis、Caffeine 或多级缓存时，
     * 不需要重新改写查询条件和业务语义。
     */
    private List<PermissionRoutePolicy> selectRoutePoliciesFromDatabase(Long tenantId,
                                                                        String roleCode,
                                                                        Boolean includeDisabled) {
        LambdaQueryWrapper<PermissionRoutePolicy> wrapper = new LambdaQueryWrapper<PermissionRoutePolicy>()
                .in(PermissionRoutePolicy::getTenantId, platformAndTenantIds(tenantId))
                .orderByDesc(PermissionRoutePolicy::getPriority)
                .orderByAsc(PermissionRoutePolicy::getId);
        if (roleCode != null && !roleCode.isBlank()) {
            wrapper.eq(PermissionRoutePolicy::getRoleCode, normalizeCode(roleCode));
        }
        if (!Boolean.TRUE.equals(includeDisabled)) {
            wrapper.eq(PermissionRoutePolicy::getEnabled, true);
        }
        return routePolicyMapper.selectList(wrapper);
    }

    /**
     * 查询角色数据范围策略。
     */
    public List<PermissionDataScopePolicy> listDataScopePolicies(Long tenantId, String roleCode, String resourceType) {
        return policyFactCache.getDataScopePolicies(tenantId, roleCode, resourceType,
                () -> selectDataScopePoliciesFromDatabase(tenantId, roleCode, resourceType));
    }

    /**
     * 查询当前操作者被授权访问的项目集合。
     *
     * <p>这是 PROJECT 数据范围从“表达式占位符”走向“可执行查询条件”的关键读取点。
     * permission-admin 在判定阶段调用它，把 actorId 对应的项目集合放进 `PermissionDecisionResult`，
     * gateway 再透传给业务服务。这样 data-sync 不需要直接依赖权限中心的成员表，也不用自己解析 `${actorProjectIds}`。
     *
     * <p>当前只返回启用关系的 projectId，并做 distinct 去重。后续如果项目成员表接入有效期、组织继承或项目内角色，
     * 也应该在本方法集中处理，保持判定结果对 gateway 和业务模块稳定。
     */
    public List<Long> listActorProjectIds(Long tenantId, Long actorId) {
        if (actorId == null) {
            return List.of();
        }
        return projectMembershipMapper.selectList(new LambdaQueryWrapper<PermissionProjectMembership>()
                        .eq(PermissionProjectMembership::getTenantId, normalizeTenantId(tenantId))
                        .eq(PermissionProjectMembership::getActorId, actorId)
                        .eq(PermissionProjectMembership::getEnabled, true)
                        .isNotNull(PermissionProjectMembership::getProjectId)
                        .orderByAsc(PermissionProjectMembership::getProjectId))
                .stream()
                .map(PermissionProjectMembership::getProjectId)
                .distinct()
                .toList();
    }

    /**
     * 从数据库读取数据范围策略。
     *
     * <p>数据范围策略后续会下沉到 datasource-management、task-management、data-quality 等模块的业务查询中。
     * 当前先把读取路径集中在这里，保证将来增加字段级权限、项目范围、敏感数据审批时有统一入口。
     */
    private List<PermissionDataScopePolicy> selectDataScopePoliciesFromDatabase(Long tenantId,
                                                                                String roleCode,
                                                                                String resourceType) {
        LambdaQueryWrapper<PermissionDataScopePolicy> wrapper = new LambdaQueryWrapper<PermissionDataScopePolicy>()
                .in(PermissionDataScopePolicy::getTenantId, platformAndTenantIds(tenantId))
                .eq(PermissionDataScopePolicy::getRoleCode, roleCode)
                .eq(PermissionDataScopePolicy::getEnabled, true)
                .orderByDesc(PermissionDataScopePolicy::getTenantId)
                .orderByAsc(PermissionDataScopePolicy::getResourceType);
        if (resourceType != null && !resourceType.isBlank()) {
            wrapper.eq(PermissionDataScopePolicy::getResourceType, resourceType);
        }
        return dataScopePolicyMapper.selectList(wrapper);
    }

    /**
     * 查询权限矩阵总览。
     *
     * <p>矩阵视图适合管理后台、联调工具和学习资料入口。
     * 后续矩阵变大后，应继续补分页、角色过滤、资源类型过滤和导出任务。
     */
    public PermissionMatrixView loadMatrix(Long tenantId) {
        List<Long> tenantIds = platformAndTenantIds(tenantId);
        List<PermissionRole> roles = listRoles(tenantId);
        List<PermissionMenu> menus = menuMapper.selectList(new LambdaQueryWrapper<PermissionMenu>()
                .eq(PermissionMenu::getEnabled, true)
                .orderByAsc(PermissionMenu::getSortOrder));
        List<PermissionRoutePolicy> routePolicies = routePolicyMapper.selectList(new LambdaQueryWrapper<PermissionRoutePolicy>()
                .in(PermissionRoutePolicy::getTenantId, tenantIds)
                .eq(PermissionRoutePolicy::getEnabled, true)
                .orderByAsc(PermissionRoutePolicy::getRoleCode)
                .orderByDesc(PermissionRoutePolicy::getPriority));
        List<PermissionDataScopePolicy> dataScopes = dataScopePolicyMapper.selectList(new LambdaQueryWrapper<PermissionDataScopePolicy>()
                .in(PermissionDataScopePolicy::getTenantId, tenantIds)
                .eq(PermissionDataScopePolicy::getEnabled, true)
                .orderByAsc(PermissionDataScopePolicy::getRoleCode)
                .orderByAsc(PermissionDataScopePolicy::getResourceType));
        return new PermissionMatrixView(normalizeTenantId(tenantId), roles, menus, routePolicies, dataScopes);
    }
}
