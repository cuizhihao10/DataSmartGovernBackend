/**
 * @Author : Cui
 * @Date: 2026/04/26 20:33
 * @Description DataSmart Govern Backend - PermissionActorContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 权限管理操作人上下文。
 *
 * <p>permission-admin 既会被 gateway 调用，也可能在本地开发阶段被直接调用。
 * 因此高风险管理动作不能只依赖 gateway 已经做过授权，而应在权限中心自身也读取操作者上下文，
 * 形成“入口授权 + 服务内业务校验”的双层保护。
 *
 * <p>当前上下文来自 platform-common 约定的 X-DataSmart-* Header。
 * 后续接入正式 JWT/IdP 后，gateway 会把认证结果转成同一组 Header，下游服务不需要关心原始身份格式。
 *
 * @param tenantId 操作者所在租户，0 表示平台级或开发期默认租户。
 * @param actorId 操作者 ID。
 * @param actorRole 操作者角色，例如 PLATFORM_ADMINISTRATOR、TENANT_ADMINISTRATOR。
 * @param traceId 当前请求链路 ID，用于审计和排障。
 */
public record PermissionActorContext(Long tenantId,
                                     Long actorId,
                                     String actorRole,
                                     String traceId) {
}
