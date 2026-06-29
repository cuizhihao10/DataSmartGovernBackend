/**
 * @Author : Cui
 * @Date: 2026/06/29 23:25
 * @Description DataSmart Govern Backend - GatewayAuthenticationCapabilityView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.controller.dto;

import java.util.List;

/**
 * 网关认证中心能力说明。
 *
 * <p>该视图用于让前端、联调脚本、运维人员和后续文档清楚知道当前 gateway 的认证状态。
 * 它不是认证结果，也不包含任何 token、密码、密钥或用户资料。</p>
 *
 * @param enabled 认证中心 API 是否启用。
 * @param providerMode 当前认证模式。
 * @param issuer 认证中心标识。
 * @param oidcEnabled 是否启用 OIDC/JWT Resource Server 认证。
 * @param audienceValidationEnabled 是否启用 JWT audience 校验，生产环境应保持开启，避免其他资源服务器 token 被误用。
 * @param requiredAudiences gateway 接受的资源 audience 列表，例如 Keycloak client id 或企业 IdP API Identifier。
 * @param supportedAuthenticationTypes 当前支持的身份来源类型。
 * @param supportedActorTypes 支持的操作者类型。
 * @param productionRecommendation 生产化推荐。
 * @param payloadPolicy 载荷策略说明。
 */
public record GatewayAuthenticationCapabilityView(
        boolean enabled,
        String providerMode,
        String issuer,
        boolean oidcEnabled,
        boolean audienceValidationEnabled,
        List<String> requiredAudiences,
        List<String> supportedAuthenticationTypes,
        List<String> supportedActorTypes,
        String productionRecommendation,
        String payloadPolicy
) {
}
