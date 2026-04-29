package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingView;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyBinding;
import com.czh.datasmart.govern.datasource.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncPermissionPolicyBindingMapper;
import com.czh.datasmart.govern.datasource.service.SyncPermissionBindingService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncAdminMenu;
import com.czh.datasmart.govern.datasource.support.SyncAdminRoutePolicy;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncDataScopeLevel;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionBindingType;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionBindingServiceImpl.java
 * @Version:1.0.0
 *
 * 权限绑定治理服务实现。
 * 这是 datasource-management 走向“数据库化权限治理”的第一版核心服务。
 *
 * 这一层主要回答三类问题：
 * 1. 当前某个角色在平台全局或某租户下，到底有哪些显式数据库绑定？
 * 2. 管理员如果想替换一整组菜单/路由/数据范围绑定，系统应该如何安全写入？
 * 3. 快照层在做解释时，应该如何按“租户覆盖优先，其次平台全局”的顺序拿到数据库级生效值？
 *
 * 当前故意先不做得太“聪明”：
 * - 不做复杂审批流；
 * - 不做跨服务同步；
 * - 不做分布式缓存一致性；
 * 而是先把最核心的治理对象、优先级和管理入口站稳，后面再继续往 permission-admin 对齐。
 */
@Service
@RequiredArgsConstructor
public class SyncPermissionBindingServiceImpl implements SyncPermissionBindingService {

    /**
     * 数据库里用 `0` 表示平台全局绑定，避免 `NULL` 唯一键带来的不稳定行为。
     */
    private static final long PLATFORM_SCOPE_TENANT_ID = 0L;

