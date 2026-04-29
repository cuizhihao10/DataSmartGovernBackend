/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAdminServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionMatrixView;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyEnabledRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyMutationRequest;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionMenu;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.entity.PermissionRoleMenuBinding;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.event.PermissionPolicyChangedEventPublisher;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionDataScopePolicyMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionMenuMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMenuBindingMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoutePolicyMapper;
import com.czh.datasmart.govern.permission.service.PermissionAdminService;
import com.czh.datasmart.govern.permission.support.PermissionRouteEffect;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 权限管理服务实现。
 *
 * <p>当前实现是 permission-admin 的第一版“权限事实中心”：
 * 1. 从 MySQL 读取角色、菜单、路由策略和数据范围；
 * 2. 通过简单但清晰的规则完成路由级判定；
 * 3. 为每次判定写入审计记录，哪怕只是基础摘要，也能让后续排查“为什么允许/拒绝”有证据。
 *
 * <p>后续商业化增强方向：
 * 1. 引入 Redis 缓存，把常用角色策略缓存起来，将 P95 授权延迟压到 20ms 以内；
 * 2. 权限变更后通过 Kafka 发布缓存失效事件；
 * 3. 对高风险策略变更接入审批流；
 * 4. 用更严格的路径匹配器或 route metadata 替代当前轻量 wildcard 匹配。
 */
@Service
@RequiredArgsConstructor
public class PermissionAdminServiceImpl implements PermissionAdminService {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final String ANY_HTTP_METHOD = "ANY";
    private static final String RESOURCE_TYPE_SYSTEM_SETTING = "SYSTEM_SETTING";
    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";
    private static final String AUDIT_RESULT_FAILED = "FAILED";
    private static final String EVENT_ROUTE_POLICY_CREATED = "ROUTE_POLICY_CREATED";
    private static final String EVENT_ROUTE_POLICY_UPDATED = "ROUTE_POLICY_UPDATED";
    private static final String EVENT_ROUTE_POLICY_ENABLED = "ROUTE_POLICY_ENABLED";
    private static final String EVENT_ROUTE_POLICY_DISABLED = "ROUTE_POLICY_DISABLED";

    private final PermissionRoleMapper roleMapper;
    private final PermissionMenuMapper menuMapper;
    private final PermissionRoleMenuBindingMapper roleMenuBindingMapper;
    private final PermissionRoutePolicyMapper routePolicyMapper;
    private final PermissionDataScopePolicyMapper dataScopePolicyMapper;
    private final PermissionAuditRecordMapper auditRecordMapper;
    private final PermissionPolicyChangedEventPublisher policyChangedEventPublisher;

    /**
     * 查询租户可用角色。
     *
     * <p>当前策略是“平台默认角色 + 当前租户角色”一起返回。
     * 如果后续出现同 roleCode 覆盖关系，可以在这里增加去重与覆盖优先级。
     */
    @Override
    public List<PermissionRole> listRoles(Long tenantId) {
        List<Long> tenantIds = platformAndTenantIds(tenantId);
        return roleMapper.selectList(new LambdaQueryWrapper<PermissionRole>()
                .in(PermissionRole::getTenantId, tenantIds)
                .eq(PermissionRole::getEnabled, true)
                .orderByAsc(PermissionRole::getTenantId)
                .orderByAsc(PermissionRole::getRoleCode));
    }

    /**
     * 查询角色可见菜单。
     *
     * <p>先查角色菜单绑定，再查菜单本体。
     * 这样可以让菜单资源和角色授权独立演进：新增菜单不代表自动授权，新增角色也不需要复制菜单表。
     */
    @Override
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

