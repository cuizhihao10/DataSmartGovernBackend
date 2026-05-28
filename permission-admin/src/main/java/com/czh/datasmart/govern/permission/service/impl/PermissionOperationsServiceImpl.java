/**
 * @Author : Cui
 * @Date: 2026/04/27 00:45
 * @Description DataSmart Govern Backend - PermissionOperationsServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionAuditQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxIgnoreRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxOperationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxRetryRequest;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionEventOutboxMapper;
import com.czh.datasmart.govern.permission.service.PermissionOperationsService;
import com.czh.datasmart.govern.permission.service.support.PermissionOutboxOperationAuditSupport;
import com.czh.datasmart.govern.permission.service.support.PermissionPolicyFactCache;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 权限中心运维与审计服务实现。
 *
 * <p>这个类主要补齐 permission-admin 的“可运营性”：
 * 只有能创建策略还不够，商业系统还必须回答这些问题：
 * 1. 策略变更事件有没有成功投递到 Kafka？
 * 2. gateway 授权缓存没有失效时，是否能查到哪条事件卡住？
 * 3. Kafka 故障恢复后，是否能人工重试 DEAD 事件？
 * 4. 管理员修改策略、重试事件、忽略事件时，是否都有审计证据？
 *
 * <p>这里没有引入新的中间件，而是继续基于 MySQL outbox 和审计表扩展控制面。
 * 这样做符合当前仓库成熟度：先把产品运维闭环做出来，再逐步接入 Micrometer 指标、告警和更强的工作流审批。
 */
@Service
@RequiredArgsConstructor
public class PermissionOperationsServiceImpl implements PermissionOperationsService {

    /**
     * 平台全局租户 ID。
     *
     * <p>当前权限模型使用 0 表示平台默认策略或全局资源。
     */
    private static final long PLATFORM_TENANT_ID = 0L;

    /**
     * 列表查询默认页码。
     */
    private static final long DEFAULT_CURRENT = 1L;

    /**
     * 列表查询默认页大小。
     */
    private static final long DEFAULT_PAGE_SIZE = 20L;

    /**
     * 列表查询最大页大小。
     *
     * <p>审计表和 outbox 表会随时间持续增长。
     * 如果不限制 size，管理后台一次请求可能拉取大量 JSON 载荷，影响 MySQL、JVM 内存和网关响应。
     */
    private static final long MAX_PAGE_SIZE = 200L;

    /**
     * 可以查询 outbox 的角色。
     *
     * <p>平台管理员看全局，租户管理员看本租户，运营人员用于排障，审计员用于只读审查。
     */
    private static final Set<String> OUTBOX_VIEW_ROLES = Set.of(
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.OPERATOR.name(),
            PermissionRoleCode.AUDITOR.name()
    );

    /**
     * 可以人工重试 outbox 的角色。
     *
     * <p>重试不会修改权限事实，但会重新触发 gateway 缓存失效等下游动作，所以它属于运维动作。
     * 平台管理员可跨租户处理；运营人员仅能处理自身租户事件。
     */
    private static final Set<String> OUTBOX_RETRY_ROLES = Set.of(
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name(),
            PermissionRoleCode.OPERATOR.name()
    );

    /**
     * 可以查看审计记录的角色。
     *
     * <p>普通用户和服务账号默认不能直接浏览审计中心，避免审计数据反向泄露平台行为。
     */
    private static final Set<String> AUDIT_VIEW_ROLES = Set.of(
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.OPERATOR.name(),
            PermissionRoleCode.AUDITOR.name()
    );

    /**
     * 可以清理权限事实缓存的角色。
     *
     * <p>缓存清理不会直接修改权限事实，但会影响短时间内的授权延迟和数据库压力，
     * 因此它仍然属于受控运维动作，不应开放给普通用户或服务账号。
     */
    private static final Set<String> CACHE_EVICT_ROLES = Set.of(
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.OPERATOR.name()
    );

    private final PermissionEventOutboxMapper eventOutboxMapper;
    private final PermissionAuditRecordMapper auditRecordMapper;
    private final PermissionPolicyFactCache policyFactCache;
    private final PermissionOutboxOperationAuditSupport outboxOperationAuditSupport;