    private final SyncPermissionPolicyBindingMapper syncPermissionPolicyBindingMapper;
    private final SyncAuditRecordMapper syncAuditRecordMapper;
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    @Override
    public List<SyncPermissionBindingView> listBindings(Long actorId,
                                                        String actorRole,
                                                        Long actorTenantId,
                                                        Long targetTenantId,
                                                        String targetRole,
                                                        String bindingType,
                                                        Boolean includeDisabled) {
        Long resolvedScopeTenantId = resolveReadableTargetTenantId(actorRole, actorTenantId, targetTenantId);
        SyncPermissionContext context = buildContext(actorId, actorRole, actorTenantId, resolvedScopeTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY);

        ActorRole normalizedRole = ActorRole.fromValue(targetRole);
        SyncPermissionBindingType normalizedType = bindingType == null || bindingType.isBlank()
                ? null
                : SyncPermissionBindingType.fromValue(bindingType);

        List<Long> tenantScopes = resolvedScopeTenantId == null
                ? List.of(PLATFORM_SCOPE_TENANT_ID)
                : List.of(PLATFORM_SCOPE_TENANT_ID, resolvedScopeTenantId);

        LambdaQueryWrapper<SyncPermissionPolicyBinding> wrapper = new LambdaQueryWrapper<SyncPermissionPolicyBinding>()
                .eq(SyncPermissionPolicyBinding::getActorRole, normalizedRole.name())
                .in(SyncPermissionPolicyBinding::getTenantId, tenantScopes)
                .orderByDesc(SyncPermissionPolicyBinding::getTenantId)
                .orderByDesc(SyncPermissionPolicyBinding::getPriority)
                .orderByAsc(SyncPermissionPolicyBinding::getBindingType)
                .orderByAsc(SyncPermissionPolicyBinding::getBindingValue)
                .orderByDesc(SyncPermissionPolicyBinding::getUpdateTime);

        if (normalizedType != null) {
            wrapper.eq(SyncPermissionPolicyBinding::getBindingType, normalizedType.name());
        }
        if (!Boolean.TRUE.equals(includeDisabled)) {
            wrapper.eq(SyncPermissionPolicyBinding::getEnabled, true);
        }

        return syncPermissionPolicyBindingMapper.selectList(wrapper).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public SyncPermissionBindingReplaceResult replaceBindings(SyncPermissionBindingReplaceRequest request) {
        Long resolvedScopeTenantId = resolveWritableTargetTenantId(
                request.getActorRole(), request.getActorTenantId(), request.getTargetTenantId());
        SyncPermissionContext context = buildContext(
                request.getActorId(), request.getActorRole(), request.getActorTenantId(), resolvedScopeTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY);

        ActorRole targetRole = ActorRole.fromValue(request.getTargetRole());
        SyncPermissionBindingType bindingType = SyncPermissionBindingType.fromValue(request.getBindingType());
        List<String> normalizedValues = normalizeBindingValues(bindingType, request.getBindingValues());

        List<SyncPermissionPolicyBinding> existingBindings = syncPermissionPolicyBindingMapper.selectList(
                new LambdaQueryWrapper<SyncPermissionPolicyBinding>()
                        .eq(SyncPermissionPolicyBinding::getTenantId, toStoredTenantId(resolvedScopeTenantId))
                        .eq(SyncPermissionPolicyBinding::getActorRole, targetRole.name())
                        .eq(SyncPermissionPolicyBinding::getBindingType, bindingType.name())
                        .eq(SyncPermissionPolicyBinding::getEnabled, true)
        );

        int disabledCount = 0;
        for (SyncPermissionPolicyBinding binding : existingBindings) {
            binding.setEnabled(false);
            binding.setUpdatedBy(request.getActorId());
            syncPermissionPolicyBindingMapper.updateById(binding);
            disabledCount++;
        }

        int createdCount = 0;
        for (String bindingValue : normalizedValues) {
            SyncPermissionPolicyBinding binding = new SyncPermissionPolicyBinding();
            binding.setTenantId(toStoredTenantId(resolvedScopeTenantId));
            binding.setActorRole(targetRole.name());
            binding.setBindingType(bindingType.name());
            binding.setBindingValue(bindingValue);
            binding.setBindingSource(normalizeBindingSource(request.getBindingSource()));
            binding.setEnabled(true);
            binding.setPriority(resolvePriority(request.getPriority()));
            binding.setNote(request.getNote());
            binding.setCreatedBy(request.getActorId());
            binding.setUpdatedBy(request.getActorId());
            syncPermissionPolicyBindingMapper.insert(binding);
            createdCount++;
        }

        SyncPermissionBindingReplaceResult result = new SyncPermissionBindingReplaceResult();
        result.setTargetTenantId(resolvedScopeTenantId);
        result.setTargetRole(targetRole.name());
        result.setBindingType(bindingType.name());
        result.setDisabledCount(disabledCount);
        result.setCreatedCount(createdCount);
        result.setActiveBindings(listBindings(
                request.getActorId(),
                request.getActorRole(),
                request.getActorTenantId(),
                resolvedScopeTenantId,
                targetRole.name(),
                bindingType.name(),
                false));
        result.setSummary(buildReplaceSummary(resolvedScopeTenantId, targetRole.name(), bindingType,
                disabledCount, createdCount, normalizedValues.isEmpty()));
        recordAudit(resolvedScopeTenantId,
                SyncAuditAction.REPLACE_PERMISSION_POLICY_BINDINGS,
                request.getActorId(),
                request.getActorRole(),
                buildPayload(
                        "targetTenantId", resolvedScopeTenantId == null ? "PLATFORM" : resolvedScopeTenantId,
                        "targetRole", targetRole.name(),
                        "bindingType", bindingType.name(),
                        "disabledCount", disabledCount,
                        "createdCount", createdCount,
                        "bindingValues", normalizedValues
                ));
        return result;
    }

    @Override
    public List<String> resolveEffectiveBindingValues(Long targetTenantId,
                                                      String targetRole,
                                                      SyncPermissionBindingType bindingType) {
        ActorRole normalizedRole = ActorRole.fromValue(targetRole);
        Long storedTenantId = toStoredTenantId(targetTenantId);

        List<String> tenantBindings = loadActiveBindingValues(storedTenantId, normalizedRole.name(), bindingType.name());
        if (targetTenantId != null && !tenantBindings.isEmpty()) {
            return tenantBindings;
        }
        return loadActiveBindingValues(PLATFORM_SCOPE_TENANT_ID, normalizedRole.name(), bindingType.name());
    }

    /**
     * 查询指定作用域下启用中的绑定值列表。
     * 返回值按优先级和更新时间排序，方便单值场景直接取第一条，多值场景按顺序展示。
     */
    private List<String> loadActiveBindingValues(Long storedTenantId, String actorRole, String bindingType) {
        return syncPermissionPolicyBindingMapper.selectList(
                        new LambdaQueryWrapper<SyncPermissionPolicyBinding>()
                                .eq(SyncPermissionPolicyBinding::getTenantId, storedTenantId)
                                .eq(SyncPermissionPolicyBinding::getActorRole, actorRole)
                                .eq(SyncPermissionPolicyBinding::getBindingType, bindingType)
                                .eq(SyncPermissionPolicyBinding::getEnabled, true)
                                .orderByDesc(SyncPermissionPolicyBinding::getPriority)
                                .orderByDesc(SyncPermissionPolicyBinding::getUpdateTime)
                                .orderByAsc(SyncPermissionPolicyBinding::getId))
                .stream()
                .map(SyncPermissionPolicyBinding::getBindingValue)
                .toList();
    }

    /**
     * 将请求里的目标租户解释成“查询时可读取的实际作用域”。
     * 平台管理员允许跨租户甚至查看平台全局；其他角色只能查看自己租户下的绑定。
     */
    private Long resolveReadableTargetTenantId(String actorRole, Long actorTenantId, Long targetTenantId) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return targetTenantId;
        }
        if (targetTenantId == null) {
            return actorTenantId;
        }
        if (!Objects.equals(actorTenantId, targetTenantId)) {
            throw new IllegalStateException("当前角色不能跨租户查看权限绑定");
        }
        return targetTenantId;
    }

    /**
     * 将请求里的目标租户解释成“写入时允许变更的实际作用域”。
     * 当前仅平台管理员允许操作平台全局绑定。
     */
    private Long resolveWritableTargetTenantId(String actorRole, Long actorTenantId, Long targetTenantId) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return targetTenantId;
        }
        if (targetTenantId == null) {
            return actorTenantId;
        }
        if (!Objects.equals(actorTenantId, targetTenantId)) {
            throw new IllegalStateException("当前角色不能跨租户修改权限绑定");
        }
        return targetTenantId;
    }

    /**
     * 统一构造权限上下文。
     */
    private SyncPermissionContext buildContext(Long actorId,
                                               String actorRole,
                                               Long actorTenantId,
                                               Long targetTenantId) {
        return SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(targetTenantId)
                .build();
    }

    /**
     * 规范化绑定值并做类型校验。
     * 这一层相当于“后端防线”，避免把错误菜单码、错误路由码或多个数据范围值直接写进数据库。
     */
    private List<String> normalizeBindingValues(SyncPermissionBindingType bindingType, List<String> rawValues) {
        List<String> normalizedValues = rawValues == null ? List.of() : rawValues.stream()
                .filter(StringUtils::hasText)
                .map(value -> normalizeBindingValue(bindingType, value))
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        ArrayList::new));

        if (!bindingType.isAllowMultipleValues() && normalizedValues.size() > 1) {
            throw new IllegalArgumentException("当前绑定类型只允许配置一个值: " + bindingType.name());
        }
        return normalizedValues;
    }

    /**
     * 对单个绑定值做强校验。
     */
    private String normalizeBindingValue(SyncPermissionBindingType bindingType, String rawValue) {
        String trimmedValue = rawValue.trim();
        return switch (bindingType) {
            case MENU -> Arrays.stream(SyncAdminMenu.values())
                    .map(SyncAdminMenu::getCode)
                    .filter(code -> code.equalsIgnoreCase(trimmedValue))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("不支持的菜单编码: " + rawValue));
            case ROUTE -> SyncAdminRoutePolicy.valueOf(trimmedValue.toUpperCase(Locale.ROOT)).name();
            case DATA_SCOPE -> SyncDataScopeLevel.valueOf(trimmedValue.toUpperCase(Locale.ROOT)).name();
            case ADMIN_ONLY_ACTION, APPROVAL_RECOMMENDED_ACTION ->
                    trimmedValue.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        };
    }

    /**
     * 规范化绑定来源。
     */
    private String normalizeBindingSource(String bindingSource) {
        if (!StringUtils.hasText(bindingSource)) {
            return "MANUAL";
        }
        return bindingSource.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    /**
     * 解析优先级默认值。
     */
    private Integer resolvePriority(Integer priority) {
        if (priority == null) {
            return 100;
        }
        return Math.max(0, priority);
    }

    /**
     * 把数据库实体转换成更适合管理界面展示的视图对象。
     */
    private SyncPermissionBindingView toView(SyncPermissionPolicyBinding binding) {
        SyncPermissionBindingView view = new SyncPermissionBindingView();
        view.setId(binding.getId());
        view.setTenantId(fromStoredTenantId(binding.getTenantId()));
        view.setScopeType(Objects.equals(binding.getTenantId(), PLATFORM_SCOPE_TENANT_ID)
                ? "PLATFORM_GLOBAL" : "TENANT_OVERRIDE");
        view.setActorRole(binding.getActorRole());
        view.setBindingType(binding.getBindingType());
        view.setBindingValue(binding.getBindingValue());
        view.setBindingSource(binding.getBindingSource());
        view.setEnabled(binding.getEnabled());
        view.setPriority(binding.getPriority());
        view.setNote(binding.getNote());
        view.setCreatedBy(binding.getCreatedBy());
        view.setUpdatedBy(binding.getUpdatedBy());
        view.setCreateTime(binding.getCreateTime());
        view.setUpdateTime(binding.getUpdateTime());
        return view;
    }

    /**
     * 生成替换结果摘要。
     */
    private String buildReplaceSummary(Long targetTenantId,
                                       String targetRole,
                                       SyncPermissionBindingType bindingType,
                                       int disabledCount,
                                       int createdCount,
                                       boolean clearedAllValues) {
        return "已完成权限绑定替换：targetTenant="
                + (targetTenantId == null ? "PLATFORM" : targetTenantId)
                + "，targetRole=" + targetRole
                + "，bindingType=" + bindingType.name()
                + "，disabledCount=" + disabledCount
                + "，createdCount=" + createdCount
                + (clearedAllValues
                ? "。当前作用域下该类型数据库绑定已清空，后续权限快照会回退到全局绑定、配置默认值或代码默认推导。"
                : "。当前作用域下该类型数据库绑定已刷新，后续权限快照会优先采用新的数据库绑定。");
    }

    /**
     * 将权限绑定替换动作接入现有统一审计表。
     * 这样后续查看审计中心时，权限治理动作和任务治理动作可以出现在同一条审计主线上。
     */
    private void recordAudit(Long tenantId,
                             SyncAuditAction action,
                             Long actorId,
                             String actorRole,
                             String payload) {
        SyncAuditRecord record = new SyncAuditRecord();
        record.setTenantId(tenantId == null ? PLATFORM_SCOPE_TENANT_ID : tenantId);
        record.setActionType(action.name());
        record.setActorId(actorId);
        record.setActorRole(ActorRole.fromValue(actorRole).name());
        record.setActionPayload(payload);
        syncAuditRecordMapper.insert(record);
    }

    /**
     * 为权限治理动作构造统一的审计载荷。
     * 当前先保持字符串化 JSON 风格，便于后续继续对齐仓库里已有的审计写入模式。
     */
    private String buildPayload(Object... keyValues) {
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('"').append(keyValues[i]).append('"').append(':').append('"')
                    .append(i + 1 < keyValues.length ? String.valueOf(keyValues[i + 1]) : "")
                    .append('"');
        }
        builder.append('}');
        return builder.toString();
    }

    /**
     * 将业务层的租户语义转换成数据库存储语义。
     */
    private Long toStoredTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_SCOPE_TENANT_ID : tenantId;
    }

    /**
     * 将数据库里的平台全局哨兵值转回对外展示语义。
     */
    private Long fromStoredTenantId(Long tenantId) {
        return Objects.equals(tenantId, PLATFORM_SCOPE_TENANT_ID) ? null : tenantId;
    }
}
