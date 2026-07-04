/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProvisioningCapabilityView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import java.util.List;

/**
 * 身份供应能力说明视图。
 *
 * <p>该响应用于管理后台和 smoke 脚本确认当前环境采用什么身份路线。它刻意不返回 Keycloak endpoint、
 * admin username、client secret、admin token 等敏感配置，只返回低敏能力事实。
 */
public record IdentityProvisioningCapabilityView(
        boolean enabled,
        String providerMode,
        boolean storesPasswordInDataSmart,
        boolean storesShadowIdentityInDataSmart,
        String tokenOwner,
        List<String> supportedOperations,
        List<String> evidenceCodes,
        String note) {
}