    /**
     * 分页查询 outbox 事件。
     *
     * <p>查询前会先做角色与租户边界校验：
     * 1. 平台管理员可以按条件查询全平台；
     * 2. 非平台管理员只能查询自己租户；
     * 3. 普通用户和服务账号不能进入 outbox 控制面。
     */
    @Override
    public PlatformPageResponse<PermissionEventOutbox> pageOutboxEvents(PermissionOutboxQueryCriteria criteria,
                                                                        PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, OUTBOX_VIEW_ROLES, "当前角色无权查看权限 outbox 事件");
        Long scopedTenantId = resolveScopedTenantId(criteria.tenantId(), actorContext);

        LambdaQueryWrapper<PermissionEventOutbox> wrapper = new LambdaQueryWrapper<PermissionEventOutbox>()
                .orderByDesc(PermissionEventOutbox::getCreateTime)
                .orderByDesc(PermissionEventOutbox::getId);
        if (scopedTenantId != null) {
            wrapper.eq(PermissionEventOutbox::getTenantId, scopedTenantId);
        }
        eqIfPresent(wrapper, PermissionEventOutbox::getStatus, normalizeCode(criteria.status()));
        eqIfPresent(wrapper, PermissionEventOutbox::getEventType, normalizeCode(criteria.eventType()));
        eqIfPresent(wrapper, PermissionEventOutbox::getResourceType, normalizeCode(criteria.resourceType()));
        eqIfPresent(wrapper, PermissionEventOutbox::getResourceId, trimToNull(criteria.resourceId()));
        eqIfPresent(wrapper, PermissionEventOutbox::getTraceId, trimToNull(criteria.traceId()));
        timeRange(wrapper, criteria.startTime(), criteria.endTime(), PermissionEventOutbox::getCreateTime);

        Page<PermissionEventOutbox> page = eventOutboxMapper.selectPage(page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 查询权限事实缓存快照。
     *
     * <p>查询缓存快照本身不改变系统状态，因此允许平台管理员、租户管理员、运营和审计角色查看。
     * 审计人员需要看到缓存状态，是为了把某次权限判定结果和当时缓存行为关联起来。
     */
    @Override
    public PermissionPolicyFactCache.CacheSnapshot snapshotPolicyCache(PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, AUDIT_VIEW_ROLES, "当前角色无权查看权限事实缓存状态");
        return policyFactCache.snapshot();
    }