    /**
     * 查询角色路由策略。
     */
    @Override
    public List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode) {
        return listRoutePolicies(tenantId, roleCode, false);
    }

    /**
     * 查询路由策略。
     *
     * <p>当 roleCode 为空时返回目标租户范围内的全部策略，适合管理后台做策略列表。
     * 当 includeDisabled=true 时连禁用策略一起返回，便于管理员审查历史配置和临时停用规则。
     */
    @Override
    public List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode, Boolean includeDisabled) {
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
     * 创建路由策略。
     *
     * <p>路由策略创建后，需要通知 gateway 清理授权缓存。
     * 当前阶段通过事务 outbox 记录待投递事件，再由后台投递器发送 Kafka，避免策略已提交但事件丢失。
     */
    @Override
    @Transactional
    public PermissionRoutePolicy createRoutePolicy(PermissionRoutePolicyMutationRequest request,
                                                   PermissionActorContext actorContext) {
        PermissionRoutePolicy policy = buildRoutePolicy(request);
        try {
            validateRoutePolicyMutationPermission(actorContext, policy.getTenantId());
            validateRoutePolicyBusinessRules(policy, null);

            LocalDateTime now = LocalDateTime.now();
            policy.setCreateTime(now);
            policy.setUpdateTime(now);
            routePolicyMapper.insert(policy);
            saveRoutePolicyMutationAudit(actorContext, "CREATE_ROUTE_POLICY", AUDIT_RESULT_SUCCESS,
                    "创建路由策略：" + policy.getPolicyName(), policy, null);
            policyChangedEventPublisher.publishRoutePolicyChanged(EVENT_ROUTE_POLICY_CREATED, policy, actorContext,
                    "创建路由策略：" + policy.getPolicyName());
            return policy;
        } catch (RuntimeException exception) {
            saveRoutePolicyMutationAudit(actorContext, "CREATE_ROUTE_POLICY", AUDIT_RESULT_FAILED,
                    "创建路由策略失败：" + exception.getMessage(), policy, null);
            throw exception;
        }
    }

    /**
     * 更新路由策略。
     *
     * <p>更新策略时会先读取旧策略，用于：
     * 1. 判断操作者是否有权管理原策略和目标策略；
     * 2. 审计中记录 before/after，便于后续追溯权限变化。
     */
    @Override
    @Transactional
    public PermissionRoutePolicy updateRoutePolicy(Long policyId,
                                                   PermissionRoutePolicyMutationRequest request,
                                                   PermissionActorContext actorContext) {
        PermissionRoutePolicy existingPolicy = findRoutePolicyOrThrow(policyId);
        PermissionRoutePolicy updatedPolicy = buildRoutePolicy(request);
        updatedPolicy.setId(policyId);
        updatedPolicy.setCreateTime(existingPolicy.getCreateTime());

        try {
            validateRoutePolicyMutationPermission(actorContext, existingPolicy.getTenantId());
            validateRoutePolicyMutationPermission(actorContext, updatedPolicy.getTenantId());
            validateRoutePolicyBusinessRules(updatedPolicy, policyId);

            updatedPolicy.setUpdateTime(LocalDateTime.now());
            routePolicyMapper.updateById(updatedPolicy);
            saveRoutePolicyMutationAudit(actorContext, "UPDATE_ROUTE_POLICY", AUDIT_RESULT_SUCCESS,
                    "更新路由策略：" + updatedPolicy.getPolicyName(), updatedPolicy, existingPolicy);
            PermissionRoutePolicy refreshedPolicy = findRoutePolicyOrThrow(policyId);
            policyChangedEventPublisher.publishRoutePolicyChanged(EVENT_ROUTE_POLICY_UPDATED, refreshedPolicy, actorContext,
                    "更新路由策略：" + refreshedPolicy.getPolicyName());
            return refreshedPolicy;
        } catch (RuntimeException exception) {
            saveRoutePolicyMutationAudit(actorContext, "UPDATE_ROUTE_POLICY", AUDIT_RESULT_FAILED,
                    "更新路由策略失败：" + exception.getMessage(), updatedPolicy, existingPolicy);
            throw exception;
        }
    }

    /**
     * 启用或禁用路由策略。
     *
     * <p>这里不做物理删除，是为了保留策略历史和审计证据。
     */
    @Override
    @Transactional
    public PermissionRoutePolicy changeRoutePolicyEnabled(Long policyId,
                                                          PermissionRoutePolicyEnabledRequest request,
                                                          PermissionActorContext actorContext) {
        PermissionRoutePolicy policy = findRoutePolicyOrThrow(policyId);
        PermissionRoutePolicy before = cloneRoutePolicy(policy);
        try {
            validateRoutePolicyMutationPermission(actorContext, policy.getTenantId());
            policy.setEnabled(request.getEnabled());
            policy.setUpdateTime(LocalDateTime.now());
            routePolicyMapper.updateById(policy);
            saveRoutePolicyMutationAudit(actorContext,
                    Boolean.TRUE.equals(request.getEnabled()) ? "ENABLE_ROUTE_POLICY" : "DISABLE_ROUTE_POLICY",
                    AUDIT_RESULT_SUCCESS,
                    (Boolean.TRUE.equals(request.getEnabled()) ? "启用" : "禁用") + "路由策略：" + policy.getPolicyName()
                            + valueForAudit(", reason=", request.getReason()),
                    policy,
                    before);
            PermissionRoutePolicy refreshedPolicy = findRoutePolicyOrThrow(policyId);
            policyChangedEventPublisher.publishRoutePolicyChanged(
                    Boolean.TRUE.equals(request.getEnabled()) ? EVENT_ROUTE_POLICY_ENABLED : EVENT_ROUTE_POLICY_DISABLED,
                    refreshedPolicy,
                    actorContext,
                    (Boolean.TRUE.equals(request.getEnabled()) ? "启用" : "禁用") + "路由策略：" + refreshedPolicy.getPolicyName());
            return refreshedPolicy;
        } catch (RuntimeException exception) {
            saveRoutePolicyMutationAudit(actorContext,
                    Boolean.TRUE.equals(request.getEnabled()) ? "ENABLE_ROUTE_POLICY" : "DISABLE_ROUTE_POLICY",
                    AUDIT_RESULT_FAILED,
                    "启停路由策略失败：" + exception.getMessage(),
                    policy,
                    before);
            throw exception;
        }
    }

    /**
     * 查询角色数据范围策略。
     */
    @Override
    public List<PermissionDataScopePolicy> listDataScopePolicies(Long tenantId, String roleCode, String resourceType) {
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
     * <p>这个接口适合作为管理后台、联调工具和学习资料入口。
     * 后续矩阵变大以后，可以增加分页、角色过滤、资源类型过滤，避免一次性返回过多数据。
     */
    @Override
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

    /**
     * 判定一次访问是否允许。
     *
     * <p>当前判定流程：
     * 1. 找出当前角色在平台默认和当前租户范围内的所有启用路由策略；
     * 2. 按优先级排序，寻找 HTTP 方法和路径都匹配的策略；
     * 3. 如果命中 DENY，则拒绝；命中 ALLOW，则继续读取数据范围；
     * 4. 如果没有任何策略命中，则默认拒绝。权限系统应默认保守，而不是默认放行。
     *
     * <p>这里的默认拒绝是商业化系统非常重要的原则。
     * 因为新增接口如果忘记配置权限，最安全的结果应该是访问失败，而不是自动暴露给所有用户。
     */
    @Override
    @Transactional
    public PermissionDecisionResult evaluate(PermissionDecisionRequest request, String traceId) {
        PermissionRoutePolicy matchedRoute = findMatchedRoutePolicy(request);
        if (matchedRoute == null) {
            PermissionDecisionResult result = denied("没有命中任何启用的路由策略，按默认拒绝处理", null, null);
            saveAudit(request, traceId, result);
            return result;
        }

        if (PermissionRouteEffect.DENY.name().equals(matchedRoute.getEffect())) {
            PermissionDecisionResult result = denied("命中显式拒绝策略：" + matchedRoute.getPolicyName(), matchedRoute, null);
            saveAudit(request, traceId, result);
            return result;
        }

        PermissionDataScopePolicy dataScope = findBestDataScope(request);
        PermissionDecisionResult result = new PermissionDecisionResult(
                true,
                "命中允许策略：" + matchedRoute.getPolicyName(),
                matchedRoute.getId(),
                matchedRoute.getEffect(),
                dataScope == null ? null : dataScope.getScopeLevel(),
                dataScope == null ? null : dataScope.getScopeExpression(),
                dataScope != null && Boolean.TRUE.equals(dataScope.getApprovalRequired())
        );
        saveAudit(request, traceId, result);
        return result;
    }

    /**
     * 查找命中的路由策略。
     *
     * <p>同一角色可能有多条策略，例如允许 GET /api/task/**，拒绝 DELETE /api/task/**。
     * 排序时先看 priority，再让 DENY 优先于 ALLOW，避免宽松策略覆盖更严格策略。
     */
    private PermissionRoutePolicy findMatchedRoutePolicy(PermissionDecisionRequest request) {
        return listRoutePolicies(request.getTenantId(), request.getActorRole())
                .stream()
                .filter(policy -> methodMatches(policy.getHttpMethod(), request.getHttpMethod()))
                .filter(policy -> pathMatches(policy.getPathPattern(), request.getRequestPath()))
                .sorted(Comparator
                        .comparing(PermissionRoutePolicy::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(policy -> PermissionRouteEffect.DENY.name().equals(policy.getEffect()) ? 0 : 1))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查找最合适的数据范围策略。
     *
     * <p>当前先取当前角色 + 资源类型下的第一条启用策略。
     * 后续可以加入更精细的策略优先级、项目范围、字段级权限、敏感数据审批等规则。
     */
    private PermissionDataScopePolicy findBestDataScope(PermissionDecisionRequest request) {
        String resourceType = request.getResourceType();
        if (resourceType == null || resourceType.isBlank()) {
            return null;
        }
        return listDataScopePolicies(request.getTenantId(), request.getActorRole(), resourceType)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断 HTTP 方法是否匹配。
     */
    private boolean methodMatches(String configuredMethod, String requestMethod) {
        if (configuredMethod == null || configuredMethod.isBlank()) {
            return false;
        }
        return ANY_HTTP_METHOD.equalsIgnoreCase(configuredMethod)
                || configuredMethod.equalsIgnoreCase(requestMethod);
    }

    /**
     * 判断路径是否匹配。
     *
     * <p>当前实现只支持最常见的 /** 后缀通配和完全匹配，足够覆盖现阶段 gateway 路由前缀。
     * 后续如果需要支持 /api/task/{id}/logs 这类模板匹配，可以替换为 Spring 的 PathPatternParser。
     */
    private boolean pathMatches(String pattern, String requestPath) {
        if (pattern == null || requestPath == null) {
            return false;
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
        }
        return Objects.equals(pattern, requestPath);
    }

    /**
     * 构造拒绝结果。
     */
    private PermissionDecisionResult denied(String reason,
                                            PermissionRoutePolicy routePolicy,
                                            PermissionDataScopePolicy dataScopePolicy) {
        return new PermissionDecisionResult(
                false,
                reason,
                routePolicy == null ? null : routePolicy.getId(),
                routePolicy == null ? null : routePolicy.getEffect(),
                dataScopePolicy == null ? null : dataScopePolicy.getScopeLevel(),
                dataScopePolicy == null ? null : dataScopePolicy.getScopeExpression(),
                dataScopePolicy != null && Boolean.TRUE.equals(dataScopePolicy.getApprovalRequired())
        );
    }

    /**
     * 保存权限判定审计。
     *
     * <p>即使是读接口的判定，也建议记录关键摘要，尤其是拒绝场景。
     * 生产环境中可以对允许访问做采样，对拒绝访问、高风险动作、跨租户操作做 100% 记录。
     */
    private void saveAudit(PermissionDecisionRequest request, String traceId, PermissionDecisionResult result) {
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(traceId);
        auditRecord.setTenantId(normalizeTenantId(request.getTenantId()));
        auditRecord.setActorId(request.getActorId());
        auditRecord.setActorRole(request.getActorRole());
        auditRecord.setResourceType(request.getResourceType());
        auditRecord.setResourceId(request.getRequestPath());
        auditRecord.setAction(request.getAction() == null ? request.getHttpMethod().toUpperCase(Locale.ROOT) : request.getAction());
        auditRecord.setResult(Boolean.TRUE.equals(result.getAllowed()) ? "SUCCESS" : "DENIED");
        auditRecord.setSummary(result.getReason());
        auditRecord.setDetailJson("{\"httpMethod\":\"" + request.getHttpMethod() + "\",\"requestPath\":\""
                + request.getRequestPath() + "\",\"matchedRoutePolicyId\":\"" + result.getMatchedRoutePolicyId() + "\"}");
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    /**
     * 根据请求构造路由策略实体。
     *
     * <p>这里集中做字段规范化，避免 Controller、Service、Mapper 各自处理大小写和空值。
     * 权限策略这种基础设施数据应该尽量保持稳定编码：
     * roleCode、httpMethod、effect 使用大写，pathPattern 保留路径原样但去除首尾空格。
     */
    private PermissionRoutePolicy buildRoutePolicy(PermissionRoutePolicyMutationRequest request) {
        PermissionRoutePolicy policy = new PermissionRoutePolicy();
        policy.setTenantId(normalizeTenantId(request.getTenantId()));
        policy.setPolicyName(request.getPolicyName().trim());
        policy.setRoleCode(normalizeCode(request.getRoleCode()));
        policy.setHttpMethod(normalizeCode(request.getHttpMethod()));
        policy.setPathPattern(request.getPathPattern().trim());
        policy.setEffect(normalizeCode(request.getEffect()));
        policy.setPriority(request.getPriority() == null ? 100 : request.getPriority());
        policy.setEnabled(request.getEnabled() == null || request.getEnabled());
        policy.setDescription(request.getDescription() == null ? null : request.getDescription().trim());
        return policy;
    }

    /**
     * 校验当前操作者是否可以修改目标租户的路由策略。
     *
     * <p>权限中心自身必须有服务内防线，不能完全依赖 gateway。
     * 当前第一版规则：
     * 1. 平台管理员可以管理全局和任意租户策略；
     * 2. 租户管理员只能管理自己租户的非全局策略；
     * 3. 普通用户、项目负责人、运营人员、审计员、服务账号默认不能直接修改路由策略。
     *
     * <p>后续可以继续扩展为“安全管理员”“合规审核员”“权限变更审批人”等更细角色。
     */
    private void validateRoutePolicyMutationPermission(PermissionActorContext actorContext, Long targetTenantId) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "缺少可信操作者角色，不能修改路由策略");
        }

        String actorRole = normalizeCode(actorContext.actorRole());
        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        Long normalizedTargetTenantId = normalizeTenantId(targetTenantId);

        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }

        if (PermissionRoleCode.TENANT_ADMINISTRATOR.name().equals(actorRole)
                && !isPlatformTenant(normalizedTargetTenantId)
                && normalizedTargetTenantId.equals(actorTenantId)) {
            return;
        }

        throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                "当前角色无权修改目标租户的路由策略，actorRole=" + actorRole + ", targetTenantId=" + normalizedTargetTenantId);
    }

    /**
     * 校验路由策略业务规则。
     *
     * <p>Jakarta Validation 负责字段格式，Service 负责跨表和业务一致性：
     * 1. 路径必须以 / 开头；
     * 2. effect 必须属于权限中心支持的枚举；
     * 3. roleCode 必须存在；
     * 4. 同一租户、角色、方法、路径、效果不能重复。
     */
    private void validateRoutePolicyBusinessRules(PermissionRoutePolicy policy, Long ignoredPolicyId) {
        if (!policy.getPathPattern().startsWith("/")) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "pathPattern 必须以 / 开头");
        }

        if (!PermissionRouteEffect.ALLOW.name().equals(policy.getEffect())
                && !PermissionRouteEffect.DENY.name().equals(policy.getEffect())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "effect 必须是 ALLOW 或 DENY");
        }

        if (!roleExists(policy.getTenantId(), policy.getRoleCode())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "路由策略绑定的角色不存在或未启用：" + policy.getRoleCode());
        }

        LambdaQueryWrapper<PermissionRoutePolicy> duplicateWrapper = new LambdaQueryWrapper<PermissionRoutePolicy>()
                .eq(PermissionRoutePolicy::getTenantId, policy.getTenantId())
                .eq(PermissionRoutePolicy::getRoleCode, policy.getRoleCode())
                .eq(PermissionRoutePolicy::getHttpMethod, policy.getHttpMethod())
                .eq(PermissionRoutePolicy::getPathPattern, policy.getPathPattern())
                .eq(PermissionRoutePolicy::getEffect, policy.getEffect());
        if (ignoredPolicyId != null) {
            duplicateWrapper.ne(PermissionRoutePolicy::getId, ignoredPolicyId);
        }

        Long duplicateCount = routePolicyMapper.selectCount(duplicateWrapper);
        if (duplicateCount != null && duplicateCount > 0) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "同一租户、角色、方法、路径和效果下已存在路由策略");
        }
    }

    /**
     * 判断角色是否存在。
     *
     * <p>允许策略绑定平台默认角色或目标租户自定义角色。
     * 当前还没有完整自定义角色管理 API，但这里先把查询规则预留好。
     */
    private boolean roleExists(Long tenantId, String roleCode) {
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<PermissionRole>()
                .in(PermissionRole::getTenantId, platformAndTenantIds(tenantId))
                .eq(PermissionRole::getRoleCode, roleCode)
                .eq(PermissionRole::getEnabled, true));
        return count != null && count > 0;
    }

    /**
     * 查询路由策略，不存在时抛出业务异常。
     */
    private PermissionRoutePolicy findRoutePolicyOrThrow(Long policyId) {
        PermissionRoutePolicy policy = routePolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "路由策略不存在：" + policyId);
        }
        return policy;
    }

    /**
     * 保存路由策略变更审计。
     *
     * <p>策略变更审计和访问判定审计使用同一张 permission_audit_record 表，
     * 便于后续统一查询“谁改了策略”和“某次请求为什么被允许/拒绝”。
     */
    private void saveRoutePolicyMutationAudit(PermissionActorContext actorContext,
                                              String action,
                                              String result,
                                              String summary,
                                              PermissionRoutePolicy afterPolicy,
                                              PermissionRoutePolicy beforePolicy) {
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(actorContext == null ? null : actorContext.traceId());
        auditRecord.setTenantId(afterPolicy == null ? PLATFORM_TENANT_ID : normalizeTenantId(afterPolicy.getTenantId()));
        auditRecord.setActorId(actorContext == null ? null : actorContext.actorId());
        auditRecord.setActorRole(actorContext == null ? null : actorContext.actorRole());
        auditRecord.setResourceType(RESOURCE_TYPE_SYSTEM_SETTING);
        auditRecord.setResourceId(afterPolicy == null || afterPolicy.getId() == null ? "permission_route_policy" : "permission_route_policy:" + afterPolicy.getId());
        auditRecord.setAction(action);
        auditRecord.setResult(result);
        auditRecord.setSummary(summary);
        auditRecord.setDetailJson(routePolicyAuditDetail(beforePolicy, afterPolicy));
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    /**
     * 生成路由策略审计详情 JSON。
     *
     * <p>当前为了避免引入额外复杂度，手动构造一段简单 JSON。
     * 后续如果审计详情字段继续增多，可以引入 ObjectMapper 统一序列化。
     */
    private String routePolicyAuditDetail(PermissionRoutePolicy beforePolicy, PermissionRoutePolicy afterPolicy) {
        return "{\"before\":" + routePolicyJson(beforePolicy) + ",\"after\":" + routePolicyJson(afterPolicy) + "}";
    }

    /**
     * 将策略关键字段写成 JSON 片段。
     */
    private String routePolicyJson(PermissionRoutePolicy policy) {
        if (policy == null) {
            return "null";
        }
        return "{"
                + "\"id\":\"" + nullSafe(policy.getId()) + "\","
                + "\"tenantId\":\"" + nullSafe(policy.getTenantId()) + "\","
                + "\"policyName\":\"" + jsonEscape(policy.getPolicyName()) + "\","
                + "\"roleCode\":\"" + jsonEscape(policy.getRoleCode()) + "\","
                + "\"httpMethod\":\"" + jsonEscape(policy.getHttpMethod()) + "\","
                + "\"pathPattern\":\"" + jsonEscape(policy.getPathPattern()) + "\","
                + "\"effect\":\"" + jsonEscape(policy.getEffect()) + "\","
                + "\"priority\":\"" + nullSafe(policy.getPriority()) + "\","
                + "\"enabled\":\"" + nullSafe(policy.getEnabled()) + "\""
                + "}";
    }

    /**
     * 克隆路由策略关键字段，用于审计 before 快照。
     */
    private PermissionRoutePolicy cloneRoutePolicy(PermissionRoutePolicy source) {
        PermissionRoutePolicy target = new PermissionRoutePolicy();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setPolicyName(source.getPolicyName());
        target.setRoleCode(source.getRoleCode());
        target.setHttpMethod(source.getHttpMethod());
        target.setPathPattern(source.getPathPattern());
        target.setEffect(source.getEffect());
        target.setPriority(source.getPriority());
        target.setEnabled(source.getEnabled());
        target.setDescription(source.getDescription());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    /**
     * 统一编码规范化。
     */
    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 判断是否为平台租户。
     */
    private boolean isPlatformTenant(Long tenantId) {
        return PLATFORM_TENANT_ID == normalizeTenantId(tenantId);
    }

    /**
     * 为审计摘要拼接可选字段。
     */
    private String valueForAudit(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value.trim();
    }

    /**
     * JSON 字符串转义。
     */
    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 空值安全字符串。
     */
    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 返回平台默认租户和当前租户两个策略范围。
     */
    private List<Long> platformAndTenantIds(Long tenantId) {
        Long normalizedTenantId = normalizeTenantId(tenantId);
        if (PLATFORM_TENANT_ID == normalizedTenantId) {
            return List.of(PLATFORM_TENANT_ID);
        }
        return List.of(PLATFORM_TENANT_ID, normalizedTenantId);
    }

    /**
     * 归一化租户 ID。
     */
    private Long normalizeTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }
}
