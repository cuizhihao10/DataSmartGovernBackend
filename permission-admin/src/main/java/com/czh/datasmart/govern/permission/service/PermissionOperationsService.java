/**
 * @Author : Cui
 * @Date: 2026/04/27 00:40
 * @Description DataSmart Govern Backend - PermissionOperationsService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionAuditQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxIgnoreRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxOperationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionOutboxRetryRequest;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.service.support.PermissionPolicyFactCache;

/**
 * 权限中心运维与审计服务。
 *
 * <p>这个服务刻意和 PermissionAdminService 分开：
 * PermissionAdminService 负责“权限事实”的读写，例如角色、菜单、路由策略、数据范围；
 * PermissionOperationsService 负责“权限系统自身如何被运营、排障和审计”，例如 outbox 查询、失败事件恢复、审计记录查询。
 *
 * <p>这种拆分能避免权限中心后续变成一个所有逻辑都塞在一起的巨大 Service，也更贴近商业产品中的控制面设计。
 */
public interface PermissionOperationsService {

    /**
     * 分页查询权限 outbox 事件。
     *
     * @param criteria 查询条件。
     * @param actorContext 当前操作者上下文，用于租户隔离和角色校验。
     * @return 分页事件列表。
     */
    PlatformPageResponse<PermissionEventOutbox> pageOutboxEvents(PermissionOutboxQueryCriteria criteria,
                                                                 PermissionActorContext actorContext);

    /**
     * 查询单条 outbox 事件详情。
     *
     * @param eventIdOrPk 可以是数据库主键，也可以是事件 ID，方便管理后台和排障人员使用。
     * @param actorContext 当前操作者上下文。
     * @return outbox 事件详情。
     */
    PermissionEventOutbox getOutboxEvent(String eventIdOrPk, PermissionActorContext actorContext);

    /**
     * 人工重试 DEAD/FAILED/IGNORED 事件。
     *
     * @param id outbox 数据库主键。
     * @param request 重试说明。
     * @param actorContext 当前操作者上下文。
     * @return 操作结果。
     */
    PermissionOutboxOperationResult retryOutboxEvent(Long id,
                                                     PermissionOutboxRetryRequest request,
                                                     PermissionActorContext actorContext);

    /**
     * 人工忽略 PENDING/FAILED/DEAD 事件。
     *
     * @param id outbox 数据库主键。
     * @param request 忽略原因。
     * @param actorContext 当前操作者上下文。
     * @return 操作结果。
     */
    PermissionOutboxOperationResult ignoreOutboxEvent(Long id,
                                                      PermissionOutboxIgnoreRequest request,
                                                      PermissionActorContext actorContext);

    /**
     * 分页查询权限审计记录。
     *
     * @param criteria 查询条件。
     * @param actorContext 当前操作者上下文，用于限制查询范围。
     * @return 分页审计列表。
     */
    PlatformPageResponse<PermissionAuditRecord> pageAuditRecords(PermissionAuditQueryCriteria criteria,
                                                                 PermissionActorContext actorContext);

    /**
     * 查询 permission-admin 内部权限事实缓存快照。
     *
     * <p>该能力面向运维和管理员，用于观察缓存是否启用、当前条目数、命中/未命中次数和最近失效时间。
     * 在商业化系统里，权限缓存如果不可观察，排查“为什么这个用户现在还能访问/不能访问”会非常困难。
     */
    PermissionPolicyFactCache.CacheSnapshot snapshotPolicyCache(PermissionActorContext actorContext);

    /**
     * 手工清理 permission-admin 内部权限事实缓存。
     *
     * @param tenantId 目标租户。为空表示全量清理；非平台管理员通常只能清理自己租户。
     * @param reason 清理原因，写入日志和返回信息，方便后续审计追踪。
     * @param actorContext 当前操作者上下文。
     * @return 清理后的缓存快照。
     */
    PermissionPolicyFactCache.CacheSnapshot evictPolicyCache(Long tenantId,
                                                             String reason,
                                                             PermissionActorContext actorContext);
}