    /**
     * 手工清理权限事实缓存。
     *
     * <p>平台管理员可以清理全量缓存；租户管理员和运营人员只能清理自己租户的缓存。
     * 这个限制很重要：如果租户管理员可以随意清理全局缓存，在多租户高并发场景下会影响其他租户授权性能。
     */
    @Override
    public PermissionPolicyFactCache.CacheSnapshot evictPolicyCache(Long tenantId,
                                                                    String reason,
                                                                    PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, CACHE_EVICT_ROLES, "当前角色无权清理权限事实缓存");
        String normalizedReason = "manual-policy-cache-evict:"
                + defaultText(reason, "管理员手工清理权限事实缓存");

        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole) && tenantId == null) {
            return policyFactCache.evictAll(normalizedReason);
        }

        Long scopedTenantId = resolveScopedTenantId(tenantId, actorContext);
        return policyFactCache.evictTenant(scopedTenantId, normalizedReason);
    }

    /**
     * 查询 outbox 详情。
     *
     * <p>允许传数据库主键或 eventId，是为了让两类排障场景都方便：
     * 管理后台列表一般使用主键，日志和 Kafka 消费者一般只知道 eventId。
     */
    @Override
    public PermissionEventOutbox getOutboxEvent(String eventIdOrPk, PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, OUTBOX_VIEW_ROLES, "当前角色无权查看权限 outbox 事件详情");
        PermissionEventOutbox event = findOutboxEventOrThrow(eventIdOrPk);
        validateTenantReadable(event.getTenantId(), actorContext);
        return event;
    }

    /**
     * 人工重试 outbox 事件。
     *
     * <p>当前只允许 FAILED、DEAD、IGNORED 回到 PENDING。
     * 这既覆盖 Kafka 故障修复后的补偿，也覆盖管理员误忽略后重新激活的场景。
     */
    @Override
    @Transactional
    public PermissionOutboxOperationResult retryOutboxEvent(Long id,
                                                            PermissionOutboxRetryRequest request,
                                                            PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, OUTBOX_RETRY_ROLES, "当前角色无权人工重试权限 outbox 事件");
        PermissionEventOutbox before = findOutboxEventByIdOrThrow(id);
        validateTenantReadable(before.getTenantId(), actorContext);

        String reason = "Manual retry: " + defaultText(request == null ? null : request.getReason(), "管理员人工重试");
        int updated = eventOutboxMapper.markManualRetry(id, truncate(reason, 1000));
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 FAILED、DEAD、IGNORED 状态的 outbox 事件可以人工重试，当前状态=" + before.getStatus());
        }

        PermissionEventOutbox after = findOutboxEventByIdOrThrow(id);
        outboxOperationAuditSupport.saveRetryAudit(actorContext, before, after, reason);
        return new PermissionOutboxOperationResult(after.getEventId(), after.getStatus(), "outbox 事件已重新放回待投递队列");
    }

    /**
     * 人工忽略 outbox 事件。
     *
     * <p>忽略代表管理员确认事件不再投递，因此只开放给平台管理员。
     * 运营人员可以重试，但不能把失败事件永久跳过，避免一线排障时无意扩大权限一致性风险。
     */
    @Override
    @Transactional
    public PermissionOutboxOperationResult ignoreOutboxEvent(Long id,
                                                             PermissionOutboxIgnoreRequest request,
                                                             PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        if (!PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "只有平台管理员可以忽略权限 outbox 事件");
        }

        PermissionEventOutbox before = findOutboxEventByIdOrThrow(id);
        String reason = "Manual ignore: " + defaultText(request == null ? null : request.getReason(), "管理员人工忽略");
        int updated = eventOutboxMapper.markIgnored(id, truncate(reason, 1000));
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 PENDING、FAILED、DEAD 状态的 outbox 事件可以忽略，当前状态=" + before.getStatus());
        }

        PermissionEventOutbox after = findOutboxEventByIdOrThrow(id);
        outboxOperationAuditSupport.saveIgnoreAudit(actorContext, before, after, reason);
        return new PermissionOutboxOperationResult(after.getEventId(), after.getStatus(), "outbox 事件已标记为 IGNORED");
    }

    /**
     * 分页查询权限审计记录。
     *
     * <p>审计查询是权限系统最重要的只读能力之一。
     * 这里默认倒序展示最新记录，符合排障和后台管理的常见使用方式。
     */
    @Override
    public PlatformPageResponse<PermissionAuditRecord> pageAuditRecords(PermissionAuditQueryCriteria criteria,
                                                                        PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, AUDIT_VIEW_ROLES, "当前角色无权查看权限审计记录");
        Long scopedTenantId = resolveScopedTenantId(criteria.tenantId(), actorContext);

        LambdaQueryWrapper<PermissionAuditRecord> wrapper = new LambdaQueryWrapper<PermissionAuditRecord>()
                .orderByDesc(PermissionAuditRecord::getCreateTime)
                .orderByDesc(PermissionAuditRecord::getId);
        if (scopedTenantId != null) {
            wrapper.eq(PermissionAuditRecord::getTenantId, scopedTenantId);
        }
        if (criteria.actorId() != null) {
            wrapper.eq(PermissionAuditRecord::getActorId, criteria.actorId());
        }
        eqIfPresent(wrapper, PermissionAuditRecord::getActorRole, normalizeCode(criteria.actorRole()));
        eqIfPresent(wrapper, PermissionAuditRecord::getResourceType, normalizeCode(criteria.resourceType()));
        eqIfPresent(wrapper, PermissionAuditRecord::getResourceId, trimToNull(criteria.resourceId()));
        eqIfPresent(wrapper, PermissionAuditRecord::getAction, normalizeCode(criteria.action()));
        eqIfPresent(wrapper, PermissionAuditRecord::getResult, normalizeCode(criteria.result()));
        eqIfPresent(wrapper, PermissionAuditRecord::getTraceId, trimToNull(criteria.traceId()));
        timeRange(wrapper, criteria.startTime(), criteria.endTime(), PermissionAuditRecord::getCreateTime);

        Page<PermissionAuditRecord> page = auditRecordMapper.selectPage(page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 读取并规范化操作者角色。
     */
    private String requireRole(PermissionActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "缺少可信操作者角色，不能访问权限运维接口");
        }
        return normalizeCode(actorContext.actorRole());
    }

    /**
     * 校验角色是否在允许集合中。
     */
    private void requireAnyRole(String actorRole, Set<String> allowedRoles, String message) {
        if (!allowedRoles.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, message + "，actorRole=" + actorRole);
        }
    }

    /**
     * 解析查询租户范围。
     *
     * <p>返回 null 表示平台管理员正在查询全平台。
     * 非平台管理员如果没有传 tenantId，则默认使用自己的租户；如果传了其他租户，则拒绝访问。
     */
    private Long resolveScopedTenantId(Long requestedTenantId, PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return requestedTenantId;
        }

        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        Long normalizedRequestedTenantId = requestedTenantId == null ? actorTenantId : requestedTenantId;
        if (!actorTenantId.equals(normalizedRequestedTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份只能访问自身租户的权限运维数据，actorTenantId=" + actorTenantId + ", requestedTenantId=" + normalizedRequestedTenantId);
        }
        return actorTenantId;
    }

    /**
     * 校验某条资源是否对当前操作者可见。
     */
    private void validateTenantReadable(Long resourceTenantId, PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }

        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        Long normalizedResourceTenantId = normalizeTenantId(resourceTenantId);
        if (!actorTenantId.equals(normalizedResourceTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能访问其他租户的权限运维数据，actorTenantId=" + actorTenantId + ", resourceTenantId=" + normalizedResourceTenantId);
        }
    }

    /**
     * 根据主键或 eventId 查询 outbox 事件。
     */
    private PermissionEventOutbox findOutboxEventOrThrow(String eventIdOrPk) {
        String value = trimToNull(eventIdOrPk);
        if (value == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "eventIdOrPk 不能为空");
        }
        if (value.chars().allMatch(Character::isDigit)) {
            return findOutboxEventByIdOrThrow(Long.valueOf(value));
        }
        PermissionEventOutbox event = eventOutboxMapper.selectOne(new LambdaQueryWrapper<PermissionEventOutbox>()
                .eq(PermissionEventOutbox::getEventId, value));
        if (event == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "权限 outbox 事件不存在：" + value);
        }
        return event;
    }

    /**
     * 根据数据库主键查询 outbox 事件。
     */
    private PermissionEventOutbox findOutboxEventByIdOrThrow(Long id) {
        PermissionEventOutbox event = eventOutboxMapper.selectById(id);
        if (event == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "权限 outbox 事件不存在：" + id);
        }
        return event;
    }

    /**
     * 生成分页对象并做页大小保护。
     */
    private <T> Page<T> page(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    /**
     * 根据条件追加等值查询。
     */
    private <T> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                 String value) {
        if (value != null && !value.isBlank()) {
            wrapper.eq(column, value);
        }
    }

    /**
     * 根据条件追加时间范围查询。
     */
    private <T> void timeRange(LambdaQueryWrapper<T> wrapper,
                               LocalDateTime startTime,
                               LocalDateTime endTime,
                               com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column) {
        if (startTime != null) {
            wrapper.ge(column, startTime);
        }
        if (endTime != null) {
            wrapper.le(column, endTime);
        }
    }

    /**
     * 编码规范化。
     */
    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 租户 ID 归一化。
     */
    private Long normalizeTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    /**
     * 去除空白字符串。
     */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 默认文本。
     */
    private String defaultText(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    /**
     * 截断字符串，避免超过数据库字段长度。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

}
