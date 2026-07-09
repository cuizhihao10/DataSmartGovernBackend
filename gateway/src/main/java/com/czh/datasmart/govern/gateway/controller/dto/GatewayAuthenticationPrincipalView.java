/**
 * @Author : Cui
 * @Date: 2026/06/29 23:24
 * @Description DataSmart Govern Backend - GatewayAuthenticationPrincipalView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.controller.dto;

import java.util.List;

/**
 * 网关认证中心解析出的当前身份视图。
 *
 * <p>该对象只描述“当前请求被解析成了哪个平台身份”，不描述“这个身份能做什么”。
 * 授权能力必须继续由 permission-admin 决定，避免认证中心越界承担 RBAC/ABAC 策略。</p>
 *
 * @param authenticated 是否已经解析到可信或受控开发身份。
 * @param authenticationType 身份来源类型，例如 OIDC_JWT、TRUSTED_PLATFORM_CONTEXT、ANONYMOUS。
 * @param tenantId 租户 ID。
 * @param actorId 操作者 ID。
 * @param actorRole 操作者角色。
 * @param actorType 操作者类型。
 * @param workspaceId 工作空间 ID。
 * @param dataScopeLevel permission-admin 或可信上游下发的数据范围级别。
 * @param authorizedProjectIds 项目集合视图。对于 `/auth/session` 这类认证中心视图，它表示 OIDC/Keycloak token
 *                             中声明的项目候选集合，主要用于前端项目切换提示和登录态诊断；对于经过
 *                             GatewayAuthorizationFilter 写入下游 Header 的业务请求，才表示 permission-admin
 *                             根据项目成员关系和数据范围策略物化后的可信授权集合。
 * @param traceId 当前链路追踪 ID。
 * @param issueCodes 解析过程中的低敏提示码。
 * @param payloadPolicy 载荷策略说明，提醒调用方不要扩展敏感字段。
 */
public record GatewayAuthenticationPrincipalView(
        boolean authenticated,
        String authenticationType,
        Long tenantId,
        Long actorId,
        String actorRole,
        String actorType,
        String workspaceId,
        String dataScopeLevel,
        List<Long> authorizedProjectIds,
        String traceId,
        List<String> issueCodes,
        String payloadPolicy
) {
}
