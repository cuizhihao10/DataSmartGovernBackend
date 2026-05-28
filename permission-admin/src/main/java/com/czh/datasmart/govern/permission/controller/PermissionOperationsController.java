/**
 * @Author : Cui
 * @Date: 2026/04/27 00:48
 * @Description DataSmart Govern Backend - PermissionOperationsController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionAuditQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxIgnoreRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxOperationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxRetryRequest;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.service.PermissionOperationsService;
import com.czh.datasmart.govern.permission.service.support.PermissionPolicyFactCache;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 权限中心运维与审计控制器。
 *
 * <p>这个 Controller 面向管理后台、运维人员和审计人员，不直接服务普通业务流量。
 * 它补齐了商业化权限系统必须具备的“可查询、可恢复、可追责”能力：
 * 1. outbox 事件列表和详情：排查权限策略变更事件是否成功投递；
 * 2. outbox 手工重试：Kafka 或 gateway 缓存失效链路恢复后，重新投递 DEAD/FAILED 事件；
 * 3. outbox 手工忽略：确认某条事件不再需要投递时，留下受控处置记录；
 * 4. 审计记录查询：按租户、操作者、资源、动作、结果、traceId 和时间范围追溯权限域行为。
 *
 * <p>这里继续保留 /permissions 与 /api/permission 双前缀：
 * /permissions 便于本服务本地调试；/api/permission 适配当前 gateway 的外部访问路径。
 */
@RestController
@RequestMapping({"/permissions/operations", "/api/permission/operations"})
@RequiredArgsConstructor
public class PermissionOperationsController {

    private final PermissionOperationsService permissionOperationsService;

    /**
     * 分页查询权限 outbox 事件。
     *
     * <p>典型使用场景：
     * 1. 运营人员查询 status=DEAD，定位长期无法发送的事件；
     * 2. 租户管理员查询本租户策略变更事件是否已经 SENT；
     * 3. 平台管理员按 traceId 或 resourceId 追踪某次策略变更的消息投递链路。
     */
    @GetMapping("/outbox/events")
    public PlatformApiResponse<PlatformPageResponse<PermissionEventOutbox>> pageOutboxEvents(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String requestTraceId) {
        PermissionOutboxQueryCriteria criteria = new PermissionOutboxQueryCriteria(
                tenantId, status, eventType, resourceType, resourceId, traceId, startTime, endTime, current, size);
        return PlatformApiResponse.success(permissionOperationsService.pageOutboxEvents(criteria,
                actorContext(actorTenantId, actorId, actorRole, requestTraceId)), requestTraceId);
    }

    /**
     * 查询单条权限 outbox 事件详情。
     *
     * <p>eventIdOrPk 支持两种输入：
     * 1. 数据库主键，例如 123；
     * 2. 事件 ID，例如 permission-policy-xxx。
     * 这样管理后台和日志排障都可以用同一个端点。
     */
    @GetMapping("/outbox/events/{eventIdOrPk}")
    public PlatformApiResponse<PermissionEventOutbox> getOutboxEvent(
            @PathVariable String eventIdOrPk,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(permissionOperationsService.getOutboxEvent(eventIdOrPk,
                actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 人工重试权限 outbox 事件。
     *
     * <p>该接口不会直接发送 Kafka，而是把事件重新置为 PENDING。
     * 后台投递器会按既有节奏扫描并发送，这样可以复用 outbox 的状态机、超时、重试和日志逻辑。
     */
    @PostMapping("/outbox/events/{id}/retry")
    public PlatformApiResponse<PermissionOutboxOperationResult> retryOutboxEvent(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) PermissionOutboxRetryRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("outbox 事件已提交人工重试",
                permissionOperationsService.retryOutboxEvent(id, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 人工忽略权限 outbox 事件。
     *
     * <p>忽略不是删除：事件仍保留在 outbox 表中，只是状态变为 IGNORED。
     * 这样可以避免管理动作抹掉事故证据，也方便后续统计“哪些事件被人工跳过”。
     */
    @PostMapping("/outbox/events/{id}/ignore")
    public PlatformApiResponse<PermissionOutboxOperationResult> ignoreOutboxEvent(
            @PathVariable Long id,
            @Valid @RequestBody PermissionOutboxIgnoreRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("outbox 事件已标记为忽略",
                permissionOperationsService.ignoreOutboxEvent(id, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 分页查询权限审计记录。
     *
     * <p>这个接口让 permission-admin 拥有第一版“审计中心”能力。
     * 它不仅能查权限判定记录，也能查路由策略变更、outbox 人工恢复、outbox 忽略等管理动作。
     */
    @GetMapping("/audit-records")
    public PlatformApiResponse<PlatformPageResponse<PermissionAuditRecord>> pageAuditRecords(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String actorRole,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long requestActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String requestActorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String requestTraceId) {
        PermissionAuditQueryCriteria criteria = new PermissionAuditQueryCriteria(
                tenantId, actorId, actorRole, resourceType, resourceId, action, result, traceId, startTime, endTime, current, size);
        return PlatformApiResponse.success(permissionOperationsService.pageAuditRecords(criteria,
                actorContext(actorTenantId, requestActorId, requestActorRole, requestTraceId)), requestTraceId);
    }

    /**
     * 查询 permission-admin 内部权限事实缓存快照。
     *
     * <p>这个接口不是给普通业务流量使用的，而是给管理后台、运维面板和排障工具使用。
     * 当用户反馈“权限刚改完但结果不对”时，排障人员可以先看这里：
     * 1. 缓存是否启用；
     * 2. 当前缓存条目数是否异常膨胀；
     * 3. hit/miss/load 是否符合预期；
     * 4. 最近一次失效时间是否晚于策略变更时间。
     */
    @GetMapping("/policy-cache")
    public PlatformApiResponse<PermissionPolicyFactCache.CacheSnapshot> snapshotPolicyCache(
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(permissionOperationsService.snapshotPolicyCache(
                actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 手工清理 permission-admin 内部权限事实缓存。
     *
     * <p>tenantId 为空时，只有平台管理员可以清理全量缓存。
     * 租户管理员和运营人员只能清理自己租户范围，避免影响其他租户授权性能。
     */
    @PostMapping("/policy-cache/evict")
    public PlatformApiResponse<PermissionPolicyFactCache.CacheSnapshot> evictPolicyCache(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String reason,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("权限事实缓存已清理",
                permissionOperationsService.evictPolicyCache(tenantId, reason,
                        actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 从平台 Header 构建操作者上下文。
     *
     * <p>Controller 不直接信任前端自报身份。
     * 这些 Header 应由 gateway 在认证后写入，permission-admin 再基于它们做服务内二次校验。
     */
    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}
