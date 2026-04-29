/**
 * @Author : Cui
 * @Date: 2026/04/27 00:40
 * @Description DataSmart Govern Backend - PermissionAuditQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import java.time.LocalDateTime;

/**
 * 权限审计查询条件。
 *
 * <p>这个对象专门承接“审计中心”类列表查询的筛选条件。
 * 商业化后台通常不会只按一两个字段查审计，而是需要围绕事故排障、合规抽查、客户现场问题复盘来组合筛选：
 * 按租户看某个客户的全部操作，按操作者看某个人的权限变更，按 traceId 串联一次网关请求，
 * 按 resourceType/resourceId 定位某个策略、任务或数据源的历史动作。
 *
 * @param tenantId 目标租户 ID。平台管理员可以为空查询全平台；租户管理员、审计员和运营人员会被限制在自身租户。
 * @param actorId 操作者 ID，用于排查“某个人做过什么”。
 * @param actorRole 操作者角色，用于筛选平台管理员、租户管理员、服务账号等不同身份的行为。
 * @param resourceType 资源类型，例如 SYSTEM_SETTING、DATASOURCE、SYNC_TASK、AUDIT_LOG。
 * @param resourceId 资源 ID，权限策略变更当前使用 permission_route_policy:{id} 这类格式。
 * @param action 动作名称，例如 CREATE_ROUTE_POLICY、RETRY_PERMISSION_OUTBOX_EVENT。
 * @param result 结果，例如 SUCCESS、FAILED、DENIED。
 * @param traceId 链路追踪 ID，用于把 gateway、permission-admin 和后续审计记录串起来。
 * @param startTime 查询开始时间，闭区间。
 * @param endTime 查询结束时间，闭区间。
 * @param current 当前页码，从 1 开始。
 * @param size 每页大小，服务层会做上限保护，避免一次查询拖垮审计表。
 */
public record PermissionAuditQueryCriteria(
        Long tenantId,
        Long actorId,
        String actorRole,
        String resourceType,
        String resourceId,
        String action,
        String result,
        String traceId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long current,
        Long size
) {
}
