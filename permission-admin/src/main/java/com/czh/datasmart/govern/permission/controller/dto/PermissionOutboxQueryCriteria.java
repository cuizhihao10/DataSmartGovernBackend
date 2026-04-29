/**
 * @Author : Cui
 * @Date: 2026/04/27 00:40
 * @Description DataSmart Govern Backend - PermissionOutboxQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import java.time.LocalDateTime;

/**
 * 权限 outbox 查询条件。
 *
 * <p>outbox 不是普通业务列表，它是生产可靠性控制面的一部分。
 * 运维人员通常会围绕“积压是否增长”“是否有 DEAD 事件”“某个租户是否一直失败”“某个 traceId 是否投递成功”来查询。
 * 因此这里预留了状态、事件类型、租户、资源、traceId 和时间范围等筛选维度。
 *
 * @param tenantId 事件所属租户。平台管理员可为空查询全平台，租户侧角色只能查看自身租户。
 * @param status 事件状态：PENDING、SENDING、SENT、FAILED、DEAD、IGNORED。
 * @param eventType 事件类型，例如 ROUTE_POLICY_CREATED。
 * @param resourceType 关联资源类型，例如 ROUTE_POLICY。
 * @param resourceId 关联资源 ID。
 * @param traceId 链路追踪 ID。
 * @param startTime 创建时间开始，闭区间。
 * @param endTime 创建时间结束，闭区间。
 * @param current 当前页码，从 1 开始。
 * @param size 每页大小，服务层会做上限保护。
 */
public record PermissionOutboxQueryCriteria(
        Long tenantId,
        String status,
        String eventType,
        String resourceType,
        String resourceId,
        String traceId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long current,
        Long size
) {
}
